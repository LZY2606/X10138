package merger

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 一次合并会话：持有三边输入、策略版本、冲突解决状态与乐观版本号。
 *
 * 合并在以下时机重新计算：
 * 1) 输入或策略变化；2) 提交一批裁决后（决议被应用进结果）。
 *
 * 指纹绑定保证：只有三边指纹与策略版本完全匹配的旧裁决才自动套用；
 * 不匹配时挂为建议（suggested），由人显式确认。
 */
class MergeSession(
    val id: String,
    var baseInput: InputDoc,
    var aInput: InputDoc,
    var bInput: InputDoc,
    var policies: PolicyRegistry,
) {
    private val lock = ReentrantReadWriteLock()

    /** 单调版本：输入/策略/裁决每次变化 +1，用于乐观并发提交。 */
    var revision: Long = 0
        private set

    /** 已经保存的裁决历史（全部，含可能失效的）。 */
    private val decisionHistory: MutableList<StoredDecision> = mutableListOf()

    /** 已确认的解决映射：conflictId -> StoredDecision（仅当前文档自动应用）。 */
    private val accepted: MutableMap<String, StoredDecision> = mutableMapOf()

    /** 最近一次合并文档。 */
    var document: MergeDocument = recomputeLocked(applyDecisions = false)
        private set

    data class InputDoc(val fileName: String, val format: ConfigFormat, val text: String) {
        fun parse(): Node = ConfigIO.parse(text, format, fileName)
    }

    fun decisions(): List<StoredDecision> = lock.read { decisionHistory.toList() }

    fun snapshot(): SessionSnapshot = lock.read {
        SessionSnapshot(
            id = id,
            revision = revision,
            policies = policies,
            baseInput = baseInput,
            aInput = aInput,
            bInput = bInput,
            document = document,
            decisions = decisionHistory.toList(),
            accepted = accepted.toMap(),
        )
    }

    fun updateInputs(base: InputDoc?, a: InputDoc?, b: InputDoc?) {
        lock.write {
            if (base != null) baseInput = base
            if (a != null) aInput = a
            if (b != null) bInput = b
            bumpAndRecompute()
        }
    }

    fun updatePolicies(newPolicies: PolicyRegistry) {
        lock.write {
            policies = newPolicies
            bumpAndRecompute()
        }
    }

    /**
     * 乐观批量提交。expectedRevision 过期（期间已有他人提交）则整批拒绝：
     * 不覆盖任何已经被解决的冲突，返回 409 风格结果让调用方刷新后重试。
     */
    fun resolve(
        expectedRevision: Long,
        items: List<ResolutionRequest>,
    ): ResolutionResult = lock.write {
        if (expectedRevision != revision) {
            return@write ResolutionResult.Conflict(
                currentRevision = revision,
                alreadyResolved = items.mapNotNull { req ->
                    document.conflicts.firstOrNull { it.id == req.conflictId && it.resolved }?.id
                },
            )
        }
        // 在旧文档上校验：所有目标必须存在且未解决
        val byId = document.conflicts.associateBy { it.id }
        for (req in items) {
            val conflict = byId[req.conflictId]
                ?: return@write ResolutionResult.NotFound(req.conflictId)
            if (conflict.resolved) {
                return@write ResolutionResult.Conflict(revision, listOf(conflict.id))
            }
        }
        for (req in items) {
            val conflict = byId.getValue(req.conflictId)
            val decision = StoredDecision(
                id = DecisionMaker.newId(),
                conflictType = conflict.type,
                path = conflict.path.toString(),
                baseFingerprint = document.fingerprints.first,
                aFingerprint = document.fingerprints.second,
                bFingerprint = document.fingerprints.third,
                policyVersion = document.policyVersion,
                resolution = DecisionPayload.from(req.resolution),
                reason = req.reason,
                createdAt = java.time.Instant.now().toString(),
            )
            decisionHistory.add(decision)
            accepted[conflict.id] = decision
            // MISSING_POLICY 的裁决同时更新策略集，需要先升级策略再重算
            if (req.resolution is Resolution.ChoosePolicy) {
                applyPolicyChoice(conflict, req.resolution)
            }
        }
        bumpAndRecompute()
        ResolutionResult.Ok(revision)
    }

    private fun applyPolicyChoice(conflict: Conflict, resolution: Resolution.ChoosePolicy) {
        val detail = conflict.detail as ConflictDetail.MissingPolicy
        val newPolicies = policies.copy(
            version = policies.version + 1,
            arrayPolicies = policies.arrayPolicies + (detail.pathKey to resolution.policy),
            idField = resolution.idKey?.let { policies.idField + (detail.pathKey to it) } ?: policies.idField,
        )
        policies = newPolicies
    }

    /** 从磁盘历史注入裁决（不增加 revision，随后统一重算一次）。 */
    fun injectLoaded(decision: StoredDecision) {
        lock.write {
            decisionHistory.add(decision)
        }
    }

    fun recomputeAfterLoad() {
        lock.write {
            // 历史已注入，根据指纹重新自动套用
            revision = decisionHistory.size.toLong().coerceAtLeast(revision)
            document = recomputeLocked(applyDecisions = true)
        }
    }

    private fun bumpAndRecompute() {
        revision += 1
        document = recomputeLocked(applyDecisions = true)
    }

    private fun recomputeLocked(applyDecisions: Boolean): MergeDocument {
        val base = runCatching { baseInput.parse() }.getOrNull()
        val a = aInput.parse()
        val b = bInput.parse()
        val doc = MergeEngine(policies).merge(base, a, b)
        val fps = doc.fingerprints

        // 自动套用指纹匹配的裁决；失配的挂为建议。
        val enriched = doc.conflicts.map { conflict ->
            val stored = findReplayable(conflict, fps)
            when {
                stored != null -> {
                    val resolution = stored.resolution.toResolution(conflict)
                    conflict.copy(resolution = resolution)
                }
                else -> {
                    val suggestion = findSuggestion(conflict, fps)
                    if (suggestion != null) conflict.copy(suggested = suggestion) else conflict
                }
            }
        }
        var resolvedDoc = doc.copy(conflicts = enriched)
        if (enriched.any { it.resolved != null }) {
            resolvedDoc = ResolutionApplier.applyAll(resolvedDoc)
        }
        return resolvedDoc
    }

    private fun findReplayable(conflict: Conflict, fps: Triple<String, String, String>): StoredDecision? {
        // 优先按当前已接受的 id 命中（同一内容下 id 本身是确定性的）
        accepted[conflict.id]?.let { return it }
        return decisionHistory.lastOrNull { d ->
            d.conflictType == conflict.type &&
                d.path == conflict.path.toString() &&
                d.baseFingerprint == fps.first &&
                d.aFingerprint == fps.second &&
                d.bFingerprint == fps.third &&
                d.policyVersion == policies.version
        }
    }

    private fun findSuggestion(conflict: Conflict, fps: Triple<String, String, String>): StoredDecision? =
        decisionHistory.lastOrNull { d ->
            d.conflictType == conflict.type &&
                d.path == conflict.path.toString() &&
                !(d.baseFingerprint == fps.first &&
                    d.aFingerprint == fps.second &&
                    d.bFingerprint == fps.third &&
                    d.policyVersion == policies.version)
        }

    sealed class ResolutionResult {
        data class Ok(val revision: Long) : ResolutionResult()
        data class Conflict(val currentRevision: Long, val alreadyResolved: List<String>) : ResolutionResult()
        data class NotFound(val conflictId: String) : ResolutionResult()
    }
}

data class ResolutionRequest(
    val conflictId: String,
    val resolution: Resolution,
    val reason: String = "",
)

data class SessionSnapshot(
    val id: String,
    val revision: Long,
    val policies: PolicyRegistry,
    val baseInput: MergeSession.InputDoc,
    val aInput: MergeSession.InputDoc,
    val bInput: MergeSession.InputDoc,
    val document: MergeDocument,
    val decisions: List<StoredDecision>,
    val accepted: Map<String, StoredDecision>,
)
