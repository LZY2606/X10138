package merger

/**
 * diff3 三向序列对齐。输入祖先 base 与两边 A、B 以及相等判定，
 * 输出 Stable / Change 片段序列。
 *
 * 算法：先分别求 base->A、base->B 的 LCS 编辑对齐，再把三条序列按
 * “同步区域（三边相等元素）”切成块，中间块各自携带三边的子序列。
 */
object Diff3 {
    sealed class Chunk<out T> {
        data class Stable<T>(val base: List<T>) : Chunk<T>()
        data class Change<T>(
            val base: List<T>,
            val a: List<T>,
            val b: List<T>,
            val baseStart: Int,
        ) : Chunk<T>()
    }

    /** 对齐标记：每个元素对应一条 base 下标（-1 表示新增）。 */
    private data class Aligned(val baseIndex: Int, val isNew: Boolean = baseIndex < 0)

    private enum class SideTag { X, Y, BOTH }

    fun <T> align(base: List<T>, a: List<T>, b: List<T>, eq: (T, T) -> Boolean): List<Chunk<T>> {
        val alignA = lcsAlign(base, a, eq)
        val alignB = lcsAlign(base, b, eq)

        // 每个 base 元素在 A/B 中是否保持（位置可能移动，diff3 只看匹配身份）
        val keptA = BooleanArray(base.size)
        val keptB = BooleanArray(base.size)
        alignA.forEach { if (it >= 0) keptA[it] = true }
        alignB.forEach { if (it >= 0) keptB[it] = true }

        val chunks = mutableListOf<Chunk<T>>()
        var bi = 0
        var ai = 0
        var bbi = 0
        while (bi < base.size || ai < a.size || bbi < b.size) {
            // 找下一个三边都保留的稳定锚点
            var sync = -1
            var scan = bi
            while (scan < base.size) {
                if (keptA[scan] && keptB[scan]) { sync = scan; break }
                scan++
            }
            if (sync < 0) {
                chunks.add(
                    Chunk.Change(
                        base.subList(bi, base.size).toList(),
                        collectTail(a, ai, alignA, bi, base.size),
                        collectTail(b, bbi, alignB, bi, base.size),
                        bi,
                    )
                )
                break
            }
            // 变化块：bi until sync
            if (sync > bi || hasNewBefore(a, ai, alignA, sync) || hasNewBefore(b, bbi, alignB, sync)) {
                val aSeg = collectSegment(a, ai, alignA, bi, sync)
                val bSeg = collectSegment(b, bbi, alignB, bi, sync)
                chunks.add(Chunk.Change(base.subList(bi, sync).toList(), aSeg.first, bSeg.first, bi))
                ai = aSeg.second
                bbi = bSeg.second
            }
            // 稳定块：单个同步元素（相同身份即认为内容一致，因为 LCS 用结构相等）
            val stableA = advanceToMatch(a, ai, alignA, sync)
            val stableB = advanceToMatch(b, bbi, alignB, sync)
            ai = stableA.second
            bbi = stableB.second
            chunks.add(Chunk.Stable(listOf(base[sync])))
            bi = sync + 1
        }
        return chunks
    }

    /** 返回 side 序列每个元素对应的 base 下标，新增元素为 -1。 */
    private fun <T> lcsAlign(base: List<T>, side: List<T>, eq: (T, T) -> Boolean): IntArray {
        val n = base.size
        val m = side.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (eq(base[i], side[j])) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val result = IntArray(m) { -1 }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                eq(base[i], side[j]) -> { result[j] = i; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return result
    }

    private fun <T> hasNewBefore(side: List<T>, from: Int, align: IntArray, baseIndex: Int): Boolean {
        var k = from
        while (k < align.size && (align[k] < 0 || align[k] < baseIndex)) {
            if (align[k] < 0) return true
            k++
        }
        return false
    }

    /** 收集 side 中属于 base 区间 [start,end) 的元素及其间的新增元素，返回新游标。 */
    private fun <T> collectSegment(side: List<T>, from: Int, align: IntArray, start: Int, end: Int): Pair<List<T>, Int> {
        val out = mutableListOf<T>()
        var k = from
        while (k < align.size) {
            val bi = align[k]
            if (bi < 0) { out.add(side[k]); k++; continue }
            if (bi < start) { k++; continue }
            if (bi >= end) break
            out.add(side[k]); k++
        }
        return out to k
    }

    private fun <T> collectTail(side: List<T>, from: Int, align: IntArray, start: Int, end: Int): List<T> {
        val out = mutableListOf<T>()
        var k = from
        while (k < align.size) {
            val bi = align[k]
            if (bi >= start || bi < 0) out.add(side[k])
            k++
        }
        return out
    }

    private fun <T> advanceToMatch(side: List<T>, from: Int, align: IntArray, baseIndex: Int): Pair<T, Int> {
        var k = from
        while (k < align.size && align[k] != baseIndex) k++
        require(k < side.size) { "diff3 内部错误：找不到同步元素" }
        return side[k] to k + 1
    }
}
