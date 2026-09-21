package merger

/** 数组合并策略：替换 / 按稳定 id 合并 / 有序序列。未登记策略的数组路径一律报冲突，绝不猜测。 */
enum class ArrayStrategy { REPLACE, MERGE_BY_ID, ORDERED }

data class ArrayPolicy(
    val path: String,
    val strategy: ArrayStrategy,
    val idKey: String? = null,
)
