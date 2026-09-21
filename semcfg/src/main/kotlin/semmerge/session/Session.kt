package semmerge.session

import semmerge.iof.Fingerprint
import semmerge.iof.TripleFingerprint
import semmerge.merge.Conflict
import semmerge.merge.DecisionMatch
import semmerge.merge.DecisionRecord
import semmerge.merge.DecisionSource
import semmerge.merge.ListStrategy
import semmerge.merge.MergeResult
import semmerge.merge.Merger
import semmerge.merge.PolicyEntry
import semmerge.merge.PolicyRegistry
import semmerge.merge.Resolution
import semmerge.merge.match
import semmerge.model.Path
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import java.nio.file.Path as JPath
import java.time.Instant

enum class InputSide(val key: String) { BASE("base"), BRANCH_A("branchA"), BRANCH_B("branchB") }

data class RawInput(val text: String, val format: String) {
    companion object {
        val EMPTY = RawInput("", "yaml")
    }
}

class OptimisticLockException(
    val expected: Long,
    val actual: Long,
    val alreadySolvedKeys: List<String>,
) : RuntimeException("乐观版本过期：expected=$expected actual=$actual")

data class CommitResult(
    val session: SessionState,
    val merge: MergeResult,
    val acceptedKeys: List<String>,
    val rejectedStaleKeys: List<String>,
)

data class SessionState(
    val inputs: Map<InputSide, RawInput>,
    val parsed: Map<InputSide, SNode>,
    val policies: PolicyRegistry,
    /** conflictKey -> newest-first records. */
    val decisions: Map<String, List<DecisionRecord>>,
    val version: Long,
) : DecisionSource {
    val fingerprints: TripleFingerprint
        get() = TripleFingerprint(
            Fingerprint.of(parsed[InputSide.BASE]),
            Fingerprint.of(parsed[InputSide.BRANCH_A]),
            Fingerprint.of(parsed[InputSide.BRANCH_B]),
        )

    override fun find(conflictKey: String): DecisionRecord? = decisions[conflictKey]?.firstOrNull()
    override fun history(conflictKey: String): List<DecisionRecord> = decisions[conflictKey] ?: emptyList()
    override fun currentPolicyVersion(): Int = policies.version
    override fun currentFingerprints(): TripleFingerprint = fingerprints

    companion object {
        fun initial(): SessionState {
            val inputs = InputSide.entries.associateWith { RawInput.EMPTY }
            val parsed = InputSide.entries.associateWith { semmerge.model.SMap() }
            return SessionState(inputs, parsed, PolicyRegistry.EMPTY, emptyMap(), 1)
        }
    }
}

class SessionService(private val store: SessionStore) {
    @Volatile
    var state: SessionState = store.load() ?: SessionState.initial()
        private set

    init {
        store.save(state)
    }

    @Synchronized
    fun recompute(): MergeResult {
        return Merger(
            state.parsed[InputSide.BASE],
            state.parsed[InputSide.BRANCH_A],
            state.parsed[InputSide.BRANCH_B],
            state.policies,
            state,
        ).merge()
    }

    @Synchronized
    fun updateInput(side: InputSide, raw: RawInput, expectedVersion: Long): Pair<SessionState, MergeResult> {
        checkVersion(expectedVersion)
        val parsed = InputParser.parse(raw)
        val newInputs = state.inputs + (side to raw)
        val newParsed = state.parsed + (side to parsed)
        state = state.copy(inputs = newInputs, parsed = newParsed, version = state.version + 1)
        store.save(state)
        return state to recompute()
    }

    @Synchronized
    fun setPolicy(path: Path, strategy: ListStrategy, idField: String, expectedVersion: Long): Pair<SessionState, MergeResult> {
        checkVersion(expectedVersion)
        val (newPolicies, _) = state.policies.with(path, strategy, idField)
        state = state.copy(policies = newPolicies, version = state.version + 1)
        store.save(state)
        return state to recompute()
    }

    @Synchronized
    fun removePolicy(path: Path, expectedVersion: Long): Pair<SessionState, MergeResult> {
        checkVersion(expectedVersion)
        state = state.copy(policies = state.policies.without(path), version = state.version + 1)
        store.save(state)
        return state to recompute()
    }

    /**
     * Batch conflict resolution with optimistic concurrency.
     * Each item is checked against current live conflicts: a conflict that
     * someone else already solved (and which disappeared) is reported as
     * stale and never overwritten; an overall version mismatch aborts the
     * whole batch before any write.
     */
    @Synchronized
    fun resolve(
        items: List<ResolutionRequest>,
        expectedVersion: Long,
        by: String = "local-user",
    ): CommitResult {
        if (expectedVersion != state.version) {
            val current = currentConflictKeys()
            val requested = items.map { it.conflictKey }.toSet()
            throw OptimisticLockException(expectedVersion, state.version,
                requested.filterNot { it in current })
        }
        val current = currentConflicts().associateBy { it.key }
        val accepted = mutableListOf<String>()
        val stale = mutableListOf<String>()
        val now = Instant.now()
        val newMap = state.decisions.toMutableMap()

        for (item in items) {
            val conflict = current[item.conflictKey]
            if (conflict == null) {
                stale += item.conflictKey
                continue
            }
            val record = DecisionRecord(
                conflictKey = conflict.key,
                path = conflict.path,
                kind = conflict.kind,
                resolution = item.resolution,
                fingerprints = state.fingerprints,
                policyVersion = state.policies.version,
                decidedAt = now,
                decidedBy = by,
                baseSessionVersion = expectedVersion,
                note = item.note,
            )
            newMap[conflict.key] = listOf(record) + (newMap[conflict.key] ?: emptyList())
            accepted += conflict.key
        }

        state = state.copy(decisions = newMap, version = state.version + 1)
        store.save(state)
        val merge = recompute()
        return CommitResult(state, merge, accepted, stale)
    }

    @Synchronized
    fun replaceState(newState: SessionState): MergeResult {
        state = newState
        store.save(state)
        return recompute()
    }

    @Synchronized
    fun reset(): MergeResult {
        state = SessionState.initial()
        store.save(state)
        return recompute()
    }

    private fun currentConflicts(): List<Conflict> = recompute().conflicts
    private fun currentConflictKeys(): Set<String> = currentConflicts().map { it.key }.toSet()

    private fun checkVersion(expected: Long) {
        if (expected != state.version) {
            throw OptimisticLockException(expected, state.version, emptyList())
        }
    }

    fun adviceFor(): List<DecisionMatch> {
        val keys = state.decisions.keys
        return keys.mapNotNull { key ->
            val m = state.match(state.find(key))
            if (m != null && !m.applicable) m else null
        }
    }
}

data class ResolutionRequest(
    val conflictKey: String,
    val resolution: Resolution,
    val note: String = "",
)

object InputParser {
    fun parse(raw: RawInput): SNode = semmerge.merge.CustomValues.parse(raw.text, raw.format)
}
