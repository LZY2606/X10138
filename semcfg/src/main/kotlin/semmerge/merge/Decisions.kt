package semmerge.merge

import semmerge.iof.TripleFingerprint
import semmerge.model.Path
import java.time.Instant

/** Immutable, replayable human decision. */
data class DecisionRecord(
    val conflictKey: String,
    val path: Path,
    val kind: ConflictKind,
    val resolution: Resolution,
    val fingerprints: TripleFingerprint,
    /** Policy registry version at decision time. */
    val policyVersion: Int,
    val decidedAt: Instant,
    val decidedBy: String,
    /** Monotonic session revision this decision was committed at. */
    val baseSessionVersion: Long,
    val note: String = "",
)

data class DecisionMatch(
    val record: DecisionRecord,
    /** Fully applicable: same path, conflict kind, fingerprints and policy version. */
    val applicable: Boolean,
    val mismatchReason: String? = null,
)

interface DecisionSource {
    /** Newest record for a conflict key. */
    fun find(conflictKey: String): DecisionRecord?

    /** All records for a conflict key (newest first). */
    fun history(conflictKey: String): List<DecisionRecord>

    fun currentPolicyVersion(): Int
    fun currentFingerprints(): TripleFingerprint
}

fun DecisionSource.match(record: DecisionRecord?): DecisionMatch? {
    if (record == null) return null
    val fps = currentFingerprints()
    val reasons = mutableListOf<String>()
    if (record.fingerprints.base != fps.base) reasons += "祖先内容已变化"
    if (record.fingerprints.branchA != fps.branchA) reasons += "分支 A 内容已变化"
    if (record.fingerprints.branchB != fps.branchB) reasons += "分支 B 内容已变化"
    if (record.policyVersion != currentPolicyVersion()) reasons += "策略版本已变化"
    return DecisionMatch(
        record = record,
        applicable = reasons.isEmpty(),
        mismatchReason = reasons.joinToString("；").ifEmpty { null },
    )
}

/** Ordered, named strategies registered per list path. */
data class PolicyEntry(val path: Path, val strategy: ListStrategy, val idField: String, val version: Int)

class PolicyRegistry(
    private val entries: Map<String, PolicyEntry> = emptyMap(),
    val version: Int = 1,
) {
    fun at(path: Path): PolicyEntry? = entries[path.render()]

    fun with(path: Path, strategy: ListStrategy, idField: String = "id"): Pair<PolicyRegistry, PolicyEntry> {
        val newVersion = (entries.values.maxOfOrNull { it.version } ?: 0) + 1
        val entry = PolicyEntry(path, strategy, idField, newVersion)
        return PolicyRegistry(entries + (path.render() to entry), version = newVersion) to entry
    }

    fun without(path: Path): PolicyRegistry {
        val newVersion = (entries.values.maxOfOrNull { it.version } ?: 0) + 1
        return PolicyRegistry(entries - path.render(), version = newVersion)
    }

    fun all(): List<PolicyEntry> = entries.values.sortedBy { it.version }

    companion object {
        val EMPTY = PolicyRegistry()
    }
}
