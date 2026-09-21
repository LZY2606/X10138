package merger

import java.time.Instant

internal class Ctx(val input: MergeInput, val at: Instant) {
    val baseFp = input.base.fingerprint
    val aFp = input.a.fingerprint
    val bFp = input.b.fingerprint

    // 同一逻辑冲突路径可能在重放时有多条历史裁决，取最近一条；强绑定裁决优先。
    private val byPath: Map<String, List<Decision>> =
        input.decisions.sortedByDescending { it.createdAt }.groupBy { it.path }

    fun decisionFor(path: String, kind: ConflictKind): Pair<Decision?, Boolean?> {
        val list = byPath[path] ?: return null to null
        val matching = list.firstOrNull { it.conflictKind == kind } ?: list.first()
        val binds = matching.binds(baseFp, aFp, bFp)
        return matching to binds
    }
}

internal fun mergeNode(
    path: Path,
    label: String,
    base: SNode?,
    a: SNode?,
    b: SNode?,
    ctx: Ctx
): MergeNode {
    val aMissing = a.isMissing
    val bMissing = b.isMissing
    val baseMissing = base.isMissing

    // 1) 三方结构相等 -> 自动
    if (Merger.semEq(base, a) && Merger.semEq(base, b)) {
        return autoNode(path, label, base, a, b, a ?: base, ctx,
            "三方一致" + if (baseMissing) "（均缺失）" else "")
    }

    // 2) 无意见方（缺失）不参与竞争：只要另一方“在场”，直接采用在场方。
    //    但删除墓碑是一种明确动作，和 null 一样都“在场”，不能走这条捷径。
    val aOpinion = !aMissing
    val bOpinion = !bMissing
    if (aOpinion && !bOpinion) {
        return autoNode(path, label, base, a, b, a, ctx, summarizeSide(MergeSide.A, base, a))
    }
    if (bOpinion && !aOpinion) {
        return autoNode(path, label, base, a, b, b, ctx, summarizeSide(MergeSide.B, base, b))
    }

    val aChanged = !Merger.semEq(base, a)
    val bChanged = !Merger.semEq(base, b)

    // 3) 两边结果相同（含独立改出同值）
    if (Merger.semEq(a, b)) {
        return autoNode(path, label, base, a, b, a, ctx,
            if (aChanged || bChanged) "两边独立修改为相同结果" else "三方一致")
    }
    if (aChanged && !bChanged) {
        return autoNode(path, label, base, a, b, a, ctx, summarizeSide(MergeSide.A, base, a))
    }
    if (bChanged && !aChanged) {
        return autoNode(path, label, base, a, b, b, ctx, summarizeSide(MergeSide.B, base, b))
    }

    // 4) 结构递归：两边都是对象（或与祖先同为对象）
    if ((a is SMap || a.isMissing) && (b is SMap || b.isMissing) &&
        (base == null || base is SSMissing || base is SMap) &&
        (a is SMap || b is SMap)
    ) {
        return mergeMap(path, label, base as? SMap, a as? SMap, b as? SMap, ctx)
    }

    // 5) 数组：需要策略
    if (isArrayTriple(base, a, b)) {
        return mergeArrayDispatch(path, label, (base as? SSeq) ?: SSMissing.INSTANCE, (a as? SSeq) ?: SSMissing.INSTANCE, (b as? SSeq) ?: SSMissing.INSTANCE, ctx)
    }

    // 6) 真冲突（叶子值/删除-null/类型）
    return leafConflict(path, label, base, a, b, ctx)
}

internal fun isArrayTriple(base: SNode?, a: SNode?, b: SNode?): Boolean {
    fun ok(n: SNode?) = n == null || n is SSMissing || n is SSeq
    return ok(base) && ok(a) && ok(b) && (a is SSeq || b is SSeq)
}

internal fun autoNode(
    path: Path, label: String, base: SNode?, a: SNode?, b: SNode?,
    result: SNode?, ctx: Ctx, summary: String,
    extraProv: List<ProvenanceStep> = emptyList()
): MergeNode {
    val prov = buildList {
        // 结果来自哪一方：与 A 的在场值相同记 A，与 B 的在场值相同记 B；
        // 两边相同则都记；都不匹配时回退到共同祖先。
        val aContributes = !a.isMissing && Merger.semEq(a, result)
        val bContributes = !b.isMissing && Merger.semEq(b, result)
        if (aContributes) add(stepFor(MergeSide.A, path, base, a, result, ctx))
        if (bContributes) add(stepFor(MergeSide.B, path, base, b, result, ctx))
        if (isEmpty()) add(ProvenanceStep(MergeSide.BASE, path.render(), "共同祖先", anchorOf(ctx, MergeSide.BASE, base)))
    }.distinct()
    return MergeNode(
        path = path.render(), keyLabel = label, status = MergeStatus.AUTO,
        base = base, a = a, b = b, result = result,
        provenance = (prov + extraProv).distinct(), autoSummary = summary
    )
}

internal fun stepFor(side: MergeSide, path: Path, old: SNode?, sideVal: SNode?, result: SNode?, ctx: Ctx): ProvenanceStep {
    val action = describeOp(old, sideVal)
    val anchor = anchorOf(ctx, side, sideVal)
    return ProvenanceStep(side, path.render(), action, anchor)
}

internal fun anchorOf(ctx: Ctx, side: MergeSide, node: SNode?): String? {
    val doc = when (side) { MergeSide.BASE -> ctx.input.base; MergeSide.A -> ctx.input.a; MergeSide.B -> ctx.input.b }
    return doc.metaOf(node).firstOrNull { it.viaAlias }?.name ?: doc.metaOf(node).firstOrNull()?.name
}

internal fun describeOp(old: SNode?, new: SNode?): String = when {
    old.isMissing && !new.isMissing -> "新增"
    old.isDelete && !new.isDelete && !new.isMissing -> "恢复删除项并赋值"
    new.isDelete -> "删除"
    new.isMissing -> "未改动（缺失）"
    new.isNullValue && !old.isNullValue -> "显式设为 null"
    else -> "修改"
}

internal fun summarizeSide(side: MergeSide, old: SNode?, new: SNode?): String =
    "${cn(side)}：${describeOp(old, new)}"

internal fun cn(side: MergeSide): String = when (side) {
    MergeSide.BASE -> "共同祖先"
    MergeSide.A -> "分支 A"
    MergeSide.B -> "分支 B"
}

internal fun leafConflict(
    path: Path, label: String, base: SNode?, a: SNode?, b: SNode?, ctx: Ctx
): MergeNode {
    val kind = when {
        (a.isDelete && b.isNullValue) || (b.isDelete && a.isNullValue) -> ConflictKind.DELETE_NULL
        Merger.kind(a) != Merger.kind(b) || Merger.kind(base).let {
            // 容器与标量相撞归类型冲突
            (a is SMap || b is SMap || a is SSeq || b is SSeq) &&
                !(a is SMap && b is SMap) && !(a is SSeq && b is SSeq)
        } -> ConflictKind.TYPE_MISMATCH
        else -> ConflictKind.VALUE
    }
    val reason = when (kind) {
        ConflictKind.DELETE_NULL -> "一边执行删除，另一边把同一路径显式设为 null：删除与置空是两种不同动作，不能静默选择。"
        ConflictKind.TYPE_MISMATCH -> "三方在该路径的结构类型不一致：祖先=${Merger.kind(base)}，A=${Merger.kind(a)}，B=${Merger.kind(b)}。"
        else -> "两边都修改了该值且结果不同。"
    }
    val choices = listOf(
        Choice("A", "采用分支 A", preview(a)),
        Choice("B", "采用分支 B", preview(b)),
        Choice("BASE", "保留共同祖先", preview(base))
    )
    val conflict = Conflict(kind, reason, choices, path.render())
    return buildConflictNode(path, label, base, a, b, conflict, ctx)
}

internal fun preview(n: SNode?): String = when (n) {
    null, is SSMissing -> "<缺失>"
    is SSDelete -> "<删除墓碑>"
    else -> Emitter.toJson(n, pretty = false).trim().let { if (it.length > 120) it.take(117) + "..." else it }
}
