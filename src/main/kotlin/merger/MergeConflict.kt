package merger

import java.time.Instant

internal fun buildConflictNode(
    path: Path,
    label: String,
    base: SNode?,
    a: SNode?,
    b: SNode?,
    conflict: Conflict,
    ctx: Ctx
): MergeNode {
    val (decision, binds) = ctx.decisionFor(path.render(), conflict.kind)
    val advisory = if (decision != null && binds == false) {
        Advisory(decision.id, decision.choiceId,
            "裁决 ${decision.id} 绑定的内容指纹与当前祖先/分支不一致，仅作为建议，需人工再次确认。")
    } else null

    if (decision != null && binds == true) {
        val chosen = resolveChoice(decision, base, a, b)
        if (chosen != null) {
            val (chosenNode, sides) = chosen
            val prov = provenanceForChoice(decision, path, chosenNode, sides, ctx)
            return MergeNode(
                path = path.render(), keyLabel = label, status = MergeStatus.CONFLICT,
                base = base, a = a, b = b, result = chosenNode,
                provenance = prov, conflict = conflict,
                resolvedBy = ResolvedBy(
                    decision.id, decision.choiceId, decision.createdAt,
                    custom = decision.choiceId == "CUSTOM"
                )
            )
        }
    }
    return MergeNode(
        path = path.render(), keyLabel = label, status = MergeStatus.CONFLICT,
        base = base, a = a, b = b, result = null,
        conflict = conflict, advisory = advisory
    )
}

internal fun resolveChoice(
    decision: Decision, base: SNode?, a: SNode?, b: SNode?
): Pair<SNode?, List<MergeSide>>? = when (decision.choiceId) {
    "A" -> a to listOf(MergeSide.A)
    "B" -> b to listOf(MergeSide.B)
    "BASE" -> base to listOf(MergeSide.BASE)
    "CUSTOM" -> {
        val text = decision.customText ?: return null
        try {
            ConfigParser.parse(text, Source.A, decision.customFormat).root to listOf(MergeSide.A)
        } catch (e: Exception) {
            null
        }
    }
    else -> null
}

internal fun provenanceForChoice(
    decision: Decision, path: Path,
    chosenNode: SNode?, sides: List<MergeSide>, ctx: Ctx
): List<ProvenanceStep> {
    val base = ctx.input.base.root // 仅用于动作描述的占位；具体三方值在节点上
    val steps = sides.map { side ->
        val action = if (decision.choiceId == "CUSTOM") "人工裁决（自定义文本）"
        else "人工裁决采用${cn(side)}"
        ProvenanceStep(side, path.render(), action, anchor = null)
    }
    return steps + ProvenanceStep(
        MergeSide.A, path.render(),
        "裁决记录 ${decision.id}，创建于 ${decision.createdAt}，作者 ${decision.author}",
        null
    )
}
