package semmerge.session

import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.model.ScalarStyle

/** Tiny JSON-value codec helpers built on the semantic model. */
object JsonCodec {
    fun str(s: String): SScalar = SScalar(ScalarKind.STRING, s, ScalarStyle.PLAIN)
    fun num(n: Number): SScalar =
        SScalar(ScalarKind.INT, n.toString(), ScalarStyle.PLAIN)
    fun bool(b: Boolean): SScalar =
        SScalar(ScalarKind.BOOL, b.toString(), ScalarStyle.PLAIN)

    fun obj(vararg pairs: Pair<String, SNode?>): SMap =
        SMap(pairs.mapNotNull { (k, v) -> v?.let { k to it } })

    fun list(items: List<SNode?>): SList = SList(items.filterNotNull())

    @Suppress("UNCHECKED_CAST")
    fun SNode.asMap(): Map<String, SNode> = (this as SMap).entries().toMap()
    fun SNode.asList(): List<SNode> = (this as SList).items
    fun SNode.asString(): String {
        val s = this as SScalar
        return when (s.kind) {
            ScalarKind.NULL -> "null"
            else -> s.text
        }
    }
    fun SNode.asLong(): Long = (this as SScalar).text.toLong()
    fun SNode.asInt(): Int = (this as SScalar).text.toInt()
    fun Map<String, SNode>.str(key: String): String =
        this[key]?.asString() ?: throw IllegalStateException("missing field '$key'")
    fun Map<String, SNode>.strOr(key: String, default: String = ""): String =
        this[key]?.takeIf { (it as? SScalar)?.kind != ScalarKind.NULL }?.asString() ?: default
    fun Map<String, SNode>.longOr(key: String, default: Long): Long =
        this[key]?.asLong() ?: default
    fun Map<String, SNode>.map(key: String): Map<String, SNode> =
        (this[key] as SMap).entries().toMap()
    fun Map<String, SNode>.list(key: String): List<SNode> =
        (this[key] as? SList)?.items ?: emptyList()
}
