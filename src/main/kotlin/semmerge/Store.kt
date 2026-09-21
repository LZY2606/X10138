package semmerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path as FsPath
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class DocInput(val id: String, val format: String, val text: String)

data class SessionState(
    val id: String,
    val version: Long,
    val base: DocInput,
    val left: DocInput,
    val right: DocInput,
    val baseModel: Node,
    val leftModel: Node,
    val rightModel: Node,
    val policies: PolicySet,
    val decisions: List<Decision>,
    val result: MergeResult
)

/** 文件型持久化：原始输入、解析模型、策略版本、裁决历史、输出全部落盘。 */
class SessionStore(private val dir: FsPath) {
    private val json = Json { prettyPrint = true }
    private val lock = ReentrantLock()

    @Volatile
    var state: SessionState? = null
        private set

    init {
        Files.createDirectories(dir)
        load()
    }

    fun <T> withLock(block: () -> T): T = lock.withLock(block)

    fun newSession(
        base: DocInput, left: DocInput, right: DocInput, policies: PolicySet
    ): SessionState = lock.withLock {
        val s = buildSession(newId("s"), 0L, base, left, right, policies, emptyList())
        state = s
        persist(s)
        s
    }

    /** 应用一批裁决（乐观版本控制）：版本不匹配则返回 null，由调用方返回 409）。 */
    fun applyResolutions(expectedVersion: Long, resolutions: List<Pair<String, Node>>): SessionState? =
        lock.withLock {
            val cur = state ?: return null
            if (cur.version != expectedVersion) return null
            val byPath = cur.result.conflicts.associateBy { it.path }
            val newDecisions = cur.decisions.toMutableList()
            for ((path, chosen) in resolutions) {
                val c = byPath[path]
                    ?: return null // 冲突已不存在（可能已被别人解决）
                newDecisions.add(
                    Decision(
                        id = newId("d"), path = path,
                        baseFp = fingerprint(c.base),
                        leftFp = fingerprint(c.left),
                        rightFp = fingerprint(c.right),
                        chosen = chosen, note = "manual"
                    )
                )
            }
            val s = buildSession(
                cur.id, cur.version + 1, cur.base, cur.left, cur.right,
                cur.policies, newDecisions
            )
            state = s
            persist(s)
            s
        }

    private fun buildSession(
        id: String, version: Long,
        base: DocInput, left: DocInput, right: DocInput,
        policies: PolicySet, decisions: List<Decision>
    ): SessionState {
        val bM = Parser.parse(base.text, base.id)
        val lM = Parser.parse(left.text, left.id)
        val rM = Parser.parse(right.text, right.id)
        val result = Merger(policies, decisions).merge(bM, lM, rM)
        return SessionState(id, version, base, left, right, bM, lM, rM, policies, decisions, result)
    }

    // ---------- 持久化 ----------

    private fun write(name: String, el: JsonObject) {
        Files.writeString(dir.resolve(name), json.encodeToString(JsonObject.serializer(), el))
    }

    private fun read(name: String): JsonObject? {
        val p = dir.resolve(name)
        if (!Files.exists(p)) return null
        return Json.parseToJsonElement(Files.readString(p)) as? JsonObject
    }

    private fun docToJson(d: DocInput) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(d.id),
            "format" to JsonPrimitive(d.format),
            "text" to JsonPrimitive(d.text)
        )
    )

    private fun docFromJson(o: JsonObject) = DocInput(
        (o["id"] as JsonPrimitive).content,
        (o["format"] as JsonPrimitive).content,
        (o["text"] as JsonPrimitive).content
    )

    private fun policiesToJson(p: PolicySet) = JsonObject(
        mapOf(
            "version" to JsonPrimitive(p.version),
            "policies" to JsonArray(p.policies.map {
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(it.pathPattern),
                        "strategy" to JsonPrimitive(it.strategy.name),
                        "idKey" to (it.idKey?.let { k -> JsonPrimitive(k) } ?: JsonPrimitive(""))
                    )
                )
            })
        )
    )

    private fun policiesFromJson(o: JsonObject) = PolicySet(
        version = (o["version"] as JsonPrimitive).content,
        policies = (o["policies"] as JsonArray).map {
            val po = it as JsonObject
            ArrayPolicy(
                (po["path"] as JsonPrimitive).content,
                ArrayStrategy.valueOf((po["strategy"] as JsonPrimitive).content),
                (po["idKey"] as? JsonPrimitive)?.content?.ifEmpty { null }
            )
        }
    )

    private fun decisionToJson(d: Decision) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(d.id),
            "path" to JsonPrimitive(d.path),
            "baseFp" to JsonPrimitive(d.baseFp),
            "leftFp" to JsonPrimitive(d.leftFp),
            "rightFp" to JsonPrimitive(d.rightFp),
            "chosen" to nodeToJson(d.chosen),
            "note" to JsonPrimitive(d.note),
            "ts" to JsonPrimitive(d.ts)
        )
    )

    private fun decisionFromJson(o: JsonObject) = Decision(
        id = (o["id"] as JsonPrimitive).content,
        path = (o["path"] as JsonPrimitive).content,
        baseFp = (o["baseFp"] as JsonPrimitive).content,
        leftFp = (o["leftFp"] as JsonPrimitive).content,
        rightFp = (o["rightFp"] as JsonPrimitive).content,
        chosen = nodeFromJson(o["chosen"] as JsonObject),
        note = (o["note"] as? JsonPrimitive)?.content ?: "",
        ts = (o["ts"] as? JsonPrimitive)?.longOrNull ?: 0L
    )

    private fun persist(s: SessionState) {
        write("inputs.json", JsonObject(mapOf(
            "session" to JsonPrimitive(s.id),
            "version" to JsonPrimitive(s.version),
            "base" to docToJson(s.base),
            "left" to docToJson(s.left),
            "right" to docToJson(s.right)
        )))
        write("model.json", JsonObject(mapOf(
            "base" to nodeToJson(s.baseModel),
            "left" to nodeToJson(s.leftModel),
            "right" to nodeToJson(s.rightModel)
        )))
        write("policies.json", policiesToJson(s.policies))
        write("decisions.json", JsonObject(mapOf(
            "history" to JsonArray(s.decisions.map { decisionToJson(it) })
        )))
        write("output.json", JsonObject(mapOf(
            "doc" to nodeToJson(s.result.root),
            "fingerprint" to JsonPrimitive(s.result.fingerprint),
            "policyVersion" to JsonPrimitive(s.policies.version),
            "provenance" to JsonArray(s.result.root.let { root ->
                provenanceMap(root).map { (p, refs) ->
                    JsonObject(mapOf(
                        "path" to JsonPrimitive(p),
                        "sources" to JsonArray(refs.map {
                            JsonObject(mapOf(
                                "doc" to JsonPrimitive(it.doc),
                                "path" to JsonPrimitive(it.path),
                                "note" to JsonPrimitive(it.note)
                            ))
                        })
                    ))
                }
            })
        )))
    }

    private fun load() {
        val inputs = read("inputs.json") ?: return
        val policies = read("policies.json") ?: return
        val decisionsEl = read("decisions.json")
        val decisions = ((decisionsEl?.get("history") as? JsonArray) ?: JsonArray(emptyList()))
            .map { decisionFromJson(it as JsonObject) }
        try {
            state = buildSession(
                (inputs["session"] as JsonPrimitive).content,
                (inputs["version"] as JsonPrimitive).longOrNull ?: 0L,
                docFromJson(inputs["base"] as JsonObject),
                docFromJson(inputs["left"] as JsonObject),
                docFromJson(inputs["right"] as JsonObject),
                policiesFromJson(policies),
                decisions
            )
        } catch (e: Exception) {
            // 持久化内容损坏时忽略，等待新会话
        }
    }
}
