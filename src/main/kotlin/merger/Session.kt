package merger

import java.time.Instant

data class InputDoc(val format: Format, val raw: String, val fingerprint: String)

data class Session(
    val id: String,
    val createdAt: Instant,
    var title: String,
    var base: InputDoc?,
    var a: InputDoc?,
    var b: InputDoc?,
    var registry: StrategyRegistry,
    var outputFormat: Format,
    /** 乐观版本：任何结构性变更（输入/策略/新增裁决）都递增。 */
    var version: Int,
    val decisions: MutableList<Decision> = mutableListOf(),
    var lastOutcome: MergeOutcome? = null
)

data class DecisionResult(
    val accepted: List<String>,
    val rejected: List<RejectedItem>,
    val newVersion: Int
)

data class RejectedItem(
    val path: String,
    val reason: String,
    val currentResolver: String?
)

data class SubmitRequest(
    val baseVersion: Int,
    val author: String,
    val items: List<SubmitItem>
)

data class SubmitItem(
    val path: String,
    val choiceId: String,
    val customText: String?,
    val customFormat: Format?
)

data class Bundle(
    val kind: String,
    val version: Int,
    val exportedAt: Instant,
    val session: Session,
    val inputs: Triple<InputDoc, InputDoc, InputDoc>,
    val materialized: SNode,
    val exported: String,
    val resultFingerprint: String,
    val strategyVersion: Int
)
