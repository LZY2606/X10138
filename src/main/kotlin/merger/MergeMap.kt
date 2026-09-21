package merger

internal fun mergeMap(
    path: Path,
    label: String,
    base: SMap?,
    a: SMap?,
    b: SMap?,
    ctx: Ctx
): MergeNode {
    val keys = linkedSetOf<String>()
    base?.let { keys.addAll(it.order) }
    a?.let { keys.addAll(it.order) }
    b?.let { keys.addAll(it.order) }

    val children = LinkedHashMap<String, MergeNode>()
    var hasConflict = false
    var hasBlocked = false
    val resultEntries = LinkedHashMap<String, SNode>()

    for (key in keys) {
        val bv = base?.get(key) ?: SSMissing.INSTANCE
        val av = a?.get(key) ?: SSMissing.INSTANCE
        val bvv = b?.get(key) ?: SSMissing.INSTANCE
        val childPath = path.child(PathSeg.Key(key))
        val child = mergeNode(childPath, key, bv, av, bvv, ctx)
        children[key] = child
        if (child.status == MergeStatus.CONFLICT) hasConflict = true
        if (child.status == MergeStatus.BLOCKED) hasBlocked = true
        child.result?.let { resultEntries[key] = it }
    }

    val prov = aggregateProvenance(children.values)
    val status = when {
        hasBlocked -> MergeStatus.BLOCKED
        hasConflict -> MergeStatus.MAP
        else -> MergeStatus.MAP
    }
    val result = SMap(resultEntries)
    return MergeNode(
        path = path.render(), keyLabel = label, status = status,
        base = base, a = a, b = b, result = result,
        children = children, provenance = prov,
        autoSummary = if (!hasConflict && !hasBlocked) "对象按键名合并，共 ${keys.size} 项" else null
    )
}

internal fun aggregateProvenance(nodes: Iterable<MergeNode>): List<ProvenanceStep> {
    // 汇总直接自动/裁决来源，最多保留去重后的代表性记录，避免整树爆炸。
    return nodes.flatMap { node ->
        if (node.children.isEmpty()) node.provenance else emptyList()
    }.distinct().take(40)
}

internal fun mergeArrayDispatch(
    path: Path,
    label: String,
    base: SNode?,
    a: SNode?,
    b: SNode?,
    ctx: Ctx
): MergeNode {
    val bs = base as? SSeq
    val asq = a as? SSeq
    val bsq = b as? SSeq
    val aChanged = !Merger.semEq(bs, asq)
    val bChanged = !Merger.semEq(bs, bsq)
    // 一边未动：直接采用另一边（无需登记策略）
    if (aChanged && !bChanged) {
        return autoNode(path, label, base, a, b, asq, ctx, summarizeSide(MergeSide.A, base, a))
    }
    if (bChanged && !aChanged) {
        return autoNode(path, label, base, a, b, bsq, ctx, summarizeSide(MergeSide.B, base, b))
    }
    if (Merger.semEq(asq, bsq)) {
        return autoNode(path, label, base, a, b, asq, ctx, "两边数组修改一致")
    }
    val entry = ctx.input.registry.lookup(path)
    if (entry == null) {
        val conflict = Conflict(
            kind = ConflictKind.NO_STRATEGY,
            reason = "数组路径 ${path.render()} 未登记合并策略，按要求不猜测。请在策略面板选择替换 / 按稳定 id 合并 / 有序序列后重新合并。",
            choices = emptyList(),
            path = path.render()
        )
        return buildConflictNode(path, label, base, a, b, conflict, ctx)
            .let { it.copy(status = MergeStatus.BLOCKED) }
    }
    return when (entry.strategy) {
        ArrayStrategy.REPLACE -> mergeReplace(path, label, bs, asq, bsq, ctx)
        ArrayStrategy.ID -> mergeById(path, label, bs, asq, bsq, ctx, entry.idField)
        ArrayStrategy.SEQUENCE -> mergeSequence(path, label, bs, asq, bsq, ctx)
    }
}

internal fun mergeReplace(
    path: Path, label: String, base: SSeq?, a: SSeq?, b: SSeq?, ctx: Ctx
): MergeNode {
    val conflict = Conflict(
        ConflictKind.VALUE,
        "策略=替换：两边都整体替换了数组且内容不同，需要选择保留哪一侧的整个数组。",
        listOf(
            Choice("A", "采用分支 A 的整个数组", preview(a)),
            Choice("B", "采用分支 B 的整个数组", preview(b)),
            Choice("BASE", "保留祖先数组", preview(base))
        ),
        path.render()
    )
    return buildConflictNode(path, label, base, a, b, conflict, ctx)
}
