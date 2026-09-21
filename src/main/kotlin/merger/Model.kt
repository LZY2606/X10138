package merger

import java.security.MessageDigest

/** 来源链中的一环：某个值由谁提供，附带说明（如 alias-expanded / modified / manual）。 */
data class Origin(val source: String, val note: String? = null)

/** 路径段：对象键或数组下标。 */
sealed class Seg {
    data class Key(val name: String) : Seg()
    data class Idx(val index: Int) : Seg()
}

fun pathToString(path: List<Seg>): String =
    if (path.isEmpty()) "/" else path.joinToString("") {
        when (it) {
            is Seg.Key -> "/" + it.name.replace("~", "~0").replace("/", "~1")
            is Seg.Idx -> "/" + it.index.toString()
        }
    }

fun pathFromString(s: String): List<Seg> {
    if (s.isEmpty() || s == "/") return emptyList()
    return s.split("/").drop(1).map { raw ->
        val un = raw.replace("~1", "/").replace("~0", "~")
        un.toIntOrNull()?.let { Seg.Idx(it) } ?: Seg.Key(un)
    }
}

/**
 * 解析后的配置节点。不可变，输出阶段深拷贝，绝不共享可变引用。
 * missing（字段缺失）用可空 CNode? 的 null 表示，与 CNull（显式 null）严格区分。
 */
sealed class CNode {
    abstract val origins: List<Origin>

    data class CObject(val entries: LinkedHashMap<String, CNode>, override val origins: List<Origin> = emptyList()) : CNode()
    data class CArray(val items: List<CNode>, override val origins: List<Origin> = emptyList()) : CNode()
    data class CScalar(val value: Any, override val origins: List<Origin> = emptyList()) : CNode() // String/Long/Double/Boolean
    data class CNull(override val origins: List<Origin> = emptyList()) : CNode()
}

fun CNode.withOrigins(o: List<Origin>): CNode = when (this) {
    is CNode.CObject -> copy(origins = o)
    is CNode.CArray -> copy(origins = o)
    is CNode.CScalar -> copy(origins = o)
    is CNode.CNull -> copy(origins = o)
}

/** 深拷贝：保证导出/合并结果之间不存在共享引用。 */
fun CNode.deepCopy(): CNode = when (this) {
    is CNode.CObject -> CNode.CObject(
        entries.entries.associateTo(LinkedHashMap()) { (k, v) -> k to v.deepCopy() }, origins)
    is CNode.CArray -> CNode.CArray(items.map { it.deepCopy() }, origins)
    is CNode.CScalar -> copy()
    is CNode.CNull -> copy()
}

/** 规范串行化：对象键排序、标量带类型标签，用于稳定指纹。 */
private fun canonical(n: CNode?, sb: StringBuilder) {
    when (n) {
        null -> sb.append("missing")
        is CNode.CNull -> sb.append("null")
        is CNode.CScalar -> when (val v = n.value) {
            is String -> sb.append("s:").append(v.length).append(':').append(v)
            is Boolean -> sb.append("b:").append(v)
            is Int, is Long -> sb.append("i:").append(v)
            is Double, is Float -> sb.append("d:").append(v)
            else -> sb.append("s:").append(v.toString())
        }
        is CNode.CArray -> {
            sb.append('[')
            n.items.forEach { canonical(it, sb); sb.append(',') }
            sb.append(']')
        }
        is CNode.CObject -> {
            sb.append('{')
            n.entries.toSortedMap().forEach { (k, v) ->
                sb.append(k.length).append(':').append(k).append('=')
                canonical(v, sb); sb.append(',')
            }
            sb.append('}')
        }
    }
}

const val MISSING_FP = "missing"

/** 内容指纹：SHA-256(规范形式)。missing 用哨兵值，与 null 指纹不同。 */
fun fingerprint(n: CNode?): String {
    if (n == null) return MISSING_FP
    val md = MessageDigest.getInstance("SHA-256")
    val sb = StringBuilder()
    canonical(n, sb)
    return md.digest(sb.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun nodeAt(root: CNode?, path: List<Seg>): CNode? {
    var cur: CNode? = root
    for (seg in path) {
        cur = when {
            cur is CNode.CObject && seg is Seg.Key -> cur.entries[seg.name]
            cur is CNode.CArray && seg is Seg.Idx -> cur.items.getOrNull(seg.index)
            else -> return null
        }
    }
    return cur
}
