package semmerge.session

import semmerge.json.JsonParser
import semmerge.json.JsonWriter
import semmerge.merge.ConflictKind
import semmerge.merge.ListStrategy
import semmerge.merge.PolicyEntry
import semmerge.merge.PolicyRegistry
import semmerge.merge.Resolution
import semmerge.model.Path
import semmerge.model.SNode
import semmerge.session.JsonCodec.asList
import semmerge.session.JsonCodec.asMap
import semmerge.session.JsonCodec.asString
import semmerge.session.JsonCodec.bool
import semmerge.session.JsonCodec.list
import semmerge.session.JsonCodec.map
import semmerge.session.JsonCodec.num
import semmerge.session.JsonCodec.obj
import semmerge.session.JsonCodec.str
import semmerge.session.JsonCodec.strOr
import semmerge.session.JsonCodec.longOr
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path as JPath
import java.time.Instant

/**
 * Persists:
 *  - the three raw inputs exactly as typed
 *  - their parsed fingerprints
 *  - the policy registry with its monotonic version
 *  - the full decision history (all replays, newest first)
 *  - the session revision for optimistic locking
 */
class SessionStore(private val dir: JPath) {

    private val stateFile = dir.resolve("session.json")
    private val inputsDir = dir.resolve("inputs")

    fun load(): SessionState? {
        if (!Files.exists(stateFile)) return null
        val root = JsonParser.parse(Files.readString(stateFile, StandardCharsets.UTF_8)).asMap()

        val inputs = InputSide.entries.associateWith { side ->
            val textFile = inputsDir.resolve("${side.key}.txt")
            val text = if (Files.exists(textFile)) Files.readString(textFile, StandardCharsets.UTF_8) else ""
            val format = root.map("inputFormats").strOr(side.key, "yaml")
            RawInput(text, format)
        }
        val parsed = inputs.mapValues { (_, raw) -> InputParser.parse(raw) }

        val policyVersion = root.longOr("policyVersion", 1).toInt()
        val policies = root.list("policies").map { n ->
            val m = n.asMap()
            PolicyEntry(
                path = PathRenderer.parse(m.str("path")),
                strategy = ListStrategy.entries.first { it.id == m.str("strategy") },
                idField = m.strOr("idField", "id"),
                version = m["version"]?.let { (it as semmerge.model.SScalar).text.toInt() } ?: 0,
            )
        }.associateBy { it.path.render() }

        val decisions = root.list("decisions")
            .map { semmerge.merge.SessionStoreJson.decodeDecision(it) }
            .groupBy { it.conflictKey }

        return SessionState(
            inputs = inputs,
            parsed = parsed,
            policies = PolicyRegistry(policies, policyVersion),
            decisions = decisions,
            version = root.longOr("version", 1),
        )
    }

    fun save(state: SessionState) {
        Files.createDirectories(dir)
        Files.createDirectories(inputsDir)
        for (side in InputSide.entries) {
            val raw = state.inputs[side] ?: RawInput.EMPTY
            Files.writeString(inputsDir.resolve("${side.key}.txt"), raw.text, StandardCharsets.UTF_8)
        }

        val formats = obj(*InputSide.entries.map {
            it.key to JsonCodec.str(state.inputs[it]?.format ?: "yaml")
        }.toTypedArray())

        val policyNodes = state.policies.all().map { p ->
            obj(
                "path" to JsonCodec.str(p.path.render()),
                "strategy" to JsonCodec.str(p.strategy.id),
                "idField" to JsonCodec.str(p.idField),
                "version" to num(p.version),
            )
        }

        val decisionNodes = state.decisions.values.flatten().map { semmerge.merge.SessionStoreJson.encodeDecision(it) }

        val root = obj(
            "version" to num(state.version),
            "policyVersion" to num(state.policies.version.toLong()),
            "inputFormats" to formats,
            "policies" to JsonCodec.list(policyNodes),
            "decisions" to JsonCodec.list(decisionNodes),
        )
        Files.writeString(stateFile, JsonWriter.write(root), StandardCharsets.UTF_8)
    }

}

object PathRenderer {
    fun parse(text: String): Path {
        if (text == "$" || text.isEmpty()) return Path.ROOT
        var rest = text
        if (rest.startsWith("$")) rest = rest.substring(1)
        val segs = mutableListOf<Path.Segment>()
        var k = 0
        while (k < rest.length) {
            val c = rest[k]
            when (c) {
                '.' -> {
                    var e = k + 1
                    val sb = StringBuilder()
                    if (e < rest.length && rest[e] == '[') {
                        k = e - 1
                        continue
                    }
                    while (e < rest.length && rest[e] != '.' && rest[e] != '[') sb.append(rest[e++])
                    segs += Path.Segment.Key(sb.toString())
                    k = e
                }
                '[' -> {
                    val end = rest.indexOf(']', k)
                    val inner = rest.substring(k + 1, end)
                    when {
                        inner.startsWith("id=") -> segs += Path.Segment.Id(unquote(inner.substring(3)))
                        inner.startsWith("'") -> segs += Path.Segment.Key(unquote(inner))
                        else -> segs += Path.Segment.Index(inner.toInt())
                    }
                    k = end + 1
                }
                else -> {
                    var e = k
                    val sb = StringBuilder()
                    while (e < rest.length && rest[e] != '.' && rest[e] != '[') sb.append(rest[e++])
                    segs += Path.Segment.Key(sb.toString())
                    k = e
                }
            }
        }
        return Path(segs)
    }

    private fun unquote(s: String): String =
        if (s.startsWith("'") && s.endsWith("'")) s.substring(1, s.length - 1).replace("\\'", "'") else s
}
