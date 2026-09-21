package semmerge.model

/**
 * Parsed semantic configuration tree.
 *
 * The model deliberately keeps three otherwise-conflated concepts distinct:
 *  - a missing key (simply absent from an [SMap])
 *  - an explicit null value ([SScalar] with [ScalarKind.NULL])
 *  - a deletion (only materialized in the merge result as a delete action;
 *    parsed input never carries tombstones)
 */
sealed class SNode {
    abstract fun deepCopy(): SNode
}

enum class ScalarKind { NULL, BOOL, INT, FLOAT, STRING }

data class SScalar(
    val kind: ScalarKind,
    /** Original lexical text for numbers/bools/strings; null for NULL. */
    val text: String,
    val style: ScalarStyle,
) : SNode() {
    override fun deepCopy(): SNode = copy()

    companion object {
        val NULL = SScalar(ScalarKind.NULL, "null", ScalarStyle.PLAIN)
    }
}

enum class ScalarStyle { PLAIN, SINGLE_QUOTED, DOUBLE_QUOTED, LITERAL, FOLDED, FLOW }

/** Insertion order is preserved — "necessary order information" for maps. */
class SMap(
    entries: List<Pair<String, SNode>> = emptyList(),
) : SNode() {
    private val backing: LinkedHashMap<String, SNode> = LinkedHashMap<String, SNode>().apply { putAll(entries) }
    val keys: List<String> get() = backing.keys.toList()
    fun get(key: String): SNode? = backing[key]
    val size: Int get() = backing.size
    fun entries(): List<Pair<String, SNode>> = backing.entries.map { it.key to it.value }
    fun put(key: String, node: SNode) { backing[key] = node }
    override fun deepCopy(): SNode = SMap(entries().map { it.first to it.second.deepCopy() })

    override fun equals(other: Any?): Boolean {
        if (other !is SMap) return false
        val a = backing
        val b = other.backing
        if (a.keys != b.keys) {
            if (a.keys.toSet() != b.keys.toSet()) return false
        }
        return a.keys.all { a[it] == b[it] }
    }

    override fun hashCode(): Int {
        var result = 1
        for ((k, v) in backing) result = 31 * result + (k.hashCode() xor structuralHash(v))
        return result
    }
}

class SList(val items: List<SNode> = emptyList()) : SNode() {
    override fun deepCopy(): SNode = SList(items.map { it.deepCopy() })
    override fun equals(other: Any?): Boolean = other is SList && items == other.items
    override fun hashCode(): Int = items.fold(1) { acc, n -> 31 * acc + structuralHash(n) }
}

internal fun structuralHash(node: SNode?): Int = when (node) {
    null -> 0
    is SScalar -> node.kind.hashCode() * 31 + scalarText(node).hashCode()
    is SList -> node.hashCode()
    is SMap -> node.hashCode()
}

/** Structural comparison text (so 1 == 1 even if styles differ). */
fun scalarText(s: SScalar): String = when (s.kind) {
    ScalarKind.NULL -> ""
    else -> s.text
}

fun equalScalar(a: SScalar, b: SScalar): Boolean =
    a.kind == b.kind && scalarText(a) == scalarText(b)

fun structuralEqual(a: SNode?, b: SNode?): Boolean {
    if (a == null || b == null) return a === b
    return a == b
}
