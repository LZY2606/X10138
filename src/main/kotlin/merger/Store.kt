package merger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** CNode 的持久化线格式：显式区分 missing / null / 标量类型。 */
object CNodeCodec {
    private val f = JsonNodeFactory.instance

    fun toWire(n: CNode?): JsonNode = when (n) {
        null -> f.objectNode().put("\$kind", "missing")
        is CNode.CNull -> f.objectNode().put("\$kind", "null")
        is CNode.CScalar -> f.objectNode().put("\$kind", "scalar").apply {
            when (val v = n.value) {
                is String -> put("type", "s").put("v", v)
                is Boolean -> put("type", "b").put("v", v)
                is Long -> put("type", "i").put("v", v)
                is Int -> put("type", "i").put("v", v)
                is Double -> put("type", "d").put("v", v)
                else -> put("type", "s").put("v", v.toString())
            }
        }
        is CNode.CArray -> f.objectNode().put("\$kind", "array")
            .set<JsonNode>("items", f.arrayNode().apply { n.items.forEach { add(toWire(it)) } })
        is CNode.CObject -> f.objectNode().put("\$kind", "object")
            .set<JsonNode>("entries", f.objectNode().apply {
                n.entries.forEach { (k, v) -> set<JsonNode>(k, toWire(v)) }
            })
    }

    fun fromWire(j: JsonNode): CNode? = when (j.get("\$kind")?.asText()) {
        "missing" -> null
        "null" -> CNode.CNull()
        "scalar" -> when (j.get("type").asText()) {
            "s" -> CNode.CScalar(j.get("v").asText())
            "b" -> CNode.CScalar(j.get("v").asBoolean())
            "i" -> CNode.CScalar(j.get("v").asLong())
            else -> CNode.CScalar(j.get("v").asDouble())
        }
        "array" -> CNode.CArray(j.get("items").mapNotNull { fromWire(it) })
        "object" -> {
            val map = LinkedHashMap<String, CNode>()
            val it = j.get("entries").fields()
            while (it.hasNext()) {
                val (k, v) = it.next()
                fromWire(v)?.let { map[k] = it }
            }
            CNode.CObject(map)
        }
        else -> throw IllegalArgumentException("非法线格式: $j")
    }

    fun originsToWire(os: List<Origin>): JsonNode =
        f.arrayNode().apply {
            os.forEach { o -> add(f.objectNode().put("source", o.source).put("note", o.note)) }
        }

    fun originsFromWire(j: JsonNode): List<Origin> =
        j.map { Origin(it.get("source").asText(), it.get("note")?.takeUnless { n -> n.isNull }?.asText()) }
}

data class SessionInput(val format: Format, val base: String, val ours: String, val theirs: String)

data class Resolution(val path: String, val choice: String, val value: CNode? = null)

data class Session(
    val id: String,
    val name: String,
    val input: SessionInput,
    val policySet: PolicySet,
    var version: Long = 0,
    val decisions: MutableList<Decision> = mutableListOf(),
    var result: MergeResult? = null,
    var createdAt: Long = System.currentTimeMillis()
)

data class ManifestEntry(val path: String, val fingerprint: String, val origins: List<Origin>)

data class ExportBundle(
    val sessionId: String,
    val format: Format,
    val policyVersion: String,
    val text: String,
    val manifest: List<ManifestEntry>,
    val resultFingerprint: String
)

class Store(dir: Path) {
    private val sessionsDir = dir.resolve("sessions")
    private val decisionsFile = dir.resolve("decisions.json")
    private val sessions = LinkedHashMap<String, Session>()
    private val globalDecisions = mutableListOf<Decision>()

    init {
        Files.createDirectories(sessionsDir)
        loadDecisions()
        loadSessions()
    }

    @Synchronized
    fun createSession(name: String, input: SessionInput, policies: PolicySet): Session {
        val s = Session(UUID.randomUUID().toString().substring(0, 8), name, input, policies)
        remerge(s)
        sessions[s.id] = s
        persist(s)
        return s
    }

    @Synchronized
    fun get(id: String): Session? = sessions[id]

    @Synchronized
    fun list(): List<Session> = sessions.values.toList()

    fun decisions(): List<Decision> = globalDecisions.toList()

    /** 重放合并：带上历史裁决（精确匹配自动套用，否则降级为建议）。 */
    @Synchronized
    fun remerge(s: Session) {
        val base = parse(s.input.base, s.input.format, "base")
        val ours = parse(s.input.ours, s.input.format, "ours")
        val theirs = parse(s.input.theirs, s.input.format, "theirs")
        s.result = Merger(s.policySet, globalDecisions + s.decisions).merge(base, ours, theirs)
    }

    /**
     * 批量提交裁决（乐观版本控制）：expectedVersion 不匹配返回 null（调用方转 409）。
     * 过期页面无法覆盖他人已解决的项。
     */
    @Synchronized
    fun resolve(id: String, expectedVersion: Long, resolutions: List<Resolution>): Session? {
        val s = sessions[id] ?: return null
        if (s.version != expectedVersion) return null
        val byPath = s.result?.conflicts?.associateBy { it.path } ?: emptyMap()
        for (r in resolutions) {
            val c = byPath[r.path] ?: continue
            s.decisions.add(
                Decision(
                    id = "d-" + UUID.randomUUID().toString().substring(0, 8),
                    path = r.path, baseFp = c.baseFp, oursFp = c.oursFp, theirsFp = c.theirsFp,
                    choice = r.choice, value = r.value
                )
            )
        }
        s.version += 1
        remerge(s)
        persist(s)
        persistDecisions()
        return s
    }

    /** 导出：文本 + 每个结果路径的来源链与指纹清单。 */
    fun export(s: Session, format: Format): ExportBundle {
        val out = s.result?.output
        val text = out?.let { serialize(it, format) } ?: ""
        val manifest = mutableListOf<ManifestEntry>()
        fun walk(n: CNode?, path: List<Seg>) {
            if (n == null) return
            manifest.add(ManifestEntry(pathToString(path), fingerprint(n), n.origins))
            when (n) {
                is CNode.CObject -> n.entries.forEach { (k, v) -> walk(v, path + Seg.Key(k)) }
                is CNode.CArray -> n.items.forEachIndexed { i, v -> walk(v, path + Seg.Idx(i)) }
                else -> {}
            }
        }
        walk(out, emptyList())
        return ExportBundle(s.id, format, s.policySet.version, text, manifest, fingerprint(out))
    }

    /** 导入校验：重解析导出文本，逐条核对路径、来源链与指纹。 */
    fun verifyImport(bundle: ExportBundle): Map<String, Any> {
        val root = parse(bundle.text, bundle.format, "import")
        val mismatches = mutableListOf<String>()
        for (e in bundle.manifest) {
            val node = nodeAt(root, pathFromString(e.path))
            if (node == null) {
                // 根路径 "/" 或缺失
                if (e.path != "/") mismatches += "路径缺失: ${e.path}"
                continue
            }
            if (fingerprint(node) != e.fingerprint) mismatches += "指纹不一致: ${e.path}"
        }
        val ok = mismatches.isEmpty() && fingerprint(root) == bundle.resultFingerprint
        return mapOf(
            "ok" to ok,
            "resultFingerprint" to fingerprint(root),
            "expectedFingerprint" to bundle.resultFingerprint,
            "manifestSize" to bundle.manifest.size,
            "mismatches" to mismatches
        )
    }

    // ---------- 持久化 ----------

    private fun persist(s: Session) {
        val f = JsonNodeFactory.instance
        val j = f.objectNode()
        j.put("id", s.id).put("name", s.name).put("version", s.version)
            .put("createdAt", s.createdAt)
        j.putObject("input")
            .put("format", s.input.format.name)
            .put("base", s.input.base).put("ours", s.input.ours).put("theirs", s.input.theirs)
        val polJ = j.putObject("policies")
        polJ.put("version", s.policySet.version)
        polJ.set<JsonNode>("arrays", f.arrayNode().apply {
                s.policySet.arrays.values.forEach { p ->
                    add(f.objectNode().put("path", p.path)
                        .put("strategy", p.strategy.name).put("idKey", p.idKey))
                }
            })
        j.set<JsonNode>("decisions", f.arrayNode().apply {
            s.decisions.forEach { add(decisionToWire(it)) }
        })
        s.result?.let { r ->
            j.set<JsonNode>("output", r.output?.let { CNodeCodec.toWire(it) } ?: f.nullNode())
            j.set<JsonNode>("auto", f.arrayNode().apply {
                r.auto.forEach { a ->
                    add(f.objectNode().put("path", a.path).put("fingerprint", a.fingerprint)
                        .set<JsonNode>("origins", CNodeCodec.originsToWire(a.origins)))
                }
            })
            j.set<JsonNode>("conflicts", f.arrayNode().apply {
                r.conflicts.forEach { c -> add(conflictToWire(c)) }
            })
        }
        Files.writeString(sessionsDir.resolve("${s.id}.json"), jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(j))
    }

    private fun decisionToWire(d: Decision): JsonNode {
        val f = JsonNodeFactory.instance
        return f.objectNode().put("id", d.id).put("path", d.path)
            .put("baseFp", d.baseFp).put("oursFp", d.oursFp).put("theirsFp", d.theirsFp)
            .put("choice", d.choice).put("createdAt", d.createdAt)
            .set<JsonNode>("value", d.value?.let { CNodeCodec.toWire(it) } ?: f.nullNode())
    }

    private fun decisionFromWire(j: JsonNode) = Decision(
        id = j.get("id").asText(), path = j.get("path").asText(),
        baseFp = j.get("baseFp").asText(), oursFp = j.get("oursFp").asText(),
        theirsFp = j.get("theirsFp").asText(), choice = j.get("choice").asText(),
        value = j.get("value")?.takeUnless { it.isNull }?.let { CNodeCodec.fromWire(it) },
        createdAt = j.get("createdAt")?.asLong() ?: 0L
    )

    private fun conflictToWire(c: Conflict): JsonNode {
        val f = JsonNodeFactory.instance
        val j = f.objectNode().put("path", c.path).put("reason", c.reason).put("detail", c.detail)
            .put("baseFp", c.baseFp).put("oursFp", c.oursFp).put("theirsFp", c.theirsFp)
        j.set<JsonNode>("base", c.base?.let { CNodeCodec.toWire(it) } ?: f.nullNode())
        j.set<JsonNode>("ours", c.ours?.let { CNodeCodec.toWire(it) } ?: f.nullNode())
        j.set<JsonNode>("theirs", c.theirs?.let { CNodeCodec.toWire(it) } ?: f.nullNode())
        return j
    }

    private fun persistDecisions() {
        val f = JsonNodeFactory.instance
        val arr = f.arrayNode()
        globalDecisions.forEach { arr.add(decisionToWire(it)) }
        sessions.values.forEach { s -> s.decisions.forEach { arr.add(decisionToWire(it)) } }
        Files.writeString(decisionsFile, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr))
    }

    private fun loadDecisions() {
        if (!Files.exists(decisionsFile)) return
        val arr = jsonMapper.readTree(Files.readString(decisionsFile))
        arr?.forEach { globalDecisions.add(decisionFromWire(it)) }
    }

    private fun loadSessions() {
        Files.list(sessionsDir).use { stream ->
            stream.filter { it.toString().endsWith(".json") }.forEach { p ->
                try {
                    val j = jsonMapper.readTree(Files.readString(p))
                    val inp = j.get("input")
                    val policiesNode = j.get("policies")
                    val arrays = mutableMapOf<String, ArrayPolicy>()
                    policiesNode.get("arrays")?.forEach { a ->
                        val pol = ArrayPolicy(
                            a.get("path").asText(),
                            ArrayStrategy.valueOf(a.get("strategy").asText()),
                            a.get("idKey")?.asText() ?: "id"
                        )
                        arrays[pol.path] = pol
                    }
                    val s = Session(
                        id = j.get("id").asText(), name = j.get("name").asText(),
                        input = SessionInput(
                            Format.valueOf(inp.get("format").asText()),
                            inp.get("base").asText(), inp.get("ours").asText(), inp.get("theirs").asText()
                        ),
                        policySet = PolicySet(policiesNode.get("version").asText(), arrays),
                        version = j.get("version").asLong(),
                        createdAt = j.get("createdAt")?.asLong() ?: 0L
                    )
                    j.get("decisions")?.forEach { s.decisions.add(decisionFromWire(it)) }
                    remerge(s)
                    sessions[s.id] = s
                } catch (e: Exception) {
                    System.err.println("跳过损坏的会话文件 $p: ${e.message}")
                }
            }
        }
    }
}
