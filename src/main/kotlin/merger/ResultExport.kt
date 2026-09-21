package merger

/**
 * 结果树导出为纯 Node（剔除删除节点、冲突占位）。
 * 仍有未决冲突时拒绝导出。
 */
object ResultExport {
    fun toNode(document: MergeDocument): Node {
        require(document.conflicts.none { !it.resolved }) { "存在未解决冲突，无法导出" }
        return convert(document.result)
    }

    private fun convert(r: RNode): Node = when (r) {
        is RNode.RScalar -> Node.Scalar(r.value, r.sources.firstOrNull { it.origin != null }?.origin ?: Origin.SYNTHETIC)
        is RNode.RDelete -> error("删除节点不应进入导出")
        is RNode.RObj -> Node.Obj(
            LinkedHashMap(r.children.mapValues { convert(it.value) }),
            r.sources.firstOrNull { it.origin != null }?.origin ?: Origin.SYNTHETIC,
        )
        is RNode.RArr -> Node.Arr(
            r.items.map { convert(it) }.toMutableList(),
            r.sources.firstOrNull { it.origin != null }?.origin ?: Origin.SYNTHETIC,
        )
        is RNode.RConflictRef -> error("未解决的冲突不能导出: ${r.conflictId}")
    }

    fun render(document: MergeDocument, format: ConfigFormat): String {
        val node = toNode(document)
        return when (format) {
            ConfigFormat.YAML -> YamlWriter.write(node)
            ConfigFormat.JSON -> JsonWriter.write(node)
        }
    }

    /** 结果指纹：对导出节点规范序列化后哈希；导出再导入保持一致。 */
    fun resultFingerprint(document: MergeDocument): String {
        val node = toNode(document)
        return Fingerprints.of(node)
    }
}

fun resultToNode(document: MergeDocument): Node = ResultExport.toNode(document)
