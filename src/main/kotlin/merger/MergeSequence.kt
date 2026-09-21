package merger

internal fun mergeSequence(
    path: Path,
    label: String,
    base: SSeq?,
    a: SSeq?,
    b: SSeq?,
    ctx: Ctx
): MergeNode {
    val baseList = base?.items ?: emptyList()
    val aList = a?.items ?: emptyList()
    val bList = b?.items ?: emptyList()
    val chunks = Diff3.diff3(baseList, aList, bList) { x, y -> Merger.semEq(x, y) }

    val children = LinkedHashMap<String, MergeNode>()
    val resultItems = mutableListOf<SNode>()
    var unresolved = false
    var regionIndex = 0

    // 为了让冲突区内每个下标仍可逐项查看，冲突区生成占位节点，裁决作用于整段。
    for (chunk in chunks) {
        when (chunk) {
            is Diff3.Chunk.Same -> {
                // 自动区：以 A 侧为准（只有 A 变时 A 是新值；三边一致时三方相同）
                val auto = if (chunk.a.isNotEmpty()) chunk.a else chunk.b
                auto.forEachIndexed { i, item ->
                    val idx = resultItems.size + i
                    val p = path.child(PathSeg.Index(idx))
                    val node = autoNode(
                        p, "[$idx]",
                        chunk.base.getOrNull(i), chunk.a.getOrNull(i), chunk.b.getOrNull(i),
                        item, ctx, "有序序列自动区"
                    )
                    children["idx:$idx"] = node
                    resultItems.add(item)
                }
            }
            is Diff3.Chunk.Conflict -> {
                val start = resultItems.size
                val regionPath = path.child(PathSeg.Any("region=$regionIndex"))
                val regionResultNode = regionNode(
                    regionPath, "冲突段 #$regionIndex（下标 $start 起）",
                    chunk, ctx
                )
                children["region:$regionIndex"] = regionResultNode
                if (regionResultNode.resolvedBy == null) unresolved = true
                regionResultNode.result?.let { res ->
                    if (res is SSeq) resultItems.addAll(res.items)
                }
                regionIndex++
            }
        }
    }

    val prov = aggregateProvenance(children.values)
    val result = if (unresolved) null else SSeq(resultItems)
    val status = if (unresolved) MergeStatus.ARRAY else MergeStatus.ARRAY
    return MergeNode(
        path = path.render(), keyLabel = label, status = status,
        base = base, a = a, b = b, result = result,
        children = children, provenance = prov,
        autoSummary = if (!unresolved) "有序序列合并完成，共 ${resultItems.size} 项" else null
    )
}

private fun regionNode(
    path: Path,
    label: String,
    chunk: Diff3.Chunk.Conflict<SNode>,
    ctx: Ctx
): MergeNode {
    val baseSeq = SSeq(chunk.base)
    val aSeq = SSeq(chunk.a)
    val bSeq = SSeq(chunk.b)
    val conflict = Conflict(
        ConflictKind.SEQUENCE_REGION,
        "有序序列在同一段落两边都发生了不同改动（祖先 ${chunk.base.size} 项 / A ${chunk.a.size} 项 / B ${chunk.b.size} 项）。",
        listOf(
            Choice("A", "整段采用分支 A", preview(aSeq)),
            Choice("B", "整段采用分支 B", preview(bSeq)),
            Choice("BASE", "整段保留祖先", preview(baseSeq))
        ),
        path.render()
    )
    val node = buildConflictNode(path, label, baseSeq, aSeq, bSeq, conflict, ctx)
    return if (node.resolvedBy != null) node else node
}
