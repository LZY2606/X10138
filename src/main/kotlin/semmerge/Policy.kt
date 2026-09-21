package semmerge

enum class ArrayPolicy { REPLACE, MERGE_BY_ID, ORDERED_SEQUENCE }

data class ArrayRule(val policy: ArrayPolicy, val idKey: String? = null)

/** 路径 -> 数组合并策略。未登记的路径绝不猜测，直接产生冲突。 */
data class PolicySet(
    val version: Int = 1,
    val rules: Map<String, ArrayRule> = emptyMap()
) {
    fun ruleFor(path: String): ArrayRule? = rules[path]
}
