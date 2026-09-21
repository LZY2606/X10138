package merger

/**
 * 三方语义合并：共同祖先 base + 分支 a + 分支 b。
 *
 * 语义约定：
 * - 对象按键名合并，保留插入顺序（祖先顺序 + A/B 新增顺序）。
 * - 数组按路径登记的策略处理；未登记策略产出 MISSING_POLICY 冲突，不猜测。
 * - 键缺失（容器里没有）、显式 null（NullVal）、删除（边相对祖先移除键）三者区分。
 * - 合并结果是全新构造的树，三边输入之间不存在共享引用。
 */
class MergeEngine(private val policies: PolicyRegistry) {
    private val conflicts = mutableListOf<Conflict>()

    fun merge(base: Node?, a: Node?, b: Node?): MergeDocument {
        conflicts.clear()
        val result = mergeNode(Path.ROOT, base, a, b)
        return MergeDocument(
            result = result,
            conflicts = conflicts.toList(),
            policyVersion = policies.version,
            fingerprints = Triple(Fingerprints.of(base), Fingerprints.of(a), Fingerprints.of(b)),
        )
    }

    private fun mergeNode(path: Path, base: Node?, a: Node?, b: Node?): RNode {
        val fa = Fingerprints.of(a)
        val fb = Fingerprints.of(b)
        val fbase = Fingerprints.of(base)
        val chain = sourceChain(base, a, b, resultFingerprint = null)

        // 1) 某一边缺失（相对祖先）
        if (a == null || b == null) return mergeMissing(path, base, a, b)

        // 2) 两边都没改（或内容一致）
        if (fa == fb) return buildFromNode(path, a, chainAllEqual(base, a, b))

        // 对象：统一按键下钻，保证子数组仍能走登记策略；mergeObj 内部会处理
        // “只有一边改”的键，并保留来源链。
        if (a is Node.Obj && b is Node.Obj && (base == null || base is Node.Obj)) {
            return mergeObj(path, base as? Node.Obj, a, b)
        }
        // 数组：只要登记了策略，就必须走策略合并（即使只有一边变化，
        // 也要保证 id 路径、顺序与删除语义一致）；未登记则提示策略。
        if (a is Node.Arr && b is Node.Arr && (base == null || base is Node.Arr)) {
            return mergeArr(path, base as? Node.Arr, a, b)
        }
        // 标量：只有一边改直接采用。
        if (fa == fbase) return buildFromNode(path, b, markChanged(base, a, b))
        if (fb == fbase) return buildFromNode(path, a, markChanged(base, a, b))

        // 5) 真冲突
        return valueConflict(path, "两边都修改了该值，且结果不一致", base, a, b)
    }

    private fun chainAllEqual(base: Node?, a: Node?, b: Node?): List<SourceRecord> = listOf(
        SourceRecord(Side.BASE, base != null, base != null && a != null && structurallyEqual(base, a), base?.origin),
        SourceRecord(Side.A, a != null, true, a?.origin),
        SourceRecord(Side.B, b != null, true, b?.origin),
    )

    private fun markChanged(base: Node?, a: Node?, b: Node?): List<SourceRecord> {
        val winner = if (Fingerprints.of(a) == Fingerprints.of(base)) b else a
        return listOf(
            SourceRecord(Side.BASE, base != null, base != null && structurallyEqual(base, winner), base?.origin),
            SourceRecord(Side.A, a != null, structurallyEqual(a, winner), a?.origin),
            SourceRecord(Side.B, b != null, structurallyEqual(b, winner), b?.origin),
        )
    }

    private fun sourceChain(base: Node?, a: Node?, b: Node?, resultFingerprint: String?): List<SourceRecord> =
        listOf(
            SourceRecord(Side.BASE, base != null, equalFp(base, resultFingerprint), base?.origin),
            SourceRecord(Side.A, a != null, equalFp(a, resultFingerprint), a?.origin),
            SourceRecord(Side.B, b != null, equalFp(b, resultFingerprint), b?.origin),
        )

    private fun equalFp(node: Node?, fp: String?): Boolean =
        fp != null && node != null && Fingerprints.of(node) == fp

    private fun buildFromNode(path: Path, node: Node, chain: List<SourceRecord>): RNode = when (node) {
        is Node.Scalar -> RNode.RScalar(path, node.value, chain)
        is Node.Obj -> {
            val children = LinkedHashMap<String, RNode>()
            node.children.forEach { (k, v) -> children[k] = buildFromNode(path.child(k), v, chain) }
            RNode.RObj(path, children, chain)
        }
        is Node.Arr -> RNode.RArr(path, node.items.mapIndexed { i, v ->
            buildFromNode(path.childIndex(i), v, chain)
        }.toMutableList(), policies.arrayPolicyAt(path.childEntry()), chain)
    }

    private fun mergeMissing(path: Path, base: Node?, a: Node?, b: Node?): RNode {
        val aMissing = a == null
        val bMissing = b == null
        if (aMissing && bMissing) {
            return if (base != null) {
                // 双方都删除 -> 自动删除
                RNode.RDelete(path, sourceChain(base, a, b, null))
            } else RNode.RObj(path, LinkedHashMap(), sourceChain(base, a, b, null))
        }
        val presentSide = if (aMissing) Side.B else Side.A
        val present = if (aMissing) b else a
        if (base == null) {
            // 一边新增，一边没动（本就没有）-> 采用新增
            return buildFromNode(path, present!!, sourceChain(base, a, b, Fingerprints.of(present)))
        }
        // 一边删除，另一边：
        val otherFp = Fingerprints.of(present)
        return if (otherFp == Fingerprints.of(base)) {
            // 另一边未改 -> 自动接受删除
            RNode.RDelete(path, sourceChain(base, a, b, null))
        } else {
            // 删除 vs 修改 -> 真冲突
            valueConflict(
                path,
                if (aMissing) "分支A 删除了该字段，而分支B 修改了它" else "分支B 删除了该字段，而分支A 修改了它",
                base, a, b,
            )
        }
    }

    private fun mergeObj(path: Path, base: Node.Obj?, a: Node.Obj, b: Node.Obj): RNode.RObj {
        val baseKeys = base?.children?.keys.orEmpty().toMutableList()
        val keys = LinkedHashSet<String>()
        keys.addAll(baseKeys)
        // 保留各边新增键的出现顺序
        a.children.keys.forEach { if (it !in keys) keys.add(it) }
        b.children.keys.forEach { if (it !in keys) keys.add(it) }

        val out = LinkedHashMap<String, RNode>()
        val chainAcc = mutableMapOf<Side, Boolean>()
        for (key in keys) {
            val childPath = path.child(key)
            val bv = base?.children?.get(key)
            val av = a.children[key]
            val bvv = b.children[key]
            val child = mergeNode(childPath, bv, av, bvv)
            if (child !is RNode.RDelete) out[key] = child
        }
        val chain = listOf(
            SourceRecord(Side.BASE, base != null, false, base?.origin),
            SourceRecord(Side.A, true, false, a.origin),
            SourceRecord(Side.B, true, false, b.origin),
        )
        return RNode.RObj(path, out, chain)
    }

    private fun mergeArr(path: Path, base: Node.Arr?, a: Node.Arr, b: Node.Arr): RNode {
        val policy = policies.arrayPolicyAt(path)
        if (policy == null) {
            val key = path.toString().removePrefix(".") + "[]"
            val id = conflictId(ConflictType.MISSING_POLICY, path, key)
            val conflict = Conflict(
                id = id,
                type = ConflictType.MISSING_POLICY,
                path = path,
                message = "数组 $path 未登记合并策略，不能猜测；请选择替换 / 按 id / 有序序列",
                base = base, a = a, b = b,
                detail = ConflictDetail.MissingPolicy(key),
            )
            conflicts.add(conflict)
            return RNode.RConflictRef(path, id, sourceChain(base, a, b, null))
        }
        return when (policy) {
            ArrayPolicy.REPLACE -> mergeArrReplace(path, base, a, b)
            ArrayPolicy.ID -> mergeArrById(path, base, a, b)
            ArrayPolicy.SEQ -> mergeArrSeq(path, base, a, b)
        }
    }

    private fun mergeArrReplace(path: Path, base: Node.Arr?, a: Node.Arr, b: Node.Arr): RNode {
        val fa = Fingerprints.of(a)
        val fb = Fingerprints.of(b)
        val fbase = Fingerprints.of(base)
        return when {
            fa == fb -> buildFromNode(path, a, chainAllEqual(base, a, b))
            fa == fbase -> buildFromNode(path, b, markChanged(base, a, b))
            fb == fbase -> buildFromNode(path, a, markChanged(base, a, b))
            else -> valueConflict(path, "替换策略下两边对数组做了不同修改", base, a, b)
        }
    }

    private fun mergeArrById(path: Path, base: Node.Arr?, a: Node.Arr, b: Node.Arr): RNode {
        val idKey = policies.idKeyAt(path)
        val dup = findDuplicateId(path, a, Side.A, idKey) ?: findDuplicateId(path, b, Side.B, idKey)
        if (dup != null) {
            val id = conflictId(ConflictType.DUP_ID, path, dup.id)
            conflicts.add(
                Conflict(
                    id = id,
                    type = ConflictType.DUP_ID,
                    path = path,
                    message = "分支${dup.side.label} 的数组中 id '${dup.id}' 出现了 ${dup.count} 次，无法按稳定 id 合并",
                    base = base, a = a, b = b,
                    detail = ConflictDetail.DupId(dup.side, dup.id, dup.count),
                )
            )
            return RNode.RConflictRef(path, id, sourceChain(base, a, b, null))
        }

        val baseMap = base?.items.orEmpty().mapNotNull { indexed(it, idKey) }.toMap(LinkedHashMap())
        val aMap = a.items.mapNotNull { indexed(it, idKey) }.toMap(LinkedHashMap())
        val bMap = b.items.mapNotNull { indexed(it, idKey) }.toMap(LinkedHashMap())

        // 逐 id 做三方合并；未用 id 标识的元素退化为“值”冲突候选（这里要求全部带 id）
        val allItems = (a.items + b.items).mapNotNull { itemId(it, idKey) }
        val missingId = (a.items + b.items).firstOrNull { itemId(it, idKey) == null }
        if (missingId != null || base?.items?.any { itemId(it, idKey) == null } == true) {
            return valueConflict(path, "按 id 策略要求数组每个元素都有 '$idKey' 字段", base, a, b)
        }

        val ids = LinkedHashSet<String>()
        baseMap.keys.forEach(ids::add)
        aMap.keys.forEach(ids::add)
        bMap.keys.forEach(ids::add)

        val merged = LinkedHashMap<String, RNode>()
        for (id in ids) {
            val elemPath = path.child(id)
            val mergedElem = mergeNode(elemPath, baseMap[id], aMap[id], bMap[id])
            if (mergedElem !is RNode.RDelete) merged[id] = mergedElem
        }

        // 顺序合并
        val baseOrder = baseMap.keys.toList()
        val aOrder = aMap.keys.filter { it in merged }
        val bOrder = bMap.keys.filter { it in merged }
        val orderedIds = mergeIdOrder(baseOrder, aOrder, bOrder)
        val orderConflictId: String? = if (orderedIds == null) {
            val cid = conflictId(ConflictType.ORDER, path, "order")
            conflicts.add(
                Conflict(
                    id = cid,
                    type = ConflictType.ORDER,
                    path = path,
                    message = "两边对数组元素顺序做了互不兼容的调整",
                    base = base, a = a, b = b,
                    detail = ConflictDetail.Order(
                        baseOrder.filter { it in merged },
                        aOrder,
                        bOrder,
                    ),
                )
            )
            cid
        } else null

        val items = mutableListOf<RNode>()
        val effectiveOrder = orderedIds ?: merged.keys.toList()
        for (id in effectiveOrder) {
            merged[id]?.let { items.add(it) }
        }
        return RNode.RArr(path, items, ArrayPolicy.ID, sourceChain(base, a, b, null)).also {
            // 顺序冲突时，把冲突引用作为附加标记放在数组元数据里（UI 可见，不占据元素位置）
            if (orderConflictId != null) it.orderConflictId = orderConflictId
        }
    }

    private data class Indexed(val id: String, val node: Node.Obj)

    private fun itemId(node: Node, idKey: String): String? {
        if (node !is Node.Obj) return null
        val v = node.children[idKey]
        return (v as? Node.Scalar)?.let { scalarToString(it.value) }
    }

    private fun indexed(node: Node, idKey: String): Pair<String, Node.Obj>? {
        if (node !is Node.Obj) return null
        val id = itemId(node, idKey) ?: return null
        return id to node
    }

    private data class Dup(val side: Side, val id: String, val count: Int)
    private fun findDuplicateId(path: Path, arr: Node.Arr, side: Side, idKey: String): Dup? {
        val counts = LinkedHashMap<String, Int>()
        for (item in arr.items) {
            val id = itemId(item, idKey) ?: continue
            counts[id] = (counts[id] ?: 0) + 1
        }
        val first = counts.entries.firstOrNull { it.value > 1 } ?: return null
        return Dup(side, first.key, first.value)
    }

    private fun mergeArrSeq(path: Path, base: Node.Arr?, a: Node.Arr, b: Node.Arr): RNode {
        val baseItems = base?.items.orEmpty()
        val chunks = Diff3.align(baseItems, a.items, b.items) { x, y -> structurallyEqual(x, y) }
        val out = mutableListOf<RNode>()
        var index = 0
        for (chunk in chunks) {
            when (chunk) {
                is Diff3.Chunk.Stable -> {
                    for (node in chunk.base) {
                        out.add(buildFromNode(path.childIndex(index), node, chainAllEqual(node, node, node)))
                        index++
                    }
                }
                is Diff3.Chunk.Change -> {
                    val sameA = chunk.a == chunk.base
                    val sameB = chunk.b == chunk.base
                    when {
                        sameA && sameB -> for (node in chunk.base) {
                            out.add(buildFromNode(path.childIndex(index), node, chainAllEqual(node, node, node)))
                            index++
                        }
                        sameA -> for (node in chunk.b) {
                            out.add(buildFromNode(path.childIndex(index), node, markChanged(chunk.base.firstOrNull(), chunk.base.firstOrNull(), node)))
                            index++
                        }
                        sameB -> for (node in chunk.a) {
                            out.add(buildFromNode(path.childIndex(index), node, markChanged(chunk.base.firstOrNull(), node, chunk.base.firstOrNull())))
                            index++
                        }
                        chunksEqual(chunk.a, chunk.b) -> for (node in chunk.a) {
                            out.add(buildFromNode(path.childIndex(index), node, chainAllEqual(chunk.base.firstOrNull(), node, node)))
                            index++
                        }
                        else -> {
                            val cid = conflictId(ConflictType.SEQ_HUNK, path, "hunk@${chunk.baseStart}")
                            conflicts.add(
                                Conflict(
                                    id = cid,
                                    type = ConflictType.SEQ_HUNK,
                                    path = path,
                                    message = "有序序列在同一段落两边做了不同修改",
                                    base = null, a = null, b = null,
                                    detail = ConflictDetail.SeqHunk(chunk.base, chunk.a, chunk.b),
                                )
                            )
                            out.add(RNode.RConflictRef(path, cid, sourceChain(base, null, null, null)))
                        }
                    }
                }
            }
        }
        return RNode.RArr(path, out, ArrayPolicy.SEQ, sourceChain(base, a, b, null))
    }

    private fun chunksEqual(a: List<Node>, b: List<Node>): Boolean =
        a.size == b.size && a.zip(b).all { structurallyEqual(it.first, it.second) }

    /**
     * id 顺序合并：对“有效 id 集合”（两边都未删除）做三方 LCS 对齐。
     * 返回 null 表示顺序冲突。
     */
    private fun mergeIdOrder(base: List<String>, a: List<String>, b: List<String>): List<String>? {
        // 只考虑最终仍然存在的 id
        val alive = (a + b).toMutableSet()
        // 双方都删除的 id 已在元素合并阶段剔除；此处 a/b 仅包含 alive。
        val bN = base.filter { it in alive }
        val aN = a.filter { it in alive }
        val bN2 = b.filter { it in alive }
        val chunks = Diff3.align(bN, aN, bN2) { x, y -> x == y }
        val out = mutableListOf<String>()
        for (chunk in chunks) {
            when (chunk) {
                is Diff3.Chunk.Stable -> out.addAll(chunk.base)
                is Diff3.Chunk.Change -> {
                    val sameA = chunk.a == chunk.base
                    val sameB = chunk.b == chunk.base
                    when {
                        sameA -> out.addAll(chunk.b)
                        sameB -> out.addAll(chunk.a)
                        chunk.a == chunk.b -> out.addAll(chunk.a)
                        else -> return null
                    }
                }
            }
        }
        return out
    }

    private fun valueConflict(
        path: Path,
        message: String,
        base: Node?,
        a: Node?,
        b: Node?,
        type: ConflictType = ConflictType.VALUE,
    ): RNode.RConflictRef {
        val id = conflictId(type, path, "${Fingerprints.of(base)}|${Fingerprints.of(a)}|${Fingerprints.of(b)}")
        conflicts.add(Conflict(id, type, path, message, base, a, b))
        return RNode.RConflictRef(path, id, sourceChain(base, a, b, null))
    }

    private fun conflictId(type: ConflictType, path: Path, salt: String): String {
        val id = "${type.name}@${path}#${Hash.sha256Hex(salt).take(12)}"
        // 同路径同类型可能有多个 hunk；salt 已区分。保证唯一。
        return if (conflicts.none { it.id == id }) id
        else "$id-${conflicts.count { it.path == path && it.type == type }}"
    }

    private fun scalarToString(v: ScalarValue): String? = when (v) {
        is ScalarValue.StrVal -> v.value
        is ScalarValue.BoolVal -> v.raw
        is ScalarValue.NumVal -> JsonWriter.normalizeNumber(v)
        ScalarValue.NullVal -> null
    }

    private fun structurallyEqual(a: Node?, b: Node?): Boolean = Fingerprints.of(a) == Fingerprints.of(b)
}

/** 合并器一次运行的完整产物。 */
data class MergeDocument(
    val result: RNode,
    val conflicts: List<Conflict>,
    val policyVersion: Int,
    val fingerprints: Triple<String, String, String>,
)
