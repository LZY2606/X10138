package merger

private data class IdItem(val id: String, val node: SMap)

internal fun mergeById(
    path: Path,
    label: String,
    base: SSeq?,
    a: SSeq?,
    b: SSeq?,
    ctx: Ctx,
    idField: String
): MergeNode {
    // 1) 结构校验：元素必须是对象且含稳定 id 字段
    val schemaBad = listOfNotNull(
        validateSchema(base, MergeSide.BASE, idField),
        validateSchema(a, MergeSide.A, idField),
        validateSchema(b, MergeSide.B, idField)
    )
    if (schemaBad.isNotEmpty()) {
        val conflict = Conflict(
            ConflictKind.ARRAY_ID_SCHEMA,
            "策略=按稳定 id（字段 $idField），但：" + schemaBad.joinToString("；"),
            listOf(
                Choice("A", "整组采用分支 A", preview(a)),
                Choice("B", "整组采用分支 B", preview(b)),
                Choice("BASE", "整组保留祖先", preview(base))
            ),
            path.render()
        )
        return buildConflictNode(path, label, base, a, b, conflict, ctx)
            .let { it.copy(status = MergeStatus.BLOCKED) }
    }

    // 2) 重复 id 检测（同一侧内部）
    val dupA = findDupIds(a, idField)
    val dupB = findDupIds(b, idField)
    val children = LinkedHashMap<String, MergeNode>()
    var blocked = false
    var baseItems = extract(base, idField)
    var aItems = extract(a, idField)
    var bItems = extract(b, idField)

    if (dupA.isNotEmpty() || dupB.isNotEmpty()) {
        val sides = mutableListOf<String>()
        if (dupA.isNotEmpty()) sides.add("A 重复 id: ${dupA.joinToString()}")
        if (dupB.isNotEmpty()) sides.add("B 重复 id: ${dupB.joinToString()}")
        val conflict = Conflict(
            ConflictKind.DUP_ID,
            "同一侧数组出现重复稳定 id（${sides.joinToString("；")}）。选择一侧后，将保留该侧每个 id 的首次出现项继续合并。",
            listOf(
                Choice("A", "重复时以分支 A 首次出现为准", preview(a)),
                Choice("B", "重复时以分支 B 首次出现为准", preview(b)),
                Choice("BASE", "回退到祖先数组", preview(base))
            ),
            path.render()
        )
        val dupNode = buildConflictNode(path, label, base, a, b, conflict, ctx)
        children["dup-id"] = dupNode
        if (dupNode.resolvedBy == null) {
            blocked = true
        } else {
            val winner = dupNode.resolvedBy!!.choiceId
            if (winner == "BASE") {
                aItems = baseItems; bItems = baseItems
            } else {
                aItems = dedupe(if (winner == "A") aItems else aItems)
                bItems = dedupe(if (winner == "B") bItems else bItems)
                if (winner == "A") bItems = dedupe(bItems) else aItems = dedupe(aItems)
            }
        }
    } else {
        aItems = dedupe(aItems)
        bItems = dedupe(bItems)
        baseItems = dedupe(baseItems)
    }

    // 3) 顺序：三方 id 序列合并（含移动与新增）
    var order = OrderMerge.merge(baseItems.map { it.id }, aItems.map { it.id }, bItems.map { it.id })
    var orderConflict = !blocked && order is OrderMerge.Result.Conflict
    if (orderConflict) {
        children["order"] = orderConflictNode(path, baseItems, aItems, bItems, ctx)
    }
    // 顺序冲突存在但已被强绑定裁决解决：按所选边的 id 顺序作为 Auto 结果
    val orderNode = children["order"]
    if (order is OrderMerge.Result.Conflict && orderNode?.resolvedBy != null) {
        val winner = orderNode.resolvedBy!!.choiceId
        val winningIds = when (winner) {
            "A" -> aItems.map { it.id }
            "B" -> bItems.map { it.id }
            else -> baseItems.map { it.id }
        }.distinct()
        order = OrderMerge.Result.Auto(winningIds)
        orderConflict = false
    }

    // 4) 每个 id 的三方内容合并
    val byIdBase = baseItems.associateBy { it.id }
    val byIdA = aItems.associateBy { it.id }
    val byIdB = bItems.associateBy { it.id }
    val allIds = linkedSetOf<String>()
    baseItems.forEach { allIds.add(it.id) }
    aItems.forEach { allIds.add(it.id) }
    bItems.forEach { allIds.add(it.id) }

    for (id in allIds) {
        val childPath = path.child(PathSeg.Id(idField, id))
        val child = mergeNode(
            childPath, "id=$id",
            byIdBase[id]?.node ?: SSMissing.INSTANCE,
            byIdA[id]?.node ?: SSMissing.INSTANCE,
            byIdB[id]?.node ?: SSMissing.INSTANCE,
            ctx
        )
        children["id:$id"] = child
        if (child.status == MergeStatus.CONFLICT || child.status == MergeStatus.BLOCKED) blocked = true
    }

    // 5) 物化顺序
    val orderStillOpen = orderNode?.resolvedBy == null &&
        (children["order"]?.conflict != null)
    val unresolved = blocked || orderConflict || orderStillOpen
    val result = if (!unresolved) {
        val auto = order as OrderMerge.Result.Auto
        val orderIds = auto.merged
        val byChild = children.filterKeys { it.startsWith("id:") }
        SSeq(orderIds.mapNotNull { id ->
            val node = byChild["id:$id"] ?: return@mapNotNull null
            when (val r = node.result) {
                null, is SSMissing, is SSDelete -> null
                else -> r
            }
        })
    } else null

    return MergeNode(
        path = path.render(), keyLabel = label,
        status = if (unresolved) MergeStatus.ARRAY else MergeStatus.ARRAY,
        base = base, a = a, b = b, result = result,
        children = children,
        provenance = aggregateProvenance(children.values),
        autoSummary = if (!unresolved) "按稳定 id 合并完成，结果 ${result?.items?.size ?: 0} 项" else null
    )
}

private fun validateSchema(seq: SSeq?, side: MergeSide, idField: String): String? {
    if (seq == null) return null
    seq.items.forEachIndexed { i, item ->
        val idv = (item as? SMap)?.get(idField)
        if (item !is SMap || (idv !is SSString && idv !is SSNumber)) {
            return "${cn(side)} 第 ${i + 1} 项不是对象或缺少标量 id 字段 $idField"
        }
    }
    return null
}

internal fun idOf(m: SMap, idField: String): String? = when (val v = m.get(idField)) {
    is SSString -> v.value
    is SSNumber -> Canonical.normalizeNumber(v.value)
    else -> null
}

private fun findDupIds(seq: SSeq?, idField: String): List<String> {
    if (seq == null) return emptyList()
    val seen = HashSet<String>()
    val dups = linkedSetOf<String>()
    for (item in seq.items) {
        val id = (item as? SMap)?.let { idOf(it, idField) } ?: continue
        if (!seen.add(id)) dups.add(id)
    }
    return dups.toList()
}

private fun extract(seq: SSeq?, idField: String): List<IdItem> =
    seq?.items?.mapNotNull { item ->
        val m = item as? SMap ?: return@mapNotNull null
        val id = idOf(m, idField) ?: return@mapNotNull null
        IdItem(id, m)
    } ?: emptyList()

private fun dedupe(items: List<IdItem>): List<IdItem> {
    val seen = HashSet<String>()
    return items.filter { seen.add(it.id) }
}

private fun findOrderDecision(path: Path, ctx: Ctx): String? {
    val (d, binds) = ctx.decisionFor(path.child(PathSeg.Any("order")).render(), ConflictKind.ARRAY_ORDER)
    return if (d != null && binds == true) d.choiceId else null
}

private fun orderConflictNode(
    path: Path, base: List<IdItem>, a: List<IdItem>, b: List<IdItem>, ctx: Ctx
): MergeNode {
    val op = path.child(PathSeg.Any("order"))
    val conflict = Conflict(
        ConflictKind.ARRAY_ORDER,
        "同一组元素在两边被移动到不同相对位置，或插入位置互不相让，需要选择采用哪一侧的整体顺序（元素内容仍按逐项合并结果）。",
        listOf(
            Choice("A", "顺序采用分支 A", a.joinToString(",") { it.id }),
            Choice("B", "顺序采用分支 B", b.joinToString(",") { it.id }),
            Choice("BASE", "顺序保留祖先", base.joinToString(",") { it.id })
        ),
        op.render()
    )
    return buildConflictNode(
        op, "元素顺序",
        SSeq(base.map { it.node }), SSeq(a.map { it.node }), SSeq(b.map { it.node }),
        conflict, ctx
    )
}
