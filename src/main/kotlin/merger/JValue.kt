package merger

/**
 * 轻量 JSON 值模型，供 API、持久化、导入导出复用。
 */
sealed class JValue {
    operator fun get(key: String): JValue? = (this as? JObj)?.map?.get(key)

    data object JNull : JValue()
    data class JBool(val value: Boolean) : JValue()
    data class JStr(val value: String) : JValue()
    data class JNum(val value: String) : JValue()
    class JObj(val map: LinkedHashMap<String, JValue> = LinkedHashMap()) : JValue() {
        fun put(key: String, v: JValue?): JObj { if (v != null) map[key] = v; return this }
    }
    class JArr(val items: MutableList<JValue> = mutableListOf()) : JValue()

    fun asObj(): JObj = this as JObj
    fun asArr(): JArr = this as JArr
    fun asStr(): String = (this as JStr).value
    fun asLong(): Long = (this as JNum).value.toLong()
    fun asBool(): Boolean = (this as JBool).value
    fun strOr(default: String): String = (this as? JStr)?.value ?: default
    fun objOrNull(): JObj? = this as? JObj
}

object JValues {
    fun obj(vararg pairs: Pair<String, JValue?>): JValue.JObj {
        val o = JValue.JObj()
        pairs.forEach { (k, v) -> o.put(k, v) }
        return o
    }
    fun arr(items: List<JValue>): JValue.JArr = JValue.JArr(items.toMutableList())
    fun str(s: String): JValue.JStr = JValue.JStr(s)
    fun num(n: Number): JValue.JNum = JValue.JNum(n.toString())
    val NULL: JValue.JNull get() = JValue.JNull
}

/**
 * JValue <-> 文本 JSON（手写，避免外部依赖）。
 */
object JsonCodec {
    fun encode(v: JValue, indent: Int = 2): String {
        val sb = StringBuilder()
        write(sb, v, 0, indent)
        sb.append('\n')
        return sb.toString()
    }

    private fun pad(sb: StringBuilder, depth: Int, indent: Int) = repeat(depth * indent) { sb.append(' ') }

    private fun write(sb: StringBuilder, v: JValue, depth: Int, indent: Int) {
        when (v) {
            JValue.JNull -> sb.append("null")
            is JValue.JBool -> sb.append(if (v.value) "true" else "false")
            is JValue.JStr -> sb.append(JsonText.quote(v.value))
            is JValue.JNum -> sb.append(v.value)
            is JValue.JObj -> {
                if (v.map.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                v.map.entries.forEachIndexed { i, e ->
                    pad(sb, depth + 1, indent)
                    sb.append(JsonText.quote(e.key)).append(": ")
                    write(sb, e.value, depth + 1, indent)
                    if (i < v.map.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, depth, indent)
                sb.append('}')
            }
            is JValue.JArr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.items.forEachIndexed { i, e ->
                    pad(sb, depth + 1, indent)
                    write(sb, e, depth + 1, indent)
                    if (i < e.let { v.items.size } - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, depth, indent)
                sb.append(']')
            }
        }
    }

    fun decode(text: String): JValue = JDecoder(text).decode()

    private class JDecoder(val s: String) {
        var p = 0
        fun decode(): JValue {
            ws()
            val v = value()
            ws()
            require(p == s.length) { "JSON 尾部多余字符" }
            return v
        }
        private fun ws() { while (p < s.length && s[p].isWhitespace()) p++ }
        private fun value(): JValue {
            ws()
            return when (s[p]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JValue.JStr(str())
                't' -> lit("true", JValue.JBool(true))
                'f' -> lit("false", JValue.JBool(false))
                'n' -> lit("null", JValue.JNull)
                else -> num()
            }
        }
        private fun lit(t: String, v: JValue): JValue {
            require(s.startsWith(t, p)) { "非法字面量" }
            p += t.length
            return v
        }
        private fun obj(): JValue.JObj {
            p++
            val o = JValue.JObj()
            ws()
            if (s[p] == '}') { p++; return o }
            while (true) {
                ws()
                val k = str()
                ws(); require(s[p] == ':'); p++
                ws()
                o.map[k] = value()
                ws()
                when (s[p]) { ',' -> { p++; continue }
                    '}' -> { p++; break }
                    else -> error("非法 JSON 对象")
                }
            }
            return o
        }
        private fun arr(): JValue.JArr {
            p++
            val a = JValue.JArr()
            ws()
            if (s[p] == ']') { p++; return a }
            while (true) {
                ws()
                a.items.add(value())
                ws()
                when (s[p]) { ',' -> { p++; continue }
                    ']' -> { p++; break }
                    else -> error("非法 JSON 数组")
                }
            }
            return a
        }
        private fun str(): String {
            require(s[p] == '"')
            p++
            val sb = StringBuilder()
            while (true) {
                val c = s[p++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[p++]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'u' -> {
                            val code = s.substring(p, p + 4).toInt(16); p += 4
                            sb.append(code.toChar())
                        }
                        else -> error("非法转义: $e")
                    }
                    else -> sb.append(c)
                }
            }
        }
        private fun num(): JValue.JNum {
            val start = p
            if (s[p] == '-') p++
            while (p < s.length && (s[p].isDigit() || s[p] in ".eE+-")) p++
            return JValue.JNum(s.substring(start, p))
        }
    }
}
