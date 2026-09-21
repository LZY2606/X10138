package merger

enum class ArrayStrategy { REPLACE, BY_ID, ORDERED }

data class ArrayPolicy(val strategy: ArrayStrategy, val idKey: String = "id")

data class PolicySet(val policies: Map<String, ArrayPolicy> = emptyMap(), val version: Int = 0)

data class MergeItem(val path: String, val action: String, val sourceChain: List<SourceRef>)

data class Conflict(
    val id: String,
    val path: String,
    val reason: String,
    val baseFp: String,
    val oursFp: String,
    val theirsFp: String,
    val base: Node?,
    val ours: Node?,
    val theirs: Node?,
    var status: String = "open", // open | resolved | auto-decision
    var resolution: String? = null
)

data class MergeResult(val tree: Node?, val items: List<MergeItem>, val conflicts: List<Conflict>)

class Merger(private val policies: Map<String, ArrayPolicy>) {
    private val items = mutableListOf<MergeItem>()
    private val conflicts = mutableListOf<Conflict>()
    private var conflictSeq = 0

    fun merge(base: Node?, ours: Node?, theirs: Node?): MergeResult =
        MergeResult(mergeNode(base, ours, theirs, ""), items.toList(), conflicts.toList())

    private fun chainOf(vararg nodes: Node?): List<SourceRef> =
        nodes.filterNotNull().mapNotNull { it.source }

    private fun adopt(node: Node?, vararg contributors: Node?): Node? {
        node ?: return null
        node.sourceChain = chainOf(*contributors)
        return node
    }

    private fun conflict(path: String, reason: String, b: Node?, o: Node?, t: Node?) {
        conflicts += Conflict(
            id = "c${++conflictSeq}", path = path, reason = reason,
            baseFp = fingerprint(b), oursFp = fingerprint(o), theirsFp = fingerprint(t),
            base = b?.copyDeep(), ours = o?.copyDeep(), theirs = t?.copyDeep()
        )
    }

    private fun mergeNode(b: Node?, o: Node?, t: Node?, path: String): Node? {
        // 两边一致（含都删除、都为 null）
        if (structEq(o, t)) {
            if (!structEq(b, o) && o != null)
                items += MergeItem(path, "both-changed-same", chainOf(b, o, t))
            return adopt(o?.copyDeep(), b, o)
        }
        // 仅 theirs 变更
        if (structEq(b, o)) {
            items += MergeItem(path, when {
                t == null -> "deleted-by-theirs"
                o == null -> "added-by-theirs"
                else -> "changed-by-theirs"
            }, chainOf(b, t))
            return adopt(t?.copyDeep(), b, t)
        }
        // 仅 ours 变更
        if (structEq(b, t)) {
            items += MergeItem(path, when {
                o == null -> "deleted-by-ours"
                t == null -> "added-by-ours"
                else -> "changed-by-ours"
            }, chainOf(b, o))
            return adopt(o?.copyDeep(), b, o)
        }
        // 两边都改：容器下钻，否则冲突
        if (b is MapNode && o is MapNode && t is MapNode) return mergeMaps(b, o, t, path)
        if (b is SeqNode && o is SeqNode && t is SeqNode) return mergeSeqs(b, o, t, path)
        val reason = when {
            o == null || t == null -> "delete-vs-modify"
            b == null -> "added-both-different"
            else -> "modified-both-different"
        }
        conflict(path, reason, b, o, t)
        return adopt(o?.copyDeep(), b, o, t) // 暂定值，等待人工裁决
    }

    private fun mergeMaps(b: MapNode, o: MapNode, t: MapNode, path: String): MapNode {
        val result = MapNode()
        result.source = b.source
        result.sourceChain = chainOf(b)
        val keys = LinkedHashSet<String>()
        keys += b.entries.keys; keys += o.entries.keys; keys += t.entries.keys
        for (k in keys) {
            val child = mergeNode(b.entries[k], o.entries[k], t.entries[k], childPath(path, k))
            if (child != null) result.entries[k] = child
        }
        return result
    }

    private fun mergeSeqs(b: SeqNode, o: SeqNode, t: SeqNode, path: String): Node {
        val policy = policies[path]
        if (policy == null) { // 未登记策略时不得猜
            conflict(path, "array-no-policy", b, o, t)
            return adopt(o.copyDeep(), b, o, t)!!
        }
        return when (policy.strategy) {
            ArrayStrategy.REPLACE -> {
                conflict(path, "array-replace-both-changed", b, o, t)
                adopt(o.copyDeep(), b, o, t)!!
            }
            ArrayStrategy.ORDERED -> mergeOrdered(b, o, t, path)
            ArrayStrategy.BY_ID -> mergeById(b, o, t, path, policy.idKey)
        }
    }

    private fun mergeOrdered(b: SeqNode, o: SeqNode, t: SeqNode, path: String): Node {
        if (b.items.size != o.items.size || b.items.size != t.items.size) {
            conflict(path, "array-ordered-length-changed", b, o, t)
            return adopt(o.copyDeep(), b, o, t)!!
        }
        val result = SeqNode()
        result.source = b.source
        result.sourceChain = chainOf(b)
        for (i in b.items.indices) {
            mergeNode(b.items[i], o.items[i], t.items[i], "$path/$i")?.let { result.items.add(it) }
        }
        items += MergeItem(path, "array-merged-ordered", chainOf(b, o, t))
        return result
    }

    private fun idOf(n: Node?, idKey: String): String? =
        ((n as? MapNode)?.entries?.get(idKey) as? ScalarNode)?.value

    private fun mergeById(b: SeqNode, o: SeqNode, t: SeqNode, path: String, idKey: String): Node {
        fun index(s: SeqNode): Pair<LinkedHashMap<String, Node>, String?> {
            val m = LinkedHashMap<String, Node>()
            for (item in s.items) {
                val id = idOf(item, idKey) ?: return m to "missing-id"
                if (m.containsKey(id)) return m to "duplicate-id:$id"
                m[id] = item
            }
            return m to null
        }
        val (bi, be) = index(b); val (oi, oe) = index(o); val (ti, te) = index(t)
        listOfNotNull(be, oe, te).firstOrNull()?.let { err ->
            conflict(path, "array-by-id-$err", b, o, t)
            return adopt(o.copyDeep(), b, o, t)!!
        }
        val bOrder = bi.keys.toList(); val oOrder = oi.keys.toList(); val tOrder = ti.keys.toList()
        val order: List<String> = when {
            oOrder == tOrder -> oOrder
            bOrder == oOrder -> tOrder
            bOrder == tOrder -> oOrder
            else -> {
                conflict(path, "array-order-conflict", b, o, t)
                oOrder
            }
        }
        val result = SeqNode()
        result.source = b.source
        result.sourceChain = chainOf(b)
        for (id in order) {
            mergeNode(bi[id], oi[id], ti[id], childPath(path, id))?.let { result.items.add(it) }
        }
        items += MergeItem(path, "array-merged-by-id", chainOf(b, o, t))
        return result
    }
}
