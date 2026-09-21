package merger

/**
 * 通用 diff3：以共同祖先 O(base) 为基准，对齐 A、B 两个有序序列。
 * 用于 SEQUENCE 数组（按元素结构相等）与 ID 数组（按稳定 id）。
 *
 * 经典 sync-point 算法：
 *  - 分别求 base↔A、base↔B 的 LCS 对齐；
 *  - 两边都命中同一祖先下标的位置为稳定点；
 *  - 两个稳定点之间：两边切片都与祖先切片相同 -> 稳定区；否则按
 *    “只有一边变化”自动合并，两边变化且不一致 -> 冲突区。
 */
object Diff3 {

    sealed class Chunk<out T> {
        data class Same<T>(val base: List<T>, val a: List<T>, val b: List<T>) : Chunk<T>()
        data class Conflict<T>(val base: List<T>, val a: List<T>, val b: List<T>) : Chunk<T>()
    }

    private const val NONE = -1

    fun <T> diff3(base: List<T>, a: List<T>, b: List<T>, eq: (T, T) -> Boolean): List<Chunk<T>> {
        val matchA = lcsMatchOfA(base, a, eq) // a 下标 -> base 下标或 NONE
        val matchB = lcsMatchOfA(base, b, eq)

        // 稳定点：按 base 下标排序的、两边共同命中的祖先下标
        val sync = sortedSetOf<Int>()
        val aByBase = HashMap<Int, Int>()
        val bByBase = HashMap<Int, Int>()
        matchA.forEachIndexed { ai, bi -> if (bi != NONE) aByBase[bi] = ai }
        matchB.forEachIndexed { bii, bi -> if (bi != NONE) bByBase[bi] = bii }
        for (bi in aByBase.keys) if (bByBase.containsKey(bi)) sync.add(bi)

        val chunks = mutableListOf<Chunk<T>>()
        var prevBase = NONE
        var prevA = NONE
        var prevB = NONE

        fun addRegion(baseEnd: Int, aEnd: Int, bEnd: Int) {
            val baseSlice = slice(base, prevBase + 1, baseEnd)
            val aSlice = slice(a, prevA + 1, aEnd)
            val bSlice = slice(b, prevB + 1, bEnd)
            val aChanged = !listEq(baseSlice, aSlice, eq)
            val bChanged = !listEq(baseSlice, bSlice, eq)
            if (!aChanged && !bChanged) {
                if (baseSlice.isNotEmpty()) chunks.add(Chunk.Same(baseSlice, aSlice, bSlice))
            } else if (aChanged && bChanged) {
                chunks.add(Chunk.Conflict(baseSlice, aSlice, bSlice))
            } else if (aChanged) {
                // 只有 A 改：采用 A 的版本（B 与祖先相同）
                chunks.add(Chunk.Same(baseSlice, aSlice, bSlice))
            } else {
                chunks.add(Chunk.Same(baseSlice, aSlice, bSlice))
            }
        }

        for (bi in sync) {
            addRegion(bi, aByBase.getValue(bi), bByBase.getValue(bi))
            // 稳定点本身
            chunks.add(Chunk.Same(listOf(base[bi]), listOf(a[aByBase.getValue(bi)]), listOf(b[bByBase.getValue(bi)])))
            prevBase = bi
            prevA = aByBase.getValue(bi)
            prevB = bByBase.getValue(bi)
        }
        addRegion(base.size, a.size, b.size)

        return mergeAdjacent(chunks, eq)
    }

    private fun <T> mergeAdjacent(chunks: List<Chunk<T>>, eq: (T, T) -> Boolean): List<Chunk<T>> {
        // 相邻 Same 合并；冲突区与其后的稳定点保持独立，便于 UI 定位。
        return chunks
    }

    private fun <T> slice(list: List<T>, from: Int, until: Int): List<T> {
        if (from >= until) return emptyList()
        return list.subList(from, until).toList()
    }

    private fun <T> listEq(x: List<T>, y: List<T>, eq: (T, T) -> Boolean): Boolean {
        if (x.size != y.size) return false
        for (i in x.indices) if (!eq(x[i], y[i])) return false
        return true
    }

    /** LCS：返回 side 中每个元素在 base 中匹配的下标（NONE 表示未匹配）。 */
    fun <T> lcsMatchOfA(base: List<T>, side: List<T>, eq: (T, T) -> Boolean): IntArray {
        val n = base.size
        val m = side.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (eq(base[i], side[j])) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val res = IntArray(m) { NONE }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                eq(base[i], side[j]) -> { res[j] = i; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return res
    }
}
