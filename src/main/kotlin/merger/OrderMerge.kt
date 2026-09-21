package merger

/**
 * id 数组的三方顺序合并（元素内容在别处按 id 合并，这里只决定存活 id 的排列）。
 *
 * 用每个序列中“锚点 id -> 后继 id”的映射比较：
 *  - 两边对某锚点给出相同后继 -> 采用；
 *  - 一边保持祖先后继、另一边改动（移动/插入）-> 采用改动方；
 *  - 两边对同一锚点给出不同且都偏离祖先的后继 -> 顺序冲突，交人工选择整体顺序。
 */
object OrderMerge {

    sealed class Result {
        data class Auto(val merged: List<String>) : Result()
        data class Conflict(val reason: String) : Result()
    }

    fun merge(base: List<String>, a: List<String>, b: List<String>): Result {
        if (a == b) return Result.Auto(a)

        val sb = successors(base)
        val sa = successors(a)
        val sc = successors(b)

        // 分叉检测：同一锚点，两边后继不同，且都不等于祖先后继。
        for (k in (sa.keys + sc.keys + sb.keys)) {
            val va = sa[k]; val vb = sc[k]; val vo = sb[k]
            if (va != null && vb != null && va != vb && va != vo && vb != vo) {
                return Result.Conflict("锚点 ${k ?: "<起点>"} 的后继在两边不同：A->$va，B->$vb，祖先->${vo}")
            }
        }

        // 合并后继关系（非祖先的一侧优先），随后从起点拓扑展开。
        val next = HashMap<String?, String?>()
        for (k in (sa.keys + sc.keys + sb.keys)) {
            val va = sa[k]; val vb = sc[k]; val vo = sb[k]
            next[k] = when {
                va == vb -> va
                va == vo -> vb
                vb == vo -> va
                va == null && vb != null -> vb
                vb == null && va != null -> va
                else -> return Result.Conflict("锚点 ${k ?: "<起点>"} 的后继无法调和：A->$va，B->$vb")
            }
        }

        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        var cur = next[null]
        var guard = 0
        while (cur != null) {
            if (!seen.add(cur) || guard++ > (a.size + b.size) + 2) {
                return Result.Conflict("合并顺序产生环或孤立片段")
            }
            result.add(cur)
            cur = next[cur]
        }
        val expected = a.toSet() + b.toSet()
        if (result.toSet() != expected) {
            // 理论上无冲突时不会发生；保底补齐，保证不丢元素。
            result.addAll(expected - result.toSet())
        }
        return Result.Auto(result)
    }

    private fun successors(list: List<String>): Map<String?, String?> {
        val m = HashMap<String?, String?>()
        var prev: String? = null
        for (x in list) { m[prev] = x; prev = x }
        m[prev] = null
        return m
    }
}
