package merger

import merger.Merger.lift

object SeqMerger {

    fun idMerge(base: PSeq?, ours: PSeq?, theirs: PSeq?, path: Path,
                policies: PolicyRegistry): MergeSeq {
        val bMap = base?.items.orEmpty().associateById()
        val oMap = ours?.items.orEmpty().associateById()
        val tMap = theirs?.items.orEmpty().associateById()
        val dup = (ours?.items.orEmpty() + theirs?.items.orEmpty()).findDuplicateIds()

        if (dup.isNotEmpty()) {
            return MergeSeq(path, MergeStatus.CONFLICT, ChangeKind.BOTH_MOD,
                ArrayPolicy.ID_MERGE, base, ours, theirs, emptyList(),
                dupIds = dup)
        }

        val order = LinkedHashSet<String>()
        bMap.keys.forEach(order::add)
        oMap.keys.forEach(order::add)
        tMap.keys.forEach(order::add)

        val rows = mutableListOf<SeqRow>()
        var status = MergeStatus.UNCHANGED
        var idx = 0
        for (id in order) {
            val b = bMap[id]; val o = oMap[id]; val t = tMap[id]
            val child = Merger.node(b, o, t, path + id, policies, idx)
            rows.add(SeqRow(idx, RowKind.ID_ROW,
                baseIdx = b?.let { base!!.items.indexOf(it) },
                oursIdx = o?.let { ours!!.items.indexOf(it) },
                theirsIdx = t?.let { theirs!!.items.indexOf(it) },
                value = child, id = id))
            status = lift(status, child.status)
            idx++
        }
        val resolved = buildSeqIfClean(path, rows)
        return MergeSeq(path, status,
            if (status == MergeStatus.UNCHANGED) ChangeKind.NONE else ChangeKind.BOTH_MOD,
            ArrayPolicy.ID_MERGE, base, ours, theirs, rows, resolved = resolved)
    }

    private fun List<PNode>.associateById(): Map<String, PNode> {
        val map = LinkedHashMap<String, PNode>()
        for (item in this) {
            val id = stableId(item) ?: continue
            if (!map.containsKey(id)) map[id] = item
        }
        return map
    }

    private fun List<PNode>.findDuplicateIds(): List<String> {
        val seen = HashSet<String>()
        val dup = LinkedHashSet<String>()
        for (item in this) {
            val id = stableId(item) ?: continue
            if (!seen.add(id)) dup.add(id)
        }
        return dup.toList()
    }

    fun stableId(node: PNode): String? {
        if (node !is PMap) return null
        val idNode = node.get("id")
        return if (idNode is PScalar && idNode.tag != ScalarTag.NULL) idNode.value else null
    }

    fun ordered(base: PSeq?, ours: PSeq?, theirs: PSeq?, path: Path,
                policies: PolicyRegistry): MergeSeq {
        val b = base?.items.orEmpty()
        val o = ours?.items.orEmpty()
        val t = theirs?.items.orEmpty()
        val ops = threeWayDiff(b, o, t)
        val rows = mutableListOf<SeqRow>()
        val conflicts = mutableListOf<SeqConflict>()
        var status = MergeStatus.UNCHANGED
        var outOrder = 0
        for (op in ops) {
            when (op) {
                is Op.Keep -> {
                    val child = Merger.node(op.bItem, op.oItem, op.tItem,
                        path + ("[" + outOrder + "]"), policies, outOrder)
                    rows.add(SeqRow(outOrder, RowKind.COMMON, op.bIdx, op.oIdx, op.tIdx, child))
                    status = lift(status, child.status)
                    outOrder++
                }
                is Op.OursInsert -> {
                    val child = Merger.node(null, op.item, null,
                        path + ("[" + outOrder + "]"), policies, outOrder)
                    rows.add(SeqRow(outOrder, RowKind.OURS_ONLY, null, op.idx, null, child))
                    status = lift(status, MergeStatus.AUTO)
                    outOrder++
                }
                is Op.TheirsInsert -> {
                    val child = Merger.node(null, null, op.item,
                        path + ("[" + outOrder + "]"), policies, outOrder)
                    rows.add(SeqRow(outOrder, RowKind.THEIRS_ONLY, null, null, op.idx, child))
                    status = lift(status, MergeStatus.AUTO)
                    outOrder++
                }
                is Op.ConflictRegion -> {
                    val chunk = SeqConflict(op.baseIdx, op.oursIdx, op.theirsIdx)
                    val chunkIdx = conflicts.size
                    conflicts.add(chunk)
                    for ((i, bi) in op.baseIdx.withIndex()) {
                        rows.add(SeqRow(outOrder, RowKind.CHUNK, bi, null, null,
                            Merger.node(b[bi], null, null,
                                path + ("#chunk" + chunkIdx + ".b" + i), policies, outOrder),
                            chunkIndex = chunkIdx))
                        outOrder++
                    }
                    status = MergeStatus.CONFLICT
                }
            }
        }
        val resolved = if (status == MergeStatus.UNCHANGED || status == MergeStatus.AUTO)
            buildSeqIfClean(path, rows) else null
        return MergeSeq(path, status,
            if (status == MergeStatus.UNCHANGED) ChangeKind.NONE else ChangeKind.BOTH_MOD,
            ArrayPolicy.ORDERED, base, ours, theirs, rows,
            conflicts = conflicts, resolved = resolved)
    }

    /** Flatten clean rows into a PSeq; returns null if any row is unresolved. */
    fun buildSeqIfClean(path: Path, rows: List<SeqRow>): PSeq? {
        val values = mutableListOf<PNode>()
        var order = 0
        for (row in rows) {
            if (row.chunkIndex != null) return null
            val v = Merger.effectiveValue(row.value) ?: return null
            values.add(v.withPath(path + ("[" + order + "]"), order))
            order++
        }
        return PSeq(values, SourceInfo(Side.BASE, path, 0))
    }

    sealed class Op {
        data class Keep(val bIdx: Int, val oIdx: Int?, val tIdx: Int?,
                        val bItem: PNode, val oItem: PNode?, val tItem: PNode?) : Op()
        data class OursInsert(val idx: Int, val item: PNode) : Op()
        data class TheirsInsert(val idx: Int, val item: PNode) : Op()
        data class ConflictRegion(val baseIdx: List<Int>, val oursIdx: List<Int>,
                                  val theirsIdx: List<Int>) : Op()
    }

    fun threeWayDiff(base: List<PNode>, ours: List<PNode>, theirs: List<PNode>): List<Op> {
        val matchO = lcsIndices(base, ours).toMap()
        val matchT = lcsIndices(base, theirs).toMap()
        val ops = mutableListOf<Op>()

        var lastB = -1
        var lastO = -1
        var lastT = -1
        var i = 0
        while (i <= base.size) {
            val sync = i < base.size && matchO.containsKey(i) && matchT.containsKey(i)
            if (!sync) { i++; continue }
            emitRegion(ops, base, ours, theirs,
                lastB + 1, i, lastO + 1, matchO[i]!!, lastT + 1, matchT[i]!!, matchO, matchT)
            ops.add(Op.Keep(i, matchO[i], matchT[i], base[i], ours[matchO[i]!!], theirs[matchT[i]!!]))
            lastB = i; lastO = matchO[i]!!; lastT = matchT[i]!!
            i++
        }
        emitRegion(ops, base, ours, theirs,
            lastB + 1, base.size, lastO + 1, ours.size, lastT + 1, theirs.size, matchO, matchT)
        return ops
    }

    private fun emitRegion(
        ops: MutableList<Op>,
        base: List<PNode>, ours: List<PNode>, theirs: List<PNode>,
        bFrom: Int, bTo: Int, oFrom: Int, oTo: Int, tFrom: Int, tTo: Int,
        mapO: Map<Int, Int>, mapT: Map<Int, Int>,
    ) {
        if (bFrom == bTo && oFrom == oTo && tFrom == tTo) return
        val baseIdx = (bFrom until bTo).toList()
        val oursIdx = (oFrom until oTo).toList()
        val theirsIdx = (tFrom until tTo).toList()
        val oursSame = sliceEqualsBase(oursIdx, baseIdx, mapO)
        val theirsSame = sliceEqualsBase(theirsIdx, baseIdx, mapT)
        when {
            oursSame && theirsSame ->
                baseIdx.forEach { idx -> ops.add(Op.Keep(idx, mapO[idx], mapT[idx], base[idx], null, null)) }
            oursSame && !theirsSame ->
                theirsIdx.forEach { ops.add(Op.TheirsInsert(it, theirs[it])) }
            !oursSame && theirsSame ->
                oursIdx.forEach { ops.add(Op.OursInsert(it, ours[it])) }
            else -> ops.add(Op.ConflictRegion(baseIdx, oursIdx, theirsIdx))
        }
    }

    private fun sliceEqualsBase(sideIdx: List<Int>, baseIdx: List<Int>, match: Map<Int, Int>): Boolean {
        if (sideIdx.size != baseIdx.size) return false
        for (k in baseIdx.indices) if (match[baseIdx[k]] != sideIdx[k]) return false
        return true
    }

    fun lcsIndices(a: List<PNode>, b: List<PNode>): List<Pair<Int, Int>> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) {
            for (y in m - 1 downTo 0) {
                dp[x][y] = if (a[x].fingerprint() == b[y].fingerprint()) dp[x + 1][y + 1] + 1
                else maxOf(dp[x + 1][y], dp[x][y + 1])
            }
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var x = 0; var y = 0
        while (x < n && y < m) {
            when {
                a[x].fingerprint() == b[y].fingerprint() -> { result.add(x to y); x++; y++ }
                dp[x + 1][y] >= dp[x][y + 1] -> x++
                else -> y++
            }
        }
        return result
    }
}
