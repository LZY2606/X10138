package semmerge.merge

import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.ScalarKind
import semmerge.model.Path

/** Three-way map merge: keys by name, union order, per-key recursion. */
internal fun Merger.mergeMap(path: Path, b: SMap?, a: SMap?, c: SMap?): MNode {
    val bMap = b ?: SMap()
    val aMap = a ?: SMap()
    val cMap = c ?: SMap()

    val orderedKeys = linkedSetOf<String>()
    orderedKeys.addAll(bMap.keys)
    orderedKeys.addAll(aMap.keys)
    orderedKeys.addAll(cMap.keys)

    val children = orderedKeys.map { key ->
        MEntry(key, mergeNode(path.child(key), bMap.get(key), aMap.get(key), cMap.get(key)))
    }

    val childStatuses = children.map { it.node.status }
    val childConflicts = children.flatMap { nodeConflicts(it.node) }

    val status = when {
        childConflicts.isNotEmpty() ->
            if (childStatuses.any { it == MergeStatus.RESOLVED || it == MergeStatus.SUGGESTED })
                MergeStatus.RESOLVED else MergeStatus.CONFLICT
        childStatuses.any { it == MergeStatus.AUTO_A || it == MergeStatus.AUTO_B ||
            it == MergeStatus.AUTO_BOTH || it == MergeStatus.DELETED_A || it == MergeStatus.DELETED_B } ->
            aggregateAuto(childStatuses)
        else -> MergeStatus.UNCHANGED
    }

    val steps = mapSteps(b, a, c)
    val explanation = mapExplanation(bMap, aMap, cMap, childStatuses)
    return MMap(path, children, status, Provenance(steps, explanation))
}

private fun Merger.mapSteps(b: SMap?, a: SMap?, c: SMap?): List<ProvStep> = listOf(
    ProvStep(Side.BASE, "map", semmerge.iof.Fingerprint.of(b)),
    ProvStep(Side.BRANCH_A, "map", semmerge.iof.Fingerprint.of(a)),
    ProvStep(Side.BRANCH_B, "map", semmerge.iof.Fingerprint.of(c)),
)

private fun mapExplanation(b: SMap, a: SMap, c: SMap, statuses: List<MergeStatus>): String {
    if (a.size == b.size && c.size == b.size && statuses.all { it == MergeStatus.UNCHANGED }) {
        return "对象未变化（${b.size} 个字段）"
    }
    val changesA = a.keys.count { b.get(it) != a.get(it) }
    val changesC = c.keys.count { b.get(it) != c.get(it) }
    return "按字段名合并：A 侧约 $changesA 处差异，B 侧约 $changesC 处差异"
}

internal fun aggregateAuto(statuses: List<MergeStatus>): MergeStatus {
    val hasA = statuses.any { it == MergeStatus.AUTO_A || it == MergeStatus.DELETED_A }
    val hasB = statuses.any { it == MergeStatus.AUTO_B || it == MergeStatus.DELETED_B }
    val hasBoth = statuses.any { it == MergeStatus.AUTO_BOTH }
    return when {
        hasA && hasB -> MergeStatus.AUTO_BOTH
        hasBoth && hasA -> MergeStatus.AUTO_A
        hasBoth && hasB -> MergeStatus.AUTO_B
        hasA -> MergeStatus.AUTO_A
        hasB -> MergeStatus.AUTO_B
        else -> MergeStatus.AUTO_BOTH
    }
}

/** All conflicts inside a subtree. */
internal fun nodeConflicts(node: MNode): List<Conflict> {
    val out = mutableListOf<Conflict>()
    fun visit(n: MNode) {
        n.conflict?.let(out::add)
        when (n) {
            is MMap -> n.entries.forEach { visit(it.node) }
            is MList -> {
                n.orderConflict?.let(out::add)
                n.items.forEach { visit(it.node) }
                n.hunks.forEach { h -> h.merged.forEach { visit(it.node) } }
            }
            else -> Unit
        }
    }
    visit(node)
    return out
}

internal fun nullNode(): SNode =
    semmerge.model.SScalar(ScalarKind.NULL, "null", semmerge.model.ScalarStyle.PLAIN)
