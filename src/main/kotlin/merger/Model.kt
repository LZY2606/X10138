package merger

import java.security.MessageDigest

/** 值来源：文档 id + 路径 + 顺序信息 */
data class SourceRef(val docId: String, val path: String, val order: Int = -1) {
    override fun toString(): String = "$docId:$path" + if (order >= 0) "#$order" else ""
}

enum class ScalarKind { STRING, NUMBER, BOOLEAN }

sealed class Node {
    var source: SourceRef? = null
    /** 结果值来源链：贡献过该值的各来源（祖先、分支、裁决） */
    var sourceChain: List<SourceRef> = emptyList()
    abstract val typeName: String
    abstract fun copyDeep(): Node
}

class ScalarNode(val value: String, val kind: ScalarKind) : Node() {
    override val typeName get() = "scalar"
    override fun copyDeep() = ScalarNode(value, kind).also { it.source = source; it.sourceChain = sourceChain }
    override fun toString() = value
}

class NullNode : Node() {
    override val typeName get() = "null"
    override fun copyDeep() = NullNode().also { it.source = source; it.sourceChain = sourceChain }
    override fun toString() = "null"
}

class MapNode : Node() {
    val entries = LinkedHashMap<String, Node>()
    override val typeName get() = "map"
    override fun copyDeep(): MapNode {
        val m = MapNode(); m.source = source; m.sourceChain = sourceChain
        for ((k, v) in entries) m.entries[k] = v.copyDeep()
        return m
    }
}

class SeqNode : Node() {
    val items = mutableListOf<Node>()
    override val typeName get() = "seq"
    override fun copyDeep(): SeqNode {
        val s = SeqNode(); s.source = source; s.sourceChain = sourceChain
        items.forEach { s.items.add(it.copyDeep()) }
        return s
    }
}

/** 结构相等：缺失(null 参数) 与 NullNode 不相等；map 键顺序不影响相等性 */
fun structEq(a: Node?, b: Node?): Boolean {
    if (a == null || b == null) return a == b
    return when (a) {
        is NullNode -> b is NullNode
        is ScalarNode -> b is ScalarNode && a.kind == b.kind && a.value == b.value
        is MapNode -> b is MapNode && a.entries.keys == b.entries.keys &&
            a.entries.all { (k, v) -> structEq(v, b.entries[k]) }
        is SeqNode -> b is SeqNode && a.items.size == b.items.size &&
            a.items.indices.all { structEq(a.items[it], b.items[it]) }
    }
}

fun escapeKey(key: String): String = key.replace("~", "~0").replace("/", "~1")
fun unescapeKey(key: String): String = key.replace("~1", "/").replace("~0", "~")
fun childPath(path: String, key: String) = "$path/${escapeKey(key)}"

/** 稳定序列化：map 键排序，序列保序；用于指纹，保证导出再导入后一致 */
fun canonical(n: Node?): String = when (n) {
    null -> "ABSENT"
    is NullNode -> "null"
    is ScalarNode -> "${n.kind}:${n.value.length}:${n.value}"
    is MapNode -> n.entries.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) ->
        "\"${escapeKey(k)}\":${canonical(v)}"
    }
    is SeqNode -> n.items.joinToString(",", "[", "]") { canonical(it) }
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun fingerprint(n: Node?): String = sha256(canonical(n))

/** 转为普通 Java 对象用于输出；每次生成全新对象，不产生共享可变引用 */
fun nodeToPlain(n: Node): Any? = when (n) {
    is NullNode -> null
    is ScalarNode -> when (n.kind) {
        ScalarKind.BOOLEAN -> n.value.toBoolean()
        ScalarKind.NUMBER -> n.value.toLongOrNull() ?: n.value.toDoubleOrNull() ?: n.value
        ScalarKind.STRING -> n.value
    }
    is MapNode -> n.entries.mapValuesTo(LinkedHashMap()) { (_, v) -> nodeToPlain(v) }
    is SeqNode -> n.items.map { nodeToPlain(it) }
}
