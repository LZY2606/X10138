package merger

import java.math.BigDecimal
import java.util.IdentityHashMap

enum class Format { YAML, JSON }

data class AnchorRef(val name: String, val viaAlias: Boolean, val line: Int?)

typealias NodeMeta = List<AnchorRef>

/**
 * 解析后的语义节点。data class 的结构相等只比较内容，不比较来源元数据，
 * 这样锚点/别名展开不会影响比较结果。别名在解析阶段展开成彼此独立的实例。
 */
sealed class SNode

data class SSMissing(val dummy: Unit = Unit) : SNode() {
    companion object {
        val INSTANCE = SSMissing()
    }
}

data class SSDelete(val dummy: Unit = Unit) : SNode() {
    companion object {
        val INSTANCE = SSDelete()
    }
}

data class SSNull(val dummy: Unit = Unit) : SNode() {
    companion object {
        val INSTANCE = SSNull()
    }
}

data class SSString(val value: String) : SNode()
data class SSBool(val value: Boolean) : SNode()
data class SSNumber(val value: BigDecimal) : SNode() {
    override fun toString(): String = value.toPlainString()
}

class SMap(val entries: LinkedHashMap<String, SNode> = LinkedHashMap()) : SNode() {
    val order: List<String> get() = entries.keys.toList()
    operator fun get(k: String): SNode? = entries[k]
    fun with(k: String, v: SNode): SMap {
        entries[k] = v
        return this
    }
    override fun equals(other: Any?): Boolean = other is SMap && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "SMap$entries"
}

class SSeq(val items: List<SNode> = emptyList()) : SNode() {
    override fun equals(other: Any?): Boolean = other is SSeq && other.items == items
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = "SSeq$items"
}

fun smapOf(vararg pairs: Pair<String, SNode>): SMap {
    val m = LinkedHashMap<String, SNode>()
    pairs.forEach { m[it.first] = it.second }
    return SMap(m)
}

fun sseqOf(vararg items: SNode): SSeq = SSeq(items.toList())

fun SNode.deepCopy(): SNode = when (this) {
    is SMap -> SMap(LinkedHashMap(entries.mapValues { it.value.deepCopy() }))
    is SSeq -> SSeq(items.map { it.deepCopy() })
    else -> this
}

val SNode?.isMissing: Boolean get() = this == null || this is SSMissing
val SNode?.isDelete: Boolean get() = this is SSDelete
val SNode?.isNullValue: Boolean get() = this is SSNull

enum class Source { BASE, A, B }

data class ParsedDoc(
    val source: Source,
    val format: Format,
    val raw: String,
    val root: SNode,
    val fingerprint: String,
    val meta: IdentityHashMap<SNode, MutableList<AnchorRef>>,
    val parseMs: Long
) {
    fun metaOf(node: SNode?): List<AnchorRef> = node?.let { meta[it] } ?: emptyList()
}

class ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
