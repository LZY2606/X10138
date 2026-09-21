package semmerge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File
import java.util.UUID

/** 已登记的输入文档（原始文本 + 解析模型 + 指纹）。 */
data class InputDoc(
    val id: String,
    val name: String,
    val format: String,
    val raw: String,
    val fingerprint: String
)

data class Resolution(val conflictId: String, val path: String, val choice: String, val customText: String? = null)

data class MergeSession(
    val id: String,
    val baseId: String,
    val oursId: String,
    val theirsId: String,
    val policyVersion: Int,
    var version: Int = 0,
    val decisions: MutableList<Decision> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)

class ConflictStaleException(val currentVersion: Int) :
    Exception("会话版本已过期，请刷新后重试")

class Store(val dir: File) {
    private val mapper = ObjectMapper().registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build()).enable(SerializationFeature.INDENT_OUTPUT)
    private val lock = Any()

    val inputs = linkedMapOf<String, InputDoc>()
    val models = linkedMapOf<String, Node?>()
    var policies = PolicySet()
    val sessions = linkedMapOf<String, MergeSession>()
    val decisionHistory = mutableListOf<Decision>()
    val outputs = linkedMapOf<String, String>()

    init {
        dir.mkdirs()
        listOf("inputs", "models", "sessions", "outputs").forEach { File(dir, it).mkdirs() }
        loadAll()
    }

    // ---------- 输入 ----------
    fun addInput(name: String, raw: String, format: String?): InputDoc {
        val fmt = format ?: Parser.detectFormat(raw).name.lowercase()
        val node = Parser.parse(raw, name, if (fmt == "json") Format.JSON else Format.YAML)
        val id = UUID.randomUUID().toString().substring(0, 8)
        val doc = InputDoc(id, name, fmt, raw, fingerprint(node))
        synchronized(lock) {
            inputs[id] = doc
            models[id] = node
            write(File(dir, "inputs/$id.json"), doc)
            write(File(dir, "models/$id.json"), mapOf("id" to id, "model" to nodeToJson(node)))
        }
        return doc
    }

    // ---------- 策略 ----------
    fun updatePolicies(p: PolicySet) {
        synchronized(lock) {
            policies = p
            write(File(dir, "policies.json"), p)
        }
    }

    // ---------- 会话 ----------
    fun createSession(baseId: String, oursId: String, theirsId: String): MergeSession {
        val s = MergeSession(
            UUID.randomUUID().toString().substring(0, 8),
            baseId, oursId, theirsId, policies.version
        )
        synchronized(lock) {
            sessions[s.id] = s
            persistSession(s)
        }
        return s
    }

    fun runMerge(s: MergeSession): MergeResult {
        val byPath = s.decisions.groupBy { it.path }
        return MergeEngine(policies, byPath).merge(
            models[s.baseId], models[s.oursId], models[s.theirsId]
        )
    }

    /** 批量提交裁决；expectedVersion 不匹配则拒绝（乐观并发）。 */
    fun submitResolutions(sessionId: String, expectedVersion: Int, resolutions: List<Resolution>): MergeResult {
        synchronized(lock) {
            val s = sessions[sessionId] ?: throw NoSuchElementException("会话不存在: $sessionId")
            if (s.version != expectedVersion) throw ConflictStaleException(s.version)
            // 先计算当前冲突以绑定指纹
            val current = runMerge(s)
            val byId = current.conflicts.associateBy { it.id }
            for (r in resolutions) {
                val c = byId[r.conflictId]
                    ?: throw IllegalArgumentException("冲突不存在或已解决: ${r.conflictId}")
                s.decisions.removeAll { it.path == c.path }
                s.decisions += Decision(
                    path = c.path,
                    baseFp = c.baseFp, oursFp = c.oursFp, theirsFp = c.theirsFp,
                    choice = r.choice, customText = r.customText
                )
            }
            s.version += 1
            decisionHistory.addAll(s.decisions.takeLast(resolutions.size))
            persistSession(s)
            write(File(dir, "decisions.json"), decisionHistory)
            return runMerge(s)
        }
    }

    // ---------- 导出 / 导入 ----------
    fun exportSession(sessionId: String, format: String): Pair<String, String> {
        val s = sessions[sessionId] ?: throw NoSuchElementException("会话不存在")
        val result = runMerge(s)
        require(result.conflicts.isEmpty()) { "尚有 ${result.conflicts.size} 个未解决冲突，不能导出" }
        val text = when (format) {
            "json" -> mapper.writeValueAsString(nodeToJson(result.merged))
            else -> YamlEmitter.emit(result.merged)
        }
        val fp = fingerprint(result.merged)
        val manifest = mapOf(
            "sessionId" to sessionId,
            "policyVersion" to s.policyVersion,
            "format" to format,
            "fingerprint" to fp,
            "provenance" to collectProvenance(result.merged),
            "text" to text
        )
        synchronized(lock) {
            outputs[sessionId] = fp
            write(File(dir, "outputs/$sessionId.json"), manifest)
        }
        return text to fp
    }

    /** 导入导出产物：重新解析文本并校验指纹与来源链一致。 */
    fun importExport(manifest: Map<*, *>): Map<String, Any?> {
        val text = manifest["text"] as String
        val expectFp = manifest["fingerprint"] as String
        val format = manifest["format"] as? String
        val node = Parser.parse(text, "imported", if (format == "json") Format.JSON else Format.YAML)
        val actualFp = fingerprint(node)
        val provOk = collectProvenance(node).size == (manifest["provenance"] as? List<*>)?.size
        return mapOf(
            "fingerprintMatch" to (actualFp == expectFp),
            "fingerprint" to actualFp,
            "provenanceEntries" to (manifest["provenance"] as? List<*>)?.size,
            "reimportedProvenanceEntries" to collectProvenance(node).size,
            "provenancePreserved" to provOk
        )
    }

    private fun collectProvenance(n: Node?, path: String = "$"): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        if (n == null) return out
        out += mapOf("path" to path, "prov" to n.prov.map { mapOf("side" to it.side, "path" to it.path) })
        when (n) {
            is Node.Obj -> n.entries.forEach { (k, v) -> out += collectProvenance(v, childPath(path, k)) }
            is Node.Arr -> n.items.forEachIndexed { i, v -> out += collectProvenance(v, indexPath(path, i)) }
            else -> {}
        }
        return out
    }

    // ---------- 持久化 ----------
    private fun persistSession(s: MergeSession) {
        write(File(dir, "sessions/${s.id}.json"), s)
    }

    private fun write(f: File, any: Any) {
        f.writeText(mapper.writeValueAsString(any))
    }

    private fun loadAll() {
        File(dir, "inputs").listFiles()?.forEach { f ->
            val doc = mapper.readValue(f.readText(), InputDoc::class.java)
            inputs[doc.id] = doc
        }
        File(dir, "models").listFiles()?.forEach { f ->
            val m = mapper.readValue(f.readText(), Map::class.java)
            val id = m["id"] as String
            models[id] = nodeFromJson(m["model"])
        }
        File(dir, "policies.json").takeIf { it.exists() }?.let {
            policies = mapper.readValue(it.readText(), PolicySet::class.java)
        }
        File(dir, "sessions").listFiles()?.forEach { f ->
            val s = mapper.readValue(f.readText(), MergeSession::class.java)
            sessions[s.id] = s
        }
        File(dir, "decisions.json").takeIf { it.exists() }?.let {
            decisionHistory.addAll(
                mapper.readValue(it.readText(), Array<Decision>::class.java).toList()
            )
        }
    }
}
