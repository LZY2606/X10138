package merger

/**
 * 数组合并策略。必须为具体数组路径显式登记，未登记时合并器拒绝猜测。
 */
enum class ArrayPolicy(val code: String, val label: String) {
    REPLACE("replace", "整体替换"),
    ID("id", "按稳定 id 合并"),
    SEQ("seq", "有序序列（diff3）");

    companion object {
        fun of(code: String): ArrayPolicy? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 策略集合，带版本号。策略路径形如 services 或 services.items[]。
 * - `services.items` 指向该数组本身的策略登记键（即 `...[]` 的父路径）。
 * 统一存储：数组路径以 `[]` 结尾，例如 `services[]`、`items[]`。
 */
data class PolicyRegistry(
    val version: Int,
    val arrayPolicies: Map<String, ArrayPolicy>,
    val idField: Map<String, String> = emptyMap(),
) {
    /** 数组合并发生在数组节点路径（如 servers），登记键以 [] 结尾（如 servers[]）。 */
    fun arrayPolicyAt(path: Path): ArrayPolicy? {
        val key = path.toString().removePrefix(".")
        return arrayPolicies["$key[]"] ?: arrayPolicies[key]
    }

    fun idKeyAt(path: Path): String {
        val key = path.toString().removePrefix(".")
        return idField["$key[]"] ?: idField[key] ?: "id"
    }

    companion object {
        val EMPTY = PolicyRegistry(1, emptyMap())
    }
}
