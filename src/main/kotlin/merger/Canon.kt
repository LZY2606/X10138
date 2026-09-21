package merger

import java.math.BigDecimal
import java.security.MessageDigest

/** 稳定序列化：同一棵树永远得到同一串文本，是指纹与相等性判断的基础。 */
object Canon {
    /** 缺失标记：任何真实节点的序列化都不会以该前缀开头（标量带引号或为字面量）。 */
    const val MISSING = "~missing"

    fun of(node: Node?): String = when (node) {
        null -> MISSING
        is ScalarNode -> when (val v = node.value) {
            null -> "null"
            is Boolean -> v.toString()
            is BigDecimal -> if (v.compareTo(BigDecimal.ZERO) == 0) "0" else v.stripTrailingZeros().toPlainString()
            is String -> quote(v)
            else -> quote(v.toString())
        }
        is ArrNode -> node.items.joinToString(",", "[", "]") { of(it) }
        is ObjNode -> node.entries.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + of(it.value) }
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun fingerprint(node: Node?): String = sha256(of(node))
}
