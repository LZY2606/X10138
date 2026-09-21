package merger

enum class MergeStatus {
    UNCHANGED,
    AUTO,        // automatically merged
    CONFLICT,    // real conflict needing a decision
    NEEDS_POLICY,// unregistered array strategy
    RESOLVED,    // conflict resolved by a bound human decision
}

enum class ChangeKind(val label: String) {
    NONE("未变更"),
    OURS_MOD("基础版→分支A 修改"),
    THEIRS_MOD("基础版→分支B 修改"),
    BOTH_MOD("两边均修改"),
    OURS_ADD("分支A 新增"),
    THEIRS_ADD("分支B 新增"),
    BOTH_ADD("两边均新增(值不同)"),
    OURS_DEL("分支A 删除"),
    THEIRS_DEL("分支B 删除"),
    DEL_VS_MOD("一边删除，另一边修改"),
    NULL_VS_MOD("一边置 null，另一边修改"),
}

enum class ArrayPolicy(val code: String, val label: String) {
    REPLACE("replace", "整体替换"),
    ID_MERGE("id", "按稳定 id 合并"),
    ORDERED("ordered", "有序序列三方合并"),
}

/** A selected side/literal value chosen by a human decision. */
enum class Choice { OURS, THEIRS, BASE, CUSTOM }

/**
 * Result of the three-way merge at one path.
 */
sealed class MergeNode {
    abstract val path: Path
    abstract val status: MergeStatus
    abstract val kind: ChangeKind
    /** Resolved value (PNode with merged provenance) or null while unresolved. */
    abstract val resolved: PNode?
    abstract val decisionId: String?
}

data class MergeScalar(
    override val path: Path,
    override val status: MergeStatus,
    override val kind: ChangeKind,
    val base: PScalar?,
    val ours: PScalar?,
    val theirs: PScalar?,
    override val resolved: PNode?,
    override val decisionId: String? = null,
) : MergeNode()

data class MergeMap(
    override val path: Path,
    override val status: MergeStatus,
    override val kind: ChangeKind,
    val children: List<Pair<String, MergeNode>>,
    override val resolved: PNode?,
    override val decisionId: String? = null,
) : MergeNode()

/** Conflict chunk for ordered-sequence merge. */
data class SeqConflict(
    val baseChunk: List<Int>,
    val oursChunk: List<Int>,
    val theirsChunk: List<Int>,
)

data class MergeSeq(
    override val path: Path,
    override val status: MergeStatus,
    override val kind: ChangeKind,
    val policy: ArrayPolicy?,
    val base: PSeq?,
    val ours: PSeq?,
    val theirs: PSeq?,
    /** Aligned output rows (ID_MERGE and ORDERED); empty for REPLACE. */
    val rows: List<SeqRow> = emptyList(),
    /** Ordered-merge conflicting chunks. */
    val conflicts: List<SeqConflict> = emptyList(),
    val dupIds: List<String> = emptyList(),
    /** Bound chunk choices for ORDERED conflicts: chunkIndex -> side. */
    val chunkChoices: Map<Int, Choice> = emptyMap(),
    override val resolved: PNode? = null,
    override val decisionId: String? = null,
) : MergeNode()

/** One aligned output row of a sequence (id or ordered). */
data class SeqRow(
    val order: Int,
    val kind: RowKind,
    val baseIdx: Int?,
    val oursIdx: Int?,
    val theirsIdx: Int?,
    val value: MergeNode,
    /** Stable id for ID_MERGE rows. */
    val id: String? = null,
    /** When the row belongs to an unresolved ordered chunk, its index. */
    val chunkIndex: Int? = null,
)

enum class RowKind { COMMON, OURS_ONLY, THEIRS_ONLY, CHUNK, ID_ROW }
