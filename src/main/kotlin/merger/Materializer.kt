package merger

/**
 * 把合并树物化为结果模型。
 *  - 未解决冲突（CONFLICT/BLOCKED 且无裁决）使该子树不可物化；
 *  - 删除墓碑在最终输出中被剔除（但内部模型与指纹仍保留其存在）；
 *  - 数组按 children 中已物化项的顺序重建（ID 数组顺序由顺序裁决决定）。
 */
object Materializer {

    sealed class Result {
        data class Ok(val node: SNode?) : Result()
        data class Pending(val path: String) : Result()
    }

    fun materialize(root: MergeNode): SNode? = when (val r = of(root)) {
        is Result.Ok -> r.node
        is Result.Pending -> null
    }

    fun of(node: MergeNode): Result {
        return when {
            node.base == null && node.a == null && node.b == null && node.children.isEmpty() ->
                Result.Ok(node.result)

            node.status == MergeStatus.AUTO -> Result.Ok(node.result)

            node.status == MergeStatus.CONFLICT || node.status == MergeStatus.BLOCKED -> {
                if (node.resolvedBy != null) Result.Ok(node.result)
                else Result.Pending(node.path)
            }

            isMapNode(node) -> materializeMap(node)
            isArrayContainer(node) -> materializeArray(node)
            else -> Result.Ok(node.result)
        }
    }

    private fun isMapNode(node: MergeNode): Boolean =
        node.result is SMap || node.base is SMap || node.a is SMap || node.b is SMap

    private fun isArrayContainer(node: MergeNode): Boolean =
        node.result is SSeq || node.base is SSeq || node.a is SSeq || node.b is SSeq

    private fun materializeMap(node: MergeNode): Result {
        // 以合并阶段已构建的 result 条目为准（键顺序即合并顺序），
        // 再递归确认每个子节点已解决；未解决则整棵子树挂起。
        val resultMap = node.result as? SMap ?: return Result.Ok(SMap())
        val entries = LinkedHashMap<String, SNode>()
        for ((key, child) in node.children) {
            // 数组专用的合成子节点键不是字段名，跳过
            if (key.startsWith("id:") || key.startsWith("idx:") ||
                key.startsWith("region:") || key == "order" || key == "dup-id"
            ) continue
            when (val r = of(child)) {
                is Result.Pending -> return r
                is Result.Ok -> {
                    val v = r.node
                    when (v) {
                        null, is SSMissing -> Unit // 缺失：不写入
                        is SSDelete -> Unit         // 删除：从最终输出剔除
                        else -> entries[key] = v
                    }
                }
            }
        }
        return Result.Ok(SMap(entries))
    }

    private fun materializeArray(node: MergeNode): Result {
        // 若数组自身已有结果（ID 数组合成顺序、或替换/自动），优先采用。
        if (node.result is SSeq) {
            // 该结果在 merge 阶段已基于已解决子节点构建；再做一次墓碑剔除。
            return Result.Ok(SSeq(node.result.items.filter { it !is SSDelete && it !is SSMissing }))
        }
        val items = mutableListOf<SNode>()
        // SEQUENCE：按下标/region 顺序
        val keys = node.children.keys.toList()
        for (key in keys) {
            val child = node.children.getValue(key)
            when (val r = of(child)) {
                is Result.Pending -> return r
                is Result.Ok -> {
                    val v = r.node ?: continue
                    when (v) {
                        is SSDelete, is SSMissing -> Unit
                        else -> items.add(v)
                    }
                }
            }
        }
        return Result.Ok(SSeq(items))
    }
}
