package semmerge

import java.security.MessageDigest

/** 来源标记：某个值来自哪一侧（base/ours/theirs/merged）以及路径。 */
data class Prov(val side: String, val path: String)

/**
 * 语义节点。缺失（missing）不表示为节点，而是用 Node? = null 表达；
 * NullNode 是显式的 null 值，与缺失严格区分。
 */
sealed class Node {
    abstract val prov: List<Prov>
    abstract fun withProv(p: List<Prov>): Node

    data class Obj(val entries: LinkedHashMap<String, Node>, override val prov: List<Prov> = emptyList()) : Node() {
        override fun withProv(p: List<Prov>) = copy(prov = p)
    }

    data class Arr(val items: List<Node>, override val prov: List<Prov> = emptyList()) : Node() {
        override fun withProv(p: List<Prov>) = copy(prov = p)
    }

    enum class Kind { STRING, NUMBER, BOOL }

    data class Scalar(val value: String, val kind: Kind, override val prov: List<Prov> = emptyList()) : Node() {
        override fun withProv(p: List<Prov>) = copy(prov = p)
    }

    data class NullNode(override val prov: List<Prov> = emptyList()) : Node() {
        override fun withProv(p: List<Prov>) = copy(prov = p)
    }
}

/** 结构相等：忽略来源信息，比较内容与键顺序。 */
fun structEq(a: Node?, b: Node?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return when (a) {
        is Node.Obj -> b is Node.Obj &&
                a.entries.keys == b.entries.keys &&
                a.entries.all { (k, v) -> structEq(v, b.entries[k]) }
        is Node.Arr -> b is Node.Arr &&
                a.items.size == b.items.size &&
                a.items.indices.all { structEq(a.items[it], b.items[it]) }
        is Node.Scalar -> b is Node.Scalar && a.kind == b.kind && a.value == b.value
        is Node.NullNode -> b is Node.NullNode
    }
}

/** 深拷贝：保证别名展开后不产生共享可变引用。 */
fun deepCopy(n: Node): Node = when (n) {
    is Node.Obj -> Node.Obj(LinkedHashMap(n.entries.mapValues { deepCopy(it.value) }), n.prov)
    is Node.Arr -> Node.Arr(n.items.map { deepCopy(it) }, n.prov)
    is Node.Scalar -> n.copy()
    is Node.NullNode -> n.copy()
}

/** 规范化序列化（键排序），用于指纹计算，保证与键顺序无关的稳定哈希。 */
fun canonical(n: Node?): String = when (n) {
    null -> "<missing>"
    is Node.NullNode -> "null"
    is Node.Scalar -> when (n.kind) {
        Node.Kind.STRING -> "s:${quote(n.value)}"
        Node.Kind.NUMBER -> "n:${n.value}"
        Node.Kind.BOOL -> "b:${n.value}"
    }
    is Node.Arr -> "[" + n.items.joinToString(",") { canonical(it) } + "]"
    is Node.Obj -> "{" + n.entries.toSortedMap().entries.joinToString(",") { "${quote(it.key)}:${canonical(it.value)}" } + "}"
}

private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
    append('"')
}

fun fingerprint(n: Node?): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(canonical(n).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun childPath(parent: String, key: String): String =
    if (parent == "$") "$.$key" else "$parent.$key"

fun indexPath(parent: String, index: Int): String = "$parent[$index]"
