package semmerge

/** 数组合并策略：按路径登记；未登记策略的路径绝不猜测。 */
enum class ArrayStrategy { REPLACE, MERGE_BY_ID, ORDERED }

data class ArrayPolicy(
    val pathPattern: String,          // 如 /servers 或 /envs/* /rules，* 匹配任意单段
    val strategy: ArrayStrategy,
    val idKey: String? = null         // MERGE_BY_ID 必填
)

data class PolicySet(
    val version: String,
    val policies: List<ArrayPolicy>
) {
    fun policyFor(path: Path): ArrayPolicy? {
        val rendered = renderPath(path)
        return policies.firstOrNull { matches(it.pathPattern, rendered) }
    }

    companion object {
        fun matches(pattern: String, path: String): Boolean {
            val pSegs = splitSegments(pattern)
            val aSegs = splitSegments(path)
            if (pSegs.size != aSegs.size) return false
            return pSegs.zip(aSegs).all { (p, a) -> p == "*" || p == a }
        }

        /** "/a/b[0]/c" -> ["a","b","0","c"]；索引段与键段同等参与匹配。 */
        fun splitSegments(path: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < path.length) {
                when (val c = path[i]) {
                    '/' -> { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() } }
                    '[' -> {
                        if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                        val end = path.indexOf(']', i)
                        if (end < 0) return out
                        out.add(path.substring(i + 1, end))
                        i = end
                    }
                    else -> cur.append(c)
                }
                i++
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}
