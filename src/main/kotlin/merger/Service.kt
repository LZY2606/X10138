package merger

import com.fasterxml.jackson.databind.JsonNode
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.math.BigDecimal

class OptimisticLockException(val currentVersion: Long) : RuntimeException("版本过期：当前版本 $currentVersion")

data class MergeSession(
    val id: String,
    val baseId: String,
    val leftId: String,
    val rightId: String,
    var policyVersion: Long,
    var version: Long,
    var result: Node?,
    var auto: List<AutoItem>,
    var conflicts: List<Conflict>,
    val createdAt: Long,
)

data class Decision(val conflictId: String, val choice: String)

class MergeService(private val store: Store) {

    @Synchronized
    fun addDocument(name: String, text: String): Map<String, Any?> {
        val (node, format) = Parse.parse(text, name)
        val doc = StoredDoc(
            id = "doc-${store.docs.size + 1}-${System.currentTimeMillis()}",
            name = name, format = format, raw = text, model = node,
            createdAt = System.currentTimeMillis(),
        )
        store.docs += doc
        store.saveDoc(doc)
        return docSummary(doc)
    }

    fun docSummary(doc: StoredDoc): Map<String, Any?> = mapOf(
        "id" to doc.id, "name" to doc.name, "format" to doc.format,
        "fingerprint" to Canon.fingerprint(doc.model), "createdAt" to doc.createdAt,
    )

    @Synchronized
    fun listDocuments(): List<Map<String, Any?>> = store.docs.map { docSummary(it) }

    @Synchronized
    fun setPolicy(path: String, strategy: String, idKey: String?): Map<String, Any?> {
        store.policies[path] = ArrayPolicy(path, ArrayStrategy.valueOf(strategy), idKey?.ifBlank { null })
        store.policyVersion += 1
        store.savePolicies()
        return policyState()
    }

    @Synchronized
    fun removePolicy(path: String): Map<String, Any?> {
        store.policies.remove(path)
        store.policyVersion += 1
        store.savePolicies()
        return policyState()
    }

    fun policyState(): Map<String, Any?> = mapOf(
        "version" to store.policyVersion,
        "policies" to store.policies.values.map {
            mapOf("path" to it.path, "strategy" to it.strategy.name, "idKey" to it.idKey)
        },
    )

    private fun docById(id: String): StoredDoc =
        store.docs.find { it.id == id } ?: throw NoSuchElementException("文档不存在: $id")

    @Synchronized
    fun createMerge(baseId: String, leftId: String, rightId: String): MergeSession {
        val session = MergeSession(
            id = "m-${store.merges.size + 1}-${System.currentTimeMillis()}",
            baseId = baseId, leftId = leftId, rightId = rightId,
            policyVersion = store.policyVersion, version = 0,
            result = null, auto = emptyList(), conflicts = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        recompute(session)
        store.merges[session.id] = session
        store.saveMerge(session)
        return session
    }

    private fun recompute(session: MergeSession) {
        val outcome = Merger(store.policies, store.resolutions).merge(
            docById(session.baseId).model,
            docById(session.leftId).model,
            docById(session.rightId).model,
        )
        session.result = outcome.result
        session.auto = outcome.auto
        session.conflicts = outcome.conflicts
        session.policyVersion = store.policyVersion
    }

    @Synchronized
    fun getMerge(id: String): MergeSession =
        store.merges[id] ?: throw NoSuchElementException("合并会话不存在: $id")

    /** 批量提交裁决：乐观版本校验，过期提交整体拒绝，不会覆盖别人已解决的项。 */
    @Synchronized
    fun resolve(mergeId: String, expectedVersion: Long, decisions: List<Decision>): MergeSession {
        val session = getMerge(mergeId)
        if (session.version != expectedVersion) throw OptimisticLockException(session.version)
        for (d in decisions) {
            val conflict = session.conflicts.find { it.id == d.conflictId }
                ?: throw IllegalArgumentException("冲突不存在或已解决: ${d.conflictId}")
            store.resolutions += Resolution(conflict.path, conflict.fingerprint, d.choice)
        }
        store.saveResolutions()
        recompute(session)
        session.version += 1
        store.saveMerge(session)
        return session
    }

    private fun toPlain(node: Node?): Any? = when (node) {
        null -> null
        is ScalarNode -> when (val v = node.value) {
            is BigDecimal -> if (v.stripTrailingZeros().scale() <= 0) v.toBigInteger() else v.toDouble()
            else -> v
        }
        is ArrNode -> node.items.map { toPlain(it) }
        is ObjNode -> node.entries.mapValues { toPlain(it.value) }.toMap(LinkedHashMap())
    }

    @Synchronized
    fun exportConfig(mergeId: String, format: String): String {
        val session = getMerge(mergeId)
        val plain = toPlain(session.result)
        return when (format) {
            "json" -> NodeJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plain)
            else -> {
                val opts = DumperOptions()
                opts.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                Yaml(opts).dump(plain)
            }
        }
    }

    /** 导出完整包：树（含来源链）+ 策略版本 + 结果指纹，可再导入且指纹一致。 */
    @Synchronized
    fun exportBundle(mergeId: String): String {
        val session = getMerge(mergeId)
        val j = NodeJson.mapper.createObjectNode()
        j.put("format", "semantic-merge-export")
        j.put("bundleVersion", 1)
        j.put("mergeId", session.id)
        j.put("policyVersion", session.policyVersion)
        j.put("fingerprint", Canon.fingerprint(session.result))
        j.set<JsonNode>("tree", NodeJson.toJson(session.result))
        val text = NodeJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(j)
        store.saveExport(session.id, text)
        return text
    }

    @Synchronized
    fun importBundle(text: String): Map<String, Any?> {
        val j = NodeJson.mapper.readTree(text)
        require(j.get("format")?.asText() == "semantic-merge-export") { "不是有效的导出包" }
        val tree = NodeJson.fromJson(j.get("tree"))
        val fingerprint = Canon.fingerprint(tree)
        val expected = j.get("fingerprint")?.asText()
        val doc = StoredDoc(
            id = "doc-${store.docs.size + 1}-${System.currentTimeMillis()}",
            name = "import:${j.get("mergeId")?.asText() ?: "bundle"}",
            format = "bundle", raw = text, model = tree ?: ScalarNode(null),
            createdAt = System.currentTimeMillis(),
        )
        store.docs += doc
        store.saveDoc(doc)
        return mapOf(
            "fingerprint" to fingerprint,
            "expectedFingerprint" to expected,
            "matches" to (fingerprint == expected),
            "documentId" to doc.id,
        )
    }

    @Synchronized
    fun resolutions(): List<Map<String, Any?>> = store.resolutions.map {
        mapOf("path" to it.path, "fingerprint" to it.fingerprint, "choice" to it.choice, "decidedAt" to it.decidedAt)
    }

    @Synchronized
    fun listMerges(): List<Map<String, Any?>> = store.merges.values.map {
        mapOf(
            "id" to it.id, "baseId" to it.baseId, "leftId" to it.leftId, "rightId" to it.rightId,
            "version" to it.version, "policyVersion" to it.policyVersion,
            "conflicts" to it.conflicts.size, "auto" to it.auto.size, "createdAt" to it.createdAt,
        )
    }

    fun sessionJson(s: MergeSession): JsonNode = store.sessionToPublicJson(s)
}
