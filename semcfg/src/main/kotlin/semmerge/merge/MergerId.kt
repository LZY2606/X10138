package semmerge.merge

import semmerge.iof.Fingerprint
import semmerge.model.Path
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.model.structuralEqual

private data class IdEntry(val id: String, val nodes: List<SNode>, val dup: Boolean)

private fun extractId(node: SNode, field: String): String? {
    val v = (node as? SMap)?.get(field) ?: return null
    val s = v as? SScalar ?: return null
    return if (s.kind == ScalarKind.STRING || s.kind == ScalarKind.INT) s.text else null
}

private fun groupIds(list: SList, field: String): Pair<LinkedHashMap<String, IdEntry>, List<Int>> {
    val map = LinkedHashMap<String, MutableList<SNode>>()
    val missing = mutableListOf<Int>()
    list.items.forEachIndexed { i, n ->
        val id = extractId(n, field)
        if (id == null) missing += i else map.getOrPut(id) { mutableListOf() }.add(n)
    }
    val entries = LinkedHashMap<String, IdEntry>()
    for ((id, nodes) in map) entries[id] = IdEntry(id, nodes, nodes.size > 1)
    return entries to missing
}

internal fun Merger.mergeById(path: Path, b: SList, aIn: SList, cIn: SList, field: String): MNode {
    val (bGroups, bMissing) = groupIds(b, field)
    val (aGroups, aMissing) = groupIds(aIn, field)
    val (cGroups, cMissing) = groupIds(cIn, field)

    val allIds = linkedSetOf<String>()
    allIds.addAll(bGroups.keys); allIds.addAll(aGroups.keys); allIds.addAll(cGroups.keys)

    data class ItemResult(val id: String, val node: MNode, val order: ItemOrder)
    val itemResults = mutableListOf<ItemResult>()

    for (id in allIds) {
        val be = bGroups[id]
        val ae = aGroups[id]
        val ce = cGroups[id]
        val itemPath = path.id(id)

        val dupConflict = dupConflictOrNull(path, itemPath, id, be, ae, ce)
        if (dupConflict != null) {
            recordConflict(dupConflict)
            val match = decide(dupConflict)
            val default = (ae ?: ce ?: be)!!.nodes.first()
            val node: MNode = if (match?.applicable == true) {
                applyResolution(itemPath, match.record.resolution,
                    be?.nodes?.firstOrNull(), ae?.nodes?.firstOrNull(), ce?.nodes?.firstOrNull(),
                    Fingerprint.of(be?.nodes?.firstOrNull()),
                    Fingerprint.of(ae?.nodes?.firstOrNull()),
                    Fingerprint.of(ce?.nodes?.firstOrNull()),
                    dupConflict, null)
            } else {
                MValue(itemPath, default,
                    Fingerprint.of(be?.nodes?.firstOrNull()),
                    Fingerprint.of(ae?.nodes?.firstOrNull()),
                    Fingerprint.of(ce?.nodes?.firstOrNull()),
                    MergeStatus.CONFLICT,
                    Provenance(emptyList(), "重复 id: $id"), dupConflict, match?.let(::suggestionOf))
            }
            itemResults += ItemResult(id, node, ItemOrder.FROM_BASE)
            continue
        }

        val bNode = be?.nodes?.firstOrNull()
        val aNode = ae?.nodes?.firstOrNull()
        val cNode = ce?.nodes?.firstOrNull()
        val merged = mergeNode(itemPath, bNode, aNode, cNode)
        val order = when {
            ae != null && ce != null -> ItemOrder.FROM_BASE
            ae != null -> ItemOrder.FROM_A
            ce != null -> ItemOrder.FROM_B
            else -> ItemOrder.FROM_BASE
        }
        itemResults += ItemResult(id, merged, order)
    }

    // Elements without an id field are hard conflicts.
    val missingIdx = linkedSetOf<Int>()
    missingIdx.addAll(bMissing); missingIdx.addAll(aMissing); missingIdx.addAll(cMissing)
    for (idx in missingIdx) {
        val p = path.index(idx)
        val conflict = Conflict(conflictKey(p, ConflictKind.MISSING_ID), p, ConflictKind.MISSING_ID,
            "id 合并策略要求每个元素都有 '$field' 字段；第 ${idx + 1} 个元素缺失")
        recordConflict(conflict)
        val match = decide(conflict)
        val fallback = aIn.items.getOrNull(idx) ?: cIn.items.getOrNull(idx) ?: b.items.getOrNull(idx)
        val node = if (match?.applicable == true) {
            applyResolution(p, match.record.resolution, b.items.getOrNull(idx),
                aIn.items.getOrNull(idx), cIn.items.getOrNull(idx),
                Fingerprint.of(b.items.getOrNull(idx)),
                Fingerprint.of(aIn.items.getOrNull(idx)),
                Fingerprint.of(cIn.items.getOrNull(idx)), conflict, null)
        } else {
            MValue(p, fallback!!,
                Fingerprint.of(b.items.getOrNull(idx)),
                Fingerprint.of(aIn.items.getOrNull(idx)),
                Fingerprint.of(cIn.items.getOrNull(idx)),
                MergeStatus.CONFLICT, Provenance(emptyList(), "缺少稳定 id"), conflict,
                match?.let(::suggestionOf))
        }
        itemResults += ItemResult("#missing:$idx", node, ItemOrder.FROM_BASE)
    }

    // ---- ordering -------------------------------------------------------
    val bOrder = bGroups.keys.toList()
    val aOrder = aGroups.keys.toList()
    val cOrder = cGroups.keys.toList()
    val aChangedOrder = aOrder != bOrder
    val cChangedOrder = cOrder != bOrder

    var orderStatus = when {
        !aChangedOrder && !cChangedOrder -> ItemOrder.FROM_BASE
        aChangedOrder && !cChangedOrder -> ItemOrder.FROM_A
        !aChangedOrder && cChangedOrder -> ItemOrder.FROM_B
        aOrder == cOrder -> ItemOrder.FROM_A
        else -> ItemOrder.CONFLICT
    }

    var finalOrder: List<String>
    var orderConflictRec: Conflict? = null

    if (orderStatus != ItemOrder.CONFLICT) {
        // Honor unilateral adds and removals while using the chosen side's order.
        val ordered = when (orderStatus) {
            ItemOrder.FROM_A -> aOrder
            ItemOrder.FROM_B -> cOrder
            else -> bOrder
        }
        val survivors = itemResults.asSequence()
            .filter { it.id.startsWith("#").not() }
            .filter { idPresentInChosen(it.id, orderStatus, bGroups, aGroups, cGroups) }
            .map { it.id }
            .toList()
        finalOrder = mergeSurvivors(ordered, survivors, bGroups, aGroups, cGroups, orderStatus)
    } else {
        val msg = "两个分支以不同方式重排了相同的元素集合"
        orderConflictRec = Conflict(conflictKey(path, ConflictKind.ORDER), path, ConflictKind.ORDER, msg)
        recordConflict(orderConflictRec)
        val match = decide(orderConflictRec)
        val resolvedOrder = (match?.record?.resolution as? Resolution.CustomOrder)?.takeIf { match.applicable }
        when {
            resolvedOrder != null -> {
                orderStatus = ItemOrder.RESOLVED
                val survivors = allIds.filter { id ->
                    present(id, aGroups) || present(id, cGroups)
                }
                val valid = resolvedOrder.ids.filter { it in survivors }
                finalOrder = valid + survivors.filterNot { it in resolvedOrder.ids }
            }
            else -> {
                finalOrder = bOrder.filter {
                    present(it, aGroups) || present(it, cGroups)
                } + allIds.filter { it !in bOrder && (present(it, aGroups) || present(it, cGroups)) }
            }
        }
    }

    val byId = itemResults.associateBy { it.id }
    val orderedItems = finalOrder.mapNotNull { byId[it]?.let { r -> MIdItem(r.id, r.node, r.order) } }
    val extra = itemResults.filter { it.id.startsWith("#") }
        .map { MIdItem(it.id, it.node, it.order) }

    val anyResolved = itemResults.any { it.node.status == MergeStatus.RESOLVED } ||
        orderStatus == ItemOrder.RESOLVED
    val innerConflicts = itemResults.flatMap { nodeConflicts(it.node) }
    val unresolvedCount = innerConflicts.count { conf ->
        val m = decisions.match(decisions.find(conf.key))
        m?.applicable != true
    } + if (orderConflictRec != null && orderStatus != ItemOrder.RESOLVED) 1 else 0
    val finalStatus = when {
        unresolvedCount > 0 -> MergeStatus.CONFLICT
        anyResolved || innerConflicts.isNotEmpty() -> MergeStatus.RESOLVED
        itemResults.any { it.order == ItemOrder.FROM_A || it.order == ItemOrder.FROM_B } ->
            MergeStatus.AUTO_BOTH
        else -> MergeStatus.UNCHANGED
    }

    return MList(
        path = path,
        strategy = ListStrategy.ID,
        items = orderedItems + extra,
        orderConflict = orderConflictRec,
        status = finalStatus,
        provenance = Provenance(listOf(
            ProvStep(Side.BASE, "list", Fingerprint.of(b), "id 顺序: ${bOrder.joinToString(",")}"),
            ProvStep(Side.BRANCH_A, "list", Fingerprint.of(aIn), "id 顺序: ${aOrder.joinToString(",")}"),
            ProvStep(Side.BRANCH_B, "list", Fingerprint.of(cIn), "id 顺序: ${cOrder.joinToString(",")}"),
        ), "按稳定 id（字段 '$field'）合并 ${allIds.size} 个元素"),
        conflict = null,
    )
}

private fun present(id: String, groups: Map<String, IdEntry>): Boolean = groups.containsKey(id)

private fun idPresentInChosen(
    id: String, order: ItemOrder,
    b: Map<String, IdEntry>, a: Map<String, IdEntry>, c: Map<String, IdEntry>,
): Boolean = when (order) {
    ItemOrder.FROM_A -> present(id, a)
    ItemOrder.FROM_B -> present(id, c)
    else -> present(id, b)
}

/** Reorder survivors; keep side-order, append items the ordering side doesn't know. */
private fun mergeSurvivors(
    ordered: List<String>,
    survivors: List<String>,
    b: Map<String, IdEntry>,
    a: Map<String, IdEntry>,
    c: Map<String, IdEntry>,
    order: ItemOrder,
): List<String> {
    val survivorSet = survivors.toSet()
    val result = ordered.filter { it in survivorSet }.toMutableList()
    // Items surviving only due to the *other* side: insert relative to base order, else append.
    val baseOrder = b.keys.toList()
    val extras = survivors.filterNot { it in result }
    for (extra in extras) {
        val baseIdx = baseOrder.indexOf(extra)
        var inserted = false
        if (baseIdx >= 0) {
            for (k in (baseIdx - 1) downTo 0) {
                val anchor = baseOrder[k]
                val pos = result.indexOf(anchor)
                if (pos >= 0) { result.add(pos + 1, extra); inserted = true; break }
            }
        }
        if (!inserted) result.add(extra)
    }
    return result
}

private fun Merger.dupConflictOrNull(
    listPath: Path, itemPath: Path, id: String,
    b: IdEntry?, a: IdEntry?, c: IdEntry?,
): Conflict? {
    val dupSide = when {
        a?.dup == true && c?.dup == true -> "A 与 B"
        a?.dup == true -> "A"
        c?.dup == true -> "B"
        b?.dup == true -> "祖先"
        else -> null
    } ?: return null
    return Conflict(
        conflictKey(itemPath, ConflictKind.DUPLICATE_ID),
        itemPath, ConflictKind.DUPLICATE_ID,
        "稳定 id '$id' 在 ${dupSide} 侧重复出现",
        duplicateId = id,
    )
}
