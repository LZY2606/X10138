package merger

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NotFoundException(msg: String) : Exception(msg)
class VersionConflictException(val currentVersion: Int) : Exception("stale version")

class MergeSession(
    val record: MergeSessionRecord,
    var items: List<MergeItem>,
    var conflicts: List<Conflict>,
    var suggestions: List<Decision>,
    var resultTree: Node?,
    var inputTrees: Triple<Node?, Node?, Node?>
) {
    val id get() = record.id
    val version get() = record.version
}

class MergeService(val store: Store) {
    private val lock = ReentrantLock()
    val documents = store.loadDocuments()
    var policySet: PolicySet = store.loadPolicies()
    val decisions = store.loadDecisions()
    private val mergeRecords = store.loadMerges()
    private val sessions = mutableMapOf<String, MergeSession>()
    val exports = store.loadExports()

    init { mergeRecords.forEach { rebuildSession(it) } }

    private fun doc(id: String): Document =
        documents.find { it.id == id } ?: throw NotFoundException("document $id")

    fun addDocument(name: String, format: String, text: String): Document {
        val fmt = DocFormat.valueOf(format.uppercase())
        val d = Document(UUID.randomUUID().toString().substring(0, 8), name, fmt.name, text, System.currentTimeMillis())
        parseDocument(fmt, text, d.id) // 校验可解析
        lock.withLock { documents += d; store.saveDocuments(documents) }
        return d
    }

    fun deleteDocument(id: String) = lock.withLock {
        documents.removeIf { it.id == id }; store.saveDocuments(documents)
    }

    fun putPolicies(policies: Map<String, ArrayPolicy>): PolicySet = lock.withLock {
        policySet = PolicySet(policies, policySet.version + 1)
        store.savePolicies(policySet)
        policySet
    }

    private fun parseDoc(id: String): Node? {
        val d = doc(id)
        return parseDocument(DocFormat.valueOf(d.format), d.text, d.id)
    }

    fun createMerge(baseId: String, oursId: String, theirsId: String): MergeSession = lock.withLock {
        val rec = MergeSessionRecord(
            UUID.randomUUID().toString().substring(0, 8), baseId, oursId, theirsId,
            policySet.version, policySet.policies, 0, mutableListOf(),
            System.currentTimeMillis(), System.currentTimeMillis()
        )
        mergeRecords += rec
        val session = rebuildSession(rec)
        store.saveMerges(mergeRecords)
        session
    }

    /** 依据记录重放合并：重算三向合并，自动套用指纹匹配的裁决，再应用人工裁决 */
    private fun rebuildSession(rec: MergeSessionRecord): MergeSession {
        val b = parseDoc(rec.baseId); val o = parseDoc(rec.oursId); val t = parseDoc(rec.theirsId)
        val result = Merger(rec.policies).merge(b, o, t)
        val items = result.items.toMutableList()
        var tree = result.tree
        for (c in result.conflicts) {
            decisions.find { it.matches(c) }?.let { d ->
                tree = applyAtPath(tree, c.path, resolutionNode(d.resolution, d.value, c), rec.policies)
                c.status = "auto-decision"; c.resolution = d.resolution
                items += MergeItem(c.path, "auto-decision:${d.id}", tree?.sourceChain ?: emptyList())
            }
        }
        val session = MergeSession(rec, items, result.conflicts, emptyList(), tree, Triple(b, o, t))
        for (r in rec.resolutions) {
            session.conflicts.find { it.id == r.conflictId }?.let { c ->
                session.resultTree = applyAtPath(session.resultTree, c.path, resolutionNode(r.resolution, r.value, c), rec.policies)
                c.status = "resolved"; c.resolution = r.resolution
            }
        }
        session.suggestions = decisions.filter { d ->
            session.conflicts.any { it.status == "open" && it.path == d.path && !d.matches(it) }
        }
        sessions[rec.id] = session
        return session
    }

    fun getMerge(id: String): MergeSession =
        sessions[id] ?: throw NotFoundException("merge $id")

    fun resolve(mergeId: String, expectedVersion: Int, resolutions: List<AppliedResolution>): MergeSession =
        lock.withLock {
            val session = getMerge(mergeId)
            if (session.version != expectedVersion) throw VersionConflictException(session.version)
            for (r in resolutions) {
                val c = session.conflicts.find { it.id == r.conflictId && it.status == "open" } ?: continue
                session.resultTree = applyAtPath(session.resultTree, c.path, resolutionNode(r.resolution, r.value, c), session.record.policies)
                c.status = "resolved"; c.resolution = r.resolution
                decisions += Decision(
                    UUID.randomUUID().toString().substring(0, 8), c.path,
                    c.baseFp, c.oursFp, c.theirsFp, r.resolution, r.value,
                    policySet.version, System.currentTimeMillis()
                )
                session.record.resolutions += r
            }
            session.record.version++
            session.record.updatedAt = System.currentTimeMillis()
            store.saveDecisions(decisions)
            store.saveMerges(mergeRecords)
            session
        }

    private fun resolutionNode(resolution: String, value: String?, c: Conflict): Node? = when (resolution) {
        "ours" -> c.ours?.copyDeep()
        "theirs" -> c.theirs?.copyDeep()
        "base" -> c.base?.copyDeep()
        "null" -> NullNode()
        "delete" -> null
        "value" -> value?.let { nodeFromJsonLiteral(it) }
        else -> throw IllegalArgumentException("unknown resolution $resolution")
    }

    /** 在结果树 path 处替换/删除节点；path 为空串表示根；数组段支持按策略 id 定位 */
    fun applyAtPath(root: Node?, path: String, replacement: Node?, policies: Map<String, ArrayPolicy> = emptyMap()): Node? {
        if (path.isEmpty()) return replacement
        val segs = path.removePrefix("/").split("/").map { unescapeKey(it) }
        var cur: Node = root ?: throw NotFoundException("empty tree")
        var curPath = ""
        for (i in 0 until segs.size - 1) {
            val seg = segs[i]
            cur = when (cur) {
                is MapNode -> cur.entries[seg] ?: throw NotFoundException(path)
                is SeqNode -> cur.items[seqIndex(cur, seg, curPath, policies)]
                else -> throw NotFoundException(path)
            }
            curPath += "/" + escapeKey(seg)
        }
        val last = segs.last()
        when (cur) {
            is MapNode -> if (replacement == null) cur.entries.remove(last) else cur.entries[last] = replacement
            is SeqNode -> {
                val idx = seqIndex(cur, last, curPath, policies)
                if (replacement == null) cur.items.removeAt(idx) else cur.items[idx] = replacement
            }
            else -> throw NotFoundException(path)
        }
        return root
    }

    private fun seqIndex(s: SeqNode, seg: String, path: String, policies: Map<String, ArrayPolicy>): Int {
        seg.toIntOrNull()?.let { return it }
        val idKey = policies[path]?.idKey ?: "id"
        val idx = s.items.indexOfFirst {
            ((it as? MapNode)?.entries?.get(idKey) as? ScalarNode)?.value == seg
        }
        if (idx < 0) throw NotFoundException("$path/$seg")
        return idx
    }

    fun export(mergeId: String, format: String): ExportRecord = lock.withLock {
        val session = getMerge(mergeId)
        if (session.conflicts.any { it.status == "open" })
            throw IllegalStateException("unresolved conflicts remain")
        val plain = session.resultTree?.let { nodeToPlain(it) }
        val text = when (format.lowercase()) {
            "json" -> store.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plain)
            else -> {
                val opts = DumperOptions().apply {
                    defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                    isPrettyFlow = true
                }
                Yaml(opts).dump(plain)
            }
        }
        val rec = ExportRecord(
            UUID.randomUUID().toString().substring(0, 8), mergeId, format.lowercase(),
            text, fingerprint(session.resultTree), System.currentTimeMillis()
        )
        exports += rec
        store.saveExports(exports)
        rec
    }

    fun exportBundle(): Bundle = lock.withLock {
        Bundle(documents.toList(), policySet, decisions.toList(), mergeRecords.toList(), exports.toList())
    }

    fun importBundle(bundle: Bundle) = lock.withLock {
        documents.clear(); documents += bundle.documents
        policySet = bundle.policies
        decisions.clear(); decisions += bundle.decisions
        mergeRecords.clear(); mergeRecords += bundle.merges
        exports.clear(); exports += bundle.exports
        sessions.clear()
        store.saveBundle(bundle)
        mergeRecords.forEach { rebuildSession(it) }
    }

    fun listMerges(): List<MergeSessionRecord> = mergeRecords.toList()
}
