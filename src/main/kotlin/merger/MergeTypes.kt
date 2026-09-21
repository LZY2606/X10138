package merger

import java.time.Instant

/** 三方：共同祖先 base，分支 A，分支 B。缺失与 null 在模型中已区分。 */

enum class ArrayStrategy { REPLACE, ID, SEQUENCE }

data class StrategyEntry(
    val path: Path,
    val strategy: ArrayStrategy,
    val idField: String = "id"
)

data class StrategyRegistry(val entries: Map<String, StrategyEntry> = emptyMap(), val version: Int = 0) {
    fun lookup(path: Path): StrategyEntry? {
        // 精确匹配优先，其次允许登记路径上的 Any 段（如 list[*] 不用于数组本身）。
        entries[path.render()]?.let { return it }
        return entries.values.firstOrNull { Path.matches(it.path, path) }
    }
    fun with(path: Path, strategy: ArrayStrategy, idField: String = "id"): StrategyRegistry {
        val key = path.render()
        val copy = entries + (key to StrategyEntry(path, strategy, idField))
        return copy(entries = copy, version = version + 1)
    }
    companion object { val EMPTY = StrategyRegistry() }
}

enum class MergeSide { BASE, A, B }

data class ProvenanceStep(
    val side: MergeSide,
    val path: String,
    val action: String,
    val anchor: String? = null
)

enum class MergeStatus { AUTO, CONFLICT, MAP, ARRAY, BLOCKED }

enum class ConflictKind {
    VALUE,            // 叶子标量：两边都改且不同
    DELETE_NULL,      // 一边删除，另一边把同一值设为 null（动作不同）
    TYPE_MISMATCH,    // 三方类型结构不一致
    NO_STRATEGY,      // 数组路径未登记策略
    DUP_ID,           // 同一侧数组出现重复稳定 id
    ARRAY_ID_SCHEMA,  // id 策略下元素不是对象或缺 id 字段
    SEQUENCE_REGION,  // 有序序列同一段两边各自不同改动
    ARRAY_ORDER,      // id 数组同一组元素两边移动到不同位置
    CUSTOM_REJECT     // 手工裁决无法解析
}

data class Choice(
    val id: String,         // A / B / BASE / CUSTOM
    val label: String,
    val preview: String,    // 该选择对应的文本值预览
    val side: MergeSide? = null
)

data class Conflict(
    val kind: ConflictKind,
    val reason: String,
    val choices: List<Choice>,
    val path: String
)

/**
 * 合并树节点。children 键：
 *  Map  -> 字段名
 *  Seq  -> id=... 或下标字符串；冲突占位区用 region:<n>
 */
data class MergeNode(
    val path: String,
    val keyLabel: String,
    val status: MergeStatus,
    val base: SNode?,
    val a: SNode?,
    val b: SNode?,
    val result: SNode?,
    val children: LinkedHashMap<String, MergeNode> = LinkedHashMap(),
    val provenance: List<ProvenanceStep> = emptyList(),
    val conflict: Conflict? = null,
    val autoSummary: String? = null,
    val resolvedBy: ResolvedBy? = null,
    val advisory: Advisory? = null
)

data class ResolvedBy(val decisionId: String, val choiceId: String, val at: Instant, val custom: Boolean)

/** 指纹不再匹配时的旧裁决提示。 */
data class Advisory(val oldDecisionId: String, val suggestedChoice: String, val reason: String)

data class Decision(
    val id: String,
    val path: String,
    val choiceId: String, // A / B / BASE / CUSTOM
    val customText: String? = null,
    val customFormat: Format = Format.YAML,
    val baseFingerprint: String,
    val aFingerprint: String,
    val bFingerprint: String,
    val conflictKind: ConflictKind,
    val createdAt: Instant,
    val author: String = "local",
    val baseVersion: Int,
    val detail: String? = null
) {
    fun binds(base: String, a: String, b: String) =
        baseFingerprint == base && aFingerprint == a && bFingerprint == b
}

/**
 * 路径在数组内部的真实段。merge 递归需要区分三类：
 *  Map 键、数组下标、id 元素。
 */
sealed class AKey {
    data class K(val name: String) : AKey()
    data class Idx(val i: Int) : AKey()
    data class ById(val field: String, val value: String) : AKey()
    data class Region(val tag: String) : AKey()
}
