package merger

import java.time.Instant

/**
 * 可重放的人工裁决记录。
 *
 * 裁决绑定 [baseFingerprint]/[aFingerprint]/[bFingerprint] 与策略版本：
 * 三边内容或策略任一变化，旧裁决不会自动套用，只会作为建议挂到
 * 同路径/同类型的新冲突上（见 MergeSession 的匹配逻辑）。
 */
data class StoredDecision(
    val id: String,
    val conflictType: ConflictType,
    val path: String,
    val baseFingerprint: String,
    val aFingerprint: String,
    val bFingerprint: String,
    val policyVersion: Int,
    val resolution: DecisionPayload,
    val reason: String,
    val createdAt: String,
)

/** 裁决内容的可持久化表示（节点以规范 JSON 保存，避免依赖具体类）。 */
sealed class DecisionPayload {
    data class TakeSide(val side: String) : DecisionPayload()
    data class SetJson(val text: String, val format: ConfigFormat) : DecisionPayload()
    data object Delete : DecisionPayload()
    data class TakeHunk(val side: String) : DecisionPayload()
    data class CustomHunk(val text: String, val format: ConfigFormat) : DecisionPayload()
    data class TakeOrder(val side: String) : DecisionPayload()
    data class CustomOrder(val ids: List<String>) : DecisionPayload()
    data class ChoosePolicy(val policy: String, val idKey: String?) : DecisionPayload()

    fun toResolution(conflict: Conflict): Resolution = when (this) {
        is TakeSide -> Resolution.Take(Side.parse(side) ?: error("未知边: $side"))
        is SetJson -> Resolution.SetValue(ConfigIO.parse(text, format, "<decision>"))
        is Delete -> Resolution.Delete
        is TakeHunk -> Resolution.TakeHunk(Side.parse(side)!!)
        is CustomHunk -> {
            val node = ConfigIO.parse(text, format, "<decision-hunk>")
            Resolution.CustomHunk((node as Node.Arr).items)
        }
        is TakeOrder -> Resolution.TakeOrder(Side.parse(side)!!)
        is CustomOrder -> Resolution.CustomOrder(ids)
        is ChoosePolicy -> Resolution.ChoosePolicy(ArrayPolicy.of(policy)!!, idKey)
    }

    companion object {
        fun from(resolution: Resolution): DecisionPayload = when (resolution) {
            is Resolution.Take -> TakeSide(resolution.side.name)
            is Resolution.SetValue -> SetJson(JsonWriter.write(resolution.value), ConfigFormat.JSON)
            is Resolution.Delete -> Delete
            is Resolution.TakeHunk -> TakeHunk(resolution.side.name)
            is Resolution.CustomHunk -> CustomHunk(
                JsonWriter.write(Node.Arr(resolution.nodes.toMutableList(), Origin.SYNTHETIC)),
                ConfigFormat.JSON,
            )
            is Resolution.TakeOrder -> TakeOrder(resolution.side.name)
            is Resolution.CustomOrder -> CustomOrder(resolution.ids)
            is Resolution.ChoosePolicy -> ChoosePolicy(resolution.policy.code, resolution.idKey)
        }
    }
}

object DecisionMaker {
    fun newId(): String = "dec-" + Hash.sha256Hex(Instant.now().toEpochMilli().toString() + "-" + System.nanoTime()).take(16)
}
