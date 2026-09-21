package merger

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 规范化序列化：用于内容指纹与结构相等判断。
 * 带类型前缀，null / 删除 / 缺失三者不会混淆；Map 键按字典序，
 * 因此指纹与键的书写顺序无关（值的数组顺序保留）。
 */
object Canonical {

    fun write(node: SNode?, sb: StringBuilder, includeTombstones: Boolean) {
        when (node) {
            null, is SSMissing -> sb.append("X") // 缺失
            is SSDelete -> if (includeTombstones) sb.append("D") else sb.append("X")
            is SSNull -> sb.append("0")
            is SSBool -> sb.append(if (node.value) "T" else "F")
            is SSString -> {
                sb.append('S')
                writeStr(node.value, sb)
            }
            is SSNumber -> {
                sb.append('N').append(normalizeNumber(node.value))
            }
            is SSeq -> {
                sb.append('A').append(node.items.size).append(';')
                node.items.forEach { write(it, sb, includeTombstones) }
            }
            is SMap -> {
                val keys = node.entries.keys.sorted()
                sb.append('M').append(keys.size).append(';')
                for (k in keys) {
                    writeStr(k, sb)
                    write(node.entries[k], sb, includeTombstones)
                }
            }
        }
    }

    fun canonical(node: SNode?, includeTombstones: Boolean = true): String =
        StringBuilder().also { write(node, it, includeTombstones) }.toString()

    fun fingerprint(node: SNode?, includeTombstones: Boolean = true): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonical(node, includeTombstones).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeStr(v: String, sb: StringBuilder) {
        val bytes = v.toByteArray(Charsets.UTF_8)
        sb.append(bytes.size).append(':').append(v)
    }

    fun normalizeNumber(v: BigDecimal): String {
        val stripped = v.stripTrailingZeros()
        val n = if (stripped.scale() < 0) stripped.setScale(0) else stripped
        return n.toPlainString()
    }
}
