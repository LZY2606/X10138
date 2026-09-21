package merger

/**
 * 冲突类型：
 * - VALUE      叶子值互斥修改 / 删除-修改 / 类型不一致
 * - SEQ_HUNK   有序序列某段双方不同修改（diff3 冲突段）
 * - ORDER      按 id 合并后，元素顺序双方互斥调整
 * - DUP_ID     按 id 策略下某一边出现重复 id
 * - MISSING_POLICY 数组路径没有登记策略，需先选择策略
 */
enum class ConflictType { VALUE, SEQ_HUNK, ORDER, DUP_ID, MISSING_POLICY }

data class Conflict(
    val id: String,
    val type: ConflictType,
    val path: Path,
    val message: String,
    val base: Node?,
    val a: Node?,
    val b: Node?,
    val detail: ConflictDetail = ConflictDetail.None,
    val resolution: Resolution? = null,
    /** 指纹不匹配而仅作为建议的历史裁决（不自动套用）。 */
    val suggested: StoredDecision? = null,
) {
    val resolved: Boolean get() = resolution != null
}

sealed class ConflictDetail {
    data object None : ConflictDetail()
    /** SEQ hunk: 祖先/A/B 三个原始片段（已按节点身份对齐）。 */
    data class SeqHunk(val base: List<Node>, val a: List<Node>, val b: List<Node>) : ConflictDetail()
    /** ORDER: 双方期望的 id 顺序。 */
    data class Order(val baseOrder: List<String>, val aOrder: List<String>, val bOrder: List<String>) : ConflictDetail()
    /** DUP_ID: 哪一边出现重复 id 及重复值。 */
    data class DupId(val side: Side, val id: String, val count: Int) : ConflictDetail()
    /** MISSING_POLICY: 当前路径登记候选。 */
    data class MissingPolicy(val pathKey: String) : ConflictDetail()
}

/**
 * 人工裁决。DELETE 与 KEEP_NULL 分开表达，保证删除 ≠ 置 null。
 */
sealed class Resolution {
    data class Take(val side: Side) : Resolution()
    data class SetValue(val value: Node) : Resolution()
    data object Delete : Resolution()
    /** SEQ_HUNK 专用：选择一边的整个冲突段。 */
    data class TakeHunk(val side: Side) : Resolution()
    /** SEQ_HUNK 专用：手工给定的片段（解析后的节点列表文本）。 */
    data class CustomHunk(val nodes: List<Node>) : Resolution()
    /** ORDER 专用：采用某一边的顺序，或显式给定 id 顺序。 */
    data class TakeOrder(val side: Side) : Resolution()
    data class CustomOrder(val ids: List<String>) : Resolution()
    /** MISSING_POLICY 专用：登记策略。 */
    data class ChoosePolicy(val policy: ArrayPolicy, val idKey: String?) : Resolution()
}
