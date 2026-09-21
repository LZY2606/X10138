package semmerge.merge

import semmerge.iof.Fingerprint
import semmerge.model.Path
import semmerge.model.SList
import semmerge.model.SNode
import semmerge.model.structuralEqual

/** Ordered-sequence three-way merge using LCS matching against the base. */
internal fun Merger.mergeSequence(path: Path, b: SList, a: SList, c: SList): MNode {
    if (structuralEqual(b, a) && structuralEqual(b, c)) {
        return replaceUnchanged(path, b, a, c, ListStrategy.SEQ)
    }

    val aPairs = matchIndices(b.items, a.items)
    val cPairs = matchIndices(b.items, c.items)
    val aMatched = aPairs.filter { it.second >= 0 }.associate { it.second to it.first }
    val cMatched = cPairs.filter { it.second >= 0 }.associate { it.second to it.first }

    // Sync points: base indices matched by both sides.
    val syncBase = (aMatched.keys intersect cMatched.keys).sorted()

    data class Cursor(var bI: Int, var aI: Int, var cI: Int)
    val cur = Cursor(0, 0, 0)
    val hunks = mutableListOf<SeqHunk>()
    var hunkStart = 0

    fun emitUpTo(baseIndexExclusive: Int) {
        val bEnd = baseIndexExclusive
        val baseSlice = b.items.subList(cur.bI, bEnd)

        val aEnd = if (bEnd >= b.items.size) a.items.size else aMatched[bEnd] ?: a.items.size
        val cEnd = if (bEnd >= b.items.size) c.items.size else cMatched[bEnd] ?: c.items.size
        val aSlice = a.items.subList(cur.aI, aEnd)
        val cSlice = c.items.subList(cur.cI, cEnd)

        if (baseSlice.isNotEmpty() || aSlice.isNotEmpty() || cSlice.isNotEmpty()) {
            val auto = structuralEqual(toList(baseSlice), toList(aSlice)) ||
                structuralEqual(toList(baseSlice), toList(cSlice)) ||
                structuralEqual(toList(aSlice), toList(cSlice))
            val merged = if (structuralEqual(toList(aSlice), toList(cSlice))) {
                aSlice.mapIndexed { i, n ->
                    MSeqElem(elemValue(path, hunkStart + i, n, MergeStatus.AUTO_BOTH), Side.BRANCH_A)
                }
            } else if (structuralEqual(toList(baseSlice), toList(aSlice))) {
                cSlice.mapIndexed { i, n ->
                    MSeqElem(elemValue(path, hunkStart + i, n, MergeStatus.AUTO_B), Side.BRANCH_B)
                }
            } else if (structuralEqual(toList(baseSlice), toList(cSlice))) {
                aSlice.mapIndexed { i, n ->
                    MSeqElem(elemValue(path, hunkStart + i, n, MergeStatus.AUTO_A), Side.BRANCH_A)
                }
            } else {
                val conflict = Conflict(
                    conflictKey(path, ConflictKind.SEQ, hunkStart.toString()),
                    path, ConflictKind.SEQ,
                    "有序序列在第 ${hunkStart + 1} 个元素附近发生分歧：A 给出 ${aSlice.size} 项，B 给出 ${cSlice.size} 项",
                    index = hunkStart,
                )
                recordConflict(conflict)
                val match = decide(conflict)
                if (match?.applicable == true) {
                    val picked = seqResolution(match.record.resolution, aSlice, cSlice, baseSlice)
                    picked.mapIndexed { i, n ->
                        MSeqElem(elemValue(path, hunkStart + i, n, MergeStatus.RESOLVED), Side.DECISION)
                    }
                } else {
                    // Keep A visible, flagged conflict.
                    aSlice.mapIndexed { i, n ->
                        MSeqElem(elemValue(path, hunkStart + i, n, MergeStatus.CONFLICT, conflict,
                            match?.let(::suggestionOf)), Side.BRANCH_A)
                    }
                }
            }
            hunks += SeqHunk(hunkStart, baseSlice, aSlice, cSlice, auto, merged)
            hunkStart += merged.size
        }

        cur.bI = bEnd
        cur.aI = aEnd
        cur.cI = cEnd
    }

    for (sb in syncBase) {
        emitUpTo(sb)
        // Emit the common sync element itself.
        val node = b.items[sb]
        val aIdx = aMatched[sb]!!
        val cIdx = cMatched[sb]!!
        val aNode = a.items[aIdx]
        val cNode = c.items[cIdx]
        val mergedNode = if (structuralEqual(aNode, cNode)) {
            elemValue(path, hunkStart, aNode, MergeStatus.AUTO_BOTH)
        } else mergeNode(path.index(hunkStart), node, aNode, cNode)
        hunks += SeqHunk(hunkStart, listOf(node), listOf(aNode), listOf(cNode), true,
            listOf(MSeqElem(mergedNode, Side.MERGED)))
        hunkStart += 1
        cur.bI = sb + 1
        cur.aI = aIdx + 1
        cur.cI = cIdx + 1
    }
    emitUpTo(b.items.size)

    val statuses = hunks.flatMap { h -> h.merged.map { it.node.status } }
    val hasConflict = hunks.any { h -> h.merged.any { it.node.status == MergeStatus.CONFLICT } }
    val status = when {
        hasConflict -> MergeStatus.CONFLICT
        statuses.any { it == MergeStatus.RESOLVED } -> MergeStatus.RESOLVED
        statuses.any { it == MergeStatus.AUTO_A || it == MergeStatus.AUTO_B || it == MergeStatus.AUTO_BOTH } ->
            MergeStatus.AUTO_BOTH
        else -> MergeStatus.UNCHANGED
    }
    return MList(path, ListStrategy.SEQ, hunks = hunks, status = status,
        provenance = Provenance(listOf(
            ProvStep(Side.BASE, "list", Fingerprint.of(b)),
            ProvStep(Side.BRANCH_A, "list", Fingerprint.of(a)),
            ProvStep(Side.BRANCH_B, "list", Fingerprint.of(c)),
        ), "有序序列逐块合并：${hunks.count { it.auto }} 个自动块"))
}

private fun seqResolution(r: Resolution, a: List<SNode>, c: List<SNode>, b: List<SNode>): List<SNode> = when (r) {
    Resolution.TakeA -> a
    Resolution.TakeB -> c
    Resolution.KeepBase -> b
    Resolution.Delete -> emptyList()
    Resolution.SetNull -> listOf(semmerge.model.SScalar.NULL)
    is Resolution.Custom -> listOf(CustomValues.parse(r.text, r.format))
    is Resolution.CustomOrder -> error("CustomOrder invalid for SEQ")
}

private fun Merger.elemValue(
    path: Path, index: Int, node: SNode, status: MergeStatus,
    conflict: Conflict? = null, suggestion: Advice? = null,
): MNode = MValue(path.index(index), node,
    "", Fingerprint.of(node), Fingerprint.of(node), status,
    Provenance(emptyList(), statusLabel(status)), conflict, suggestion)

private fun toList(l: List<SNode>): SList = SList(l)

/** LCS-based alignment: each base item maps to its matched index in [other] or -1. */
internal fun matchIndices(base: List<SNode>, other: List<SNode>): List<Pair<Int, Int>> {
    val n = base.size
    val m = other.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (structuralEqual(base[i], other[j])) {
                dp[i + 1][j + 1] + 1
            } else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val match = IntArray(n) { -1 }
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            structuralEqual(base[i], other[j]) -> { match[i] = j; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
    }
    return List(n) { k -> k to match[k] }
}
