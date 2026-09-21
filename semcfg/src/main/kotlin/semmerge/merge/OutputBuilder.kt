package semmerge.merge

import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode

/**
 * Materializes the merge result. Deleted keys are *omitted* (a delete action
 * is not an explicit null). The result is a brand-new deep copy — it never
 * aliases parsed input nodes, so consumers cannot mutate shared references.
 */
object OutputBuilder {
    class UnresolvedConflictException(val conflicts: List<Conflict>) :
        RuntimeException("尚有 ${conflicts.size} 个未解决冲突，无法导出")

    fun build(result: MergeResult, allowUnresolved: Boolean = false): SNode {
        val unresolved = result.conflicts.filter { conf ->
            !result.appliedDecisions.contains(conf.key)
        }
        if (unresolved.isNotEmpty() && !allowUnresolved) {
            throw UnresolvedConflictException(unresolved)
        }
        return buildNode(result.tree)
    }

    private fun buildNode(node: MNode): SNode = when (node) {
        is MValue -> node.value.deepCopy()
        is MDeleted -> SMap()
        is MMap -> SMap(node.entries.mapNotNull { entry ->
            when (val child = entry.node) {
                is MDeleted -> null
                else -> entry.key to buildNode(child)
            }
        })
        is MList -> buildList(node)
    }

    private fun buildList(list: MList): SNode {
        if (list.strategy == ListStrategy.SEQ) {
            val elems = list.hunks.flatMap { h ->
                h.merged.filterNot { it.node is MDeleted }.map { buildNode(it.node) }
            }
            return SList(elems)
        }
        return SList(list.items.mapNotNull { item ->
            val n = item.node
            if (n is MDeleted) null else buildNode(n)
        })
    }
}
