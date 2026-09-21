package semmerge.merge

import semmerge.model.Path
import semmerge.model.SNode

enum class Side { BASE, BRANCH_A, BRANCH_B, MERGED, DECISION }

/** Status of every node in the merged tree. */
enum class MergeStatus {
    UNCHANGED,
    AUTO_A,        // value taken from branch A, branch B made no relevant change
    AUTO_B,        // value taken from branch B
    AUTO_BOTH,     // both sides made the identical change
    DELETED_A,     // A deleted, B made no change
    DELETED_B,
    CONFLICT,      // needs a human decision (or one was applied)
    RESOLVED,      // human decision applied and still valid
    SUGGESTED,     // stale human decision shown as advice only
    NEEDS_POLICY,  // array diverged without a registered strategy
}

data class ProvStep(
    val side: Side,
    /** "delete" | "null" | "value" | "missing" | "map" | "list" */
    val action: String,
    val fingerprint: String,
    val detail: String? = null,
)

data class Provenance(
    val chain: List<ProvStep>,
    val explanation: String,
)

/** Why two sides could not be merged automatically. */
enum class ConflictKind {
    VALUE,            // different edits of the same scalar
    TYPE,             // e.g. one side scalar, other side map
    DELETE_EDIT,      // one side deleted, the other edited
    DELETE_DELETE,    // both deleted (consistent, auto-resolved, but recorded)
    POLICY_MISSING,   // array strategy not registered
    DUPLICATE_ID,     // same stable id twice on one side
    MISSING_ID,       // id-merged element without the id field
    ORDER,            // both sides reordered the same id set differently
    SEQ,              // ordered-sequence hunk conflict
}

data class Conflict(
    val key: String,
    val path: Path,
    val kind: ConflictKind,
    val message: String,
    /** For SEQ conflicts: zero-based hunk index inside the list node. */
    val index: Int? = null,
    val duplicateId: String? = null,
)

/** A human decision, in a replayable form. */
sealed class Resolution {
    data object TakeA : Resolution()
    data object TakeB : Resolution()
    data object KeepBase : Resolution()
    data object Delete : Resolution()
    /** Keep explicit null (distinct from delete). */
    data object SetNull : Resolution()
    /** Raw text in the declared format ("yaml" | "json"). */
    data class Custom(val text: String, val format: String) : Resolution()
    /** ORDER conflicts only: list of stable ids. */
    data class CustomOrder(val ids: List<String>) : Resolution()

    fun label(): String = when (this) {
        TakeA -> "takeA"
        TakeB -> "takeB"
        KeepBase -> "keepBase"
        Delete -> "delete"
        SetNull -> "setNull"
        is Custom -> "custom"
        is CustomOrder -> "customOrder"
    }
}

sealed class MNode {
    abstract val path: Path
    abstract val status: MergeStatus
    abstract val provenance: Provenance
    abstract val conflict: Conflict?
    /** Advice carried by an old, fingerprint-mismatched decision. */
    abstract val suggestion: Advice?
}

data class Advice(
    val resolutionLabel: String,
    val reason: String,
    val oldFingerprints: Map<String, String>,
)

data class MMap(
    override val path: Path,
    val entries: List<MEntry>,
    override val status: MergeStatus,
    override val provenance: Provenance,
    override val conflict: Conflict? = null,
    override val suggestion: Advice? = null,
) : MNode()

data class MEntry(
    val key: String,
    val node: MNode,
)

data class MList(
    override val path: Path,
    val strategy: ListStrategy?,
    /** REPLACE / SEQ use hunks; ID merge uses items + orderConflict. */
    val items: List<MIdItem> = emptyList(),
    val hunks: List<SeqHunk> = emptyList(),
    val orderConflict: Conflict? = null,
    override val status: MergeStatus,
    override val provenance: Provenance,
    override val conflict: Conflict? = null,
    override val suggestion: Advice? = null,
) : MNode()

data class MIdItem(
    val id: String,
    val node: MNode,
    val order: ItemOrder,
)

enum class ItemOrder { FROM_BASE, FROM_A, FROM_B, CONFLICT, RESOLVED }

data class SeqHunk(
    val start: Int,
    val base: List<SNode>,
    val sideA: List<SNode>,
    val sideB: List<SNode>,
    val auto: Boolean,
    /** Index in the result list. */
    val merged: List<MSeqElem>,
)

data class MSeqElem(
    val node: MNode,
    val side: Side,
)

data class MValue(
    override val path: Path,
    /** Merged semantic value. */
    val value: SNode,
    val baseFp: String,
    val aFp: String,
    val bFp: String,
    override val status: MergeStatus,
    override val provenance: Provenance,
    override val conflict: Conflict? = null,
    override val suggestion: Advice? = null,
) : MNode()

/** Marker for an auto or chosen delete. Kept in maps so provenance survives. */
data class MDeleted(
    override val path: Path,
    override val status: MergeStatus,
    override val provenance: Provenance,
    override val conflict: Conflict? = null,
    override val suggestion: Advice? = null,
) : MNode()

enum class ListStrategy(val id: String, val label: String) {
    REPLACE("replace", "替换：整段数组取一侧"),
    ID("id", "按稳定 id 合并"),
    SEQ("sequence", "有序序列：逐块三方合并"),
}
