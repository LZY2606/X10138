package semmerge.merge

import semmerge.iof.Fingerprint
import semmerge.model.Path
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.ScalarKind
import semmerge.model.SScalar
import semmerge.model.structuralEqual

/**
 * List merge. A strategy must be registered for the path — we never guess.
 *  - REPLACE: a classic whole-value three-way pick (same scalar rules)
 *  - ID: elements keyed by a stable id field, reordered independently
 *  - SEQ: ordered sequence, diff3-style hunk alignment
 */
internal fun Merger.mergeList(path: Path, b: SList, a: SList?, c: SList?): MNode {
    val aList = a ?: SList()
    val cList = c ?: SList()
    val unchanged = structuralEqual(b, aList) && structuralEqual(b, cList)

    val entry = policy.at(path)
    if (entry == null) {
        return if (unchanged) {
            replaceUnchanged(path, b, aList, cList, ListStrategy.REPLACE)
        } else {
            missingPolicyNode(path, b, aList, cList)
        }
    }

    return when (entry.strategy) {
        ListStrategy.REPLACE -> mergeReplace(path, b, aList, cList, ListStrategy.REPLACE)
        ListStrategy.ID -> mergeById(path, b, aList, cList, entry.idField)
        ListStrategy.SEQ -> mergeSequence(path, b, aList, cList)
    }
}

private fun Merger.listSteps(b: SList, a: SList, c: SList): List<ProvStep> = listOf(
    ProvStep(Side.BASE, "list", Fingerprint.of(b)),
    ProvStep(Side.BRANCH_A, "list", Fingerprint.of(a)),
    ProvStep(Side.BRANCH_B, "list", Fingerprint.of(c)),
)

internal fun Merger.replaceUnchanged(path: Path, b: SList, a: SList, c: SList, strategy: ListStrategy): MList {
    val items = b.items.mapIndexed { i, n ->
        MIdItem(i.toString(),
            MValue(path.index(i), n, Fingerprint.of(n), Fingerprint.of(n), Fingerprint.of(n),
                MergeStatus.UNCHANGED,
                Provenance(listOf(
                    ProvStep(Side.BASE, "value", Fingerprint.of(n)),
                    ProvStep(Side.BRANCH_A, "value", Fingerprint.of(n)),
                    ProvStep(Side.BRANCH_B, "value", Fingerprint.of(n)),
                ), "三方一致")),
            ItemOrder.FROM_BASE)
    }
    return MList(path, strategy, items,
        status = MergeStatus.UNCHANGED,
        provenance = Provenance(listSteps(b, a, c), "数组未变化（${b.items.size} 项）"))
}

private fun Merger.missingPolicyNode(path: Path, b: SList, a: SList, c: SList): MList {
    val msg = "路径 ${path.render()} 是数组且发生分歧，但未登记合并策略；系统不会猜测"
    val conflict = Conflict(conflictKey(path, ConflictKind.POLICY_MISSING), path, ConflictKind.POLICY_MISSING, msg)
    recordConflict(conflict)
    val match = decide(conflict)
    val suggestion = match?.let(::suggestionOf)

    val items = b.items.mapIndexed { i, n ->
        MIdItem(i.toString(),
            MValue(path.index(i), n, Fingerprint.of(n),
                Fingerprint.of(a.items.getOrNull(i)), Fingerprint.of(c.items.getOrNull(i)),
                MergeStatus.NEEDS_POLICY,
                Provenance(emptyList(), "等待登记策略后合并")),
            ItemOrder.FROM_BASE)
    }
    return MList(path, null, items, status = MergeStatus.NEEDS_POLICY,
        provenance = Provenance(listSteps(b, a, c), msg),
        conflict = conflict, suggestion = suggestion)
}

private fun Merger.mergeReplace(path: Path, b: SList, a: SList, c: SList, strategy: ListStrategy): MNode {
    val aEqB = structuralEqual(b, a)
    val cEqB = structuralEqual(b, c)
    val aEqC = structuralEqual(a, c)
    return when {
        aEqB && cEqB -> replaceUnchanged(path, b, a, c, strategy)
        aEqB && !cEqB -> {
            val child = listAsMerged(path, c, MergeStatus.AUTO_B)
            MList(path, strategy, child, status = MergeStatus.AUTO_B,
                provenance = Provenance(listSteps(b, a, c), "替换策略：采纳分支 B 的整段数组"))
        }
        !aEqB && cEqB -> {
            val child = listAsMerged(path, a, MergeStatus.AUTO_A)
            MList(path, strategy, child, status = MergeStatus.AUTO_A,
                provenance = Provenance(listSteps(b, a, c), "替换策略：采纳分支 A 的整段数组"))
        }
        aEqC -> {
            val child = listAsMerged(path, a, MergeStatus.AUTO_BOTH)
            MList(path, strategy, child, status = MergeStatus.AUTO_BOTH,
                provenance = Provenance(listSteps(b, a, c), "替换策略：两侧数组相同"))
        }
        else -> replaceConflict(path, b, a, c)
    }
}

private fun Merger.listAsMerged(path: Path, list: SList, status: MergeStatus): List<MIdItem> =
    list.items.mapIndexed { i, n ->
        MIdItem(i.toString(),
            MValue(path.index(i), n, "", Fingerprint.of(list), Fingerprint.of(list),
                status, Provenance(emptyList(), statusLabel(status))),
            ItemOrder.FROM_BASE)
    }

internal fun statusLabel(s: MergeStatus): String = when (s) {
    MergeStatus.AUTO_A -> "来自分支 A"
    MergeStatus.AUTO_B -> "来自分支 B"
    MergeStatus.AUTO_BOTH -> "两侧相同"
    MergeStatus.RESOLVED -> "人工裁决"
    else -> ""
}

private fun Merger.replaceConflict(path: Path, b: SList, a: SList, c: SList): MNode {
    val conflict = Conflict(conflictKey(path, ConflictKind.VALUE), path, ConflictKind.VALUE,
        "替换策略下两个分支给出了不同的整段数组")
    recordConflict(conflict)
    val match = decide(conflict)
    if (match?.applicable == true) {
        val resolved = applyResolution(path, match.record.resolution, b, a, c,
            Fingerprint.of(b), Fingerprint.of(a), Fingerprint.of(c), conflict, null)
        return wrapResolvedList(path, resolved, ListStrategy.REPLACE)
    }
    val items = b.items.mapIndexed { i, n ->
        MIdItem(i.toString(),
            MValue(path.index(i), n, Fingerprint.of(n),
                Fingerprint.of(a.items.getOrNull(i)), Fingerprint.of(c.items.getOrNull(i)),
                MergeStatus.CONFLICT, Provenance(emptyList(), "待裁决")),
            ItemOrder.FROM_BASE)
    }
    return MList(path, ListStrategy.REPLACE, items, status = MergeStatus.CONFLICT,
        provenance = Provenance(listSteps(b, a, c), "替换策略冲突：A 有 ${a.items.size} 项，B 有 ${c.items.size} 项"),
        conflict = conflict, suggestion = match?.let(::suggestionOf))
}

private fun wrapResolvedList(path: Path, resolved: MNode, strategy: ListStrategy): MNode = resolved
