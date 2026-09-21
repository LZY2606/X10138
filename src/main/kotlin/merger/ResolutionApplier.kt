package merger

/**
 * 把已解决冲突的裁决应用到结果树：
 * - VALUE 冲突：Take / SetValue / Delete 直接替换引用位。
 * - SEQ_HUNK：替换数组中的冲突引用段。
 * - ORDER：按裁决重排 ID 数组元素。
 * - MISSING_POLICY：选择策略后由会话整体重算（这里不会再遇到）。
 * - DUP_ID：只能 Take 一边，或手工给值。
 */
object ResolutionApplier {
    fun applyAll(doc: MergeDocument): MergeDocument {
        val byId = doc.conflicts.filter { it.resolved }.associateBy { it.id }
        if (byId.isEmpty()) return doc
        val newResult = applyNode(doc.result, byId)
        return doc.copy(result = newResult)
    }

    private fun applyNode(node: RNode, byId: Map<String, Conflict>): RNode = when (node) {
        is RNode.RScalar -> node
        is RNode.RDelete -> node
        is RNode.RConflictRef -> {
            val conflict = byId[node.conflictId]
            if (conflict == null) node else resolveScalarLike(node, conflict)
        }
        is RNode.RObj -> {
            val children = LinkedHashMap<String, RNode>()
            node.children.forEach { (k, v) ->
                val nv = applyNode(v, byId)
                if (nv !is RNode.RDelete) children[k] = nv
            }
            RNode.RObj(node.path, children, node.sources)
        }
        is RNode.RArr -> applyArray(node, byId)
    }

    private fun applyArray(arr: RNode.RArr, byId: Map<String, Conflict>): RNode.RArr {
        // 先递归应用元素
        val processed = arr.items.map { applyNode(it, byId) }.toMutableList()

        // SEQ_HUNK：展开冲突引用为裁决给定的片段
        val expanded = mutableListOf<RNode>()
        var hunkIndex = 0
        for (item in processed) {
            if (item is RNode.RConflictRef) {
                val conflict = byId[item.conflictId]
                if (conflict != null && conflict.type == ConflictType.SEQ_HUNK) {
                    val chosen = chosenHunkNodes(conflict)
                    for (n in chosen) {
                        expanded.add(
                            materialize(n, arr.path.childIndex(hunkIndex), item.sources)
                        )
                        hunkIndex++
                    }
                    continue
                }
            }
            expanded.add(item)
            hunkIndex++
        }

        // ORDER：重排
        val orderConflictId = arr.orderConflictId
        if (orderConflictId != null && orderConflictId in byId) {
            val conflict = byId.getValue(orderConflictId)
            reorderById(expanded, conflict)
        }
        return RNode.RArr(arr.path, expanded, arr.policy, arr.sources).also {
            it.orderConflictId = if (orderConflictId != null && orderConflictId !in byId) orderConflictId else null
        }
    }

    private fun chosenHunkNodes(conflict: Conflict): List<Node> {
        return when (val r = conflict.resolution!!) {
            is Resolution.TakeHunk -> when (r.side) {
                Side.A -> (conflict.detail as ConflictDetail.SeqHunk).a
                Side.B -> (conflict.detail as ConflictDetail.SeqHunk).b
                Side.BASE -> (conflict.detail as ConflictDetail.SeqHunk).base
            }
            is Resolution.CustomHunk -> r.nodes
            else -> error("SEQ_HUNK 不支持的裁决: $r")
        }
    }

    private fun reorderById(items: MutableList<RNode>, conflict: Conflict) {
        val desired: List<String> = when (val r = conflict.resolution!!) {
            is Resolution.TakeOrder -> when (r.side) {
                Side.A -> (conflict.detail as ConflictDetail.Order).aOrder
                Side.B -> (conflict.detail as ConflictDetail.Order).bOrder
                Side.BASE -> (conflict.detail as ConflictDetail.Order).baseOrder
            }
            is Resolution.CustomOrder -> r.ids
            else -> error("ORDER 不支持的裁决: $r")
        }
        // 元素路径形如 path.<id>
        val byId = items.associateBy { it.path.segments.filterIsInstance<PathSeg.Key>().lastOrNull()?.key }
        val out = mutableListOf<RNode>()
        val consumed = mutableSetOf<String>()
        for (id in desired) {
            val item = byId[id]
            if (item != null) { out.add(item); consumed.add(id) }
        }
        // 保留裁决顺序里未出现的新增元素在末尾
        for (item in items) {
            val id = item.path.segments.filterIsInstance<PathSeg.Key>().lastOrNull()?.key
            if (id == null || id !in consumed) out.add(item)
        }
        items.clear()
        items.addAll(out)
    }

    private fun resolveScalarLike(ref: RNode.RConflictRef, conflict: Conflict): RNode {
        return when (val r = conflict.resolution!!) {
            is Resolution.Take -> {
                val node = when (r.side) {
                    Side.BASE -> conflict.base
                    Side.A -> conflict.a
                    Side.B -> conflict.b
                } ?: return RNode.RDelete(ref.path, ref.sources)
                materialize(node, ref.path, ref.sources)
            }
            is Resolution.SetValue -> materialize(r.value, ref.path, ref.sources)
            Resolution.Delete -> RNode.RDelete(ref.path, ref.sources)
            is Resolution.ChoosePolicy -> ref
            else -> ref
        }
    }

    /** 把解析树物化为结果树（深拷贝语义，不与输入共享引用）。 */
    private fun materialize(node: Node, path: Path, sources: List<SourceRecord>): RNode = when (node) {
        is Node.Scalar -> RNode.RScalar(path, node.value, sources)
        is Node.Obj -> {
            val children = LinkedHashMap<String, RNode>()
            node.children.forEach { (k, v) -> children[k] = materialize(v, path.child(k), sources) }
            RNode.RObj(path, children, sources)
        }
        is Node.Arr -> RNode.RArr(
            path,
            node.items.mapIndexed { i, v -> materialize(v, path.childIndex(i), sources) }.toMutableList(),
            policy = null,
            sources = sources,
        )
    }
}
