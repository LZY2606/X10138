package merger

/** 来源信息：某个值来自哪个文档、哪条路径，附带说明（如别名展开、合并动作）。 */
data class Origin(val doc: String, val path: String, val note: String = "")

/**
 * 解析后的配置节点。缺失（字段不存在）用 null Node? 表示，
 * 与 ScalarNode(null)（显式设为 null）严格区分。
 */
sealed class Node {
    abstract val origins: List<Origin>
}

data class ObjNode(
    val entries: LinkedHashMap<String, Node>,
    override val origins: List<Origin> = emptyList(),
) : Node()

data class ArrNode(
    val items: List<Node>,
    override val origins: List<Origin> = emptyList(),
) : Node()

data class ScalarNode(
    val value: Any?, // String / Boolean / java.math.BigDecimal / null
    override val origins: List<Origin> = emptyList(),
) : Node()

fun Node.withExtraOrigin(origin: Origin): Node = when (this) {
    is ObjNode -> copy(origins = origins + origin)
    is ArrNode -> copy(origins = origins + origin)
    is ScalarNode -> copy(origins = origins + origin)
}
