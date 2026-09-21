package merger

import java.math.BigDecimal

/**
 * 极简 JSON 值模型与解析/序列化器，用于 HTTP API 与持久化。
 * 仅支持标准 JSON 对象/数组/字符串/数字/布尔/null。
 */
sealed class JV {
    data class Obj(val map: LinkedHashMap<String, JV> = LinkedHashMap()) : JV() {
        operator fun get(k: String): JV? = map[k]
        fun str(k: String): String? = (map[k] as? Str)?.value
        fun int(k: String): Int? = (map[k] as? Num)?.value?.toBigInteger()?.toInt()
        fun bool(k: String): Boolean? = (map[k] as? Bool)?.value
        fun obj(k: String): Obj? = map[k] as? Obj
        fun arr(k: String): Arr? = map[k] as? Arr
    }
    data class Arr(val list: List<JV>) : JV()
    data class Str(val value: String) : JV()
    data class Num(val value: BigDecimal) : JV()
    data class Bool(val value: Boolean) : JV()
    data object Null : JV()
}

class JParseException(message: String) : RuntimeException(message)

object JsonCodec {

    fun parse(input: String): JV {
        val p = Parser(input)
        p.ws()
        val v = p.value()
        p.ws()
        if (!p.eof()) throw JParseException("JSON 尾部存在多余字符 at ${p.pos}")
        return v
    }

    fun stringify(v: JV, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(v, sb, 0, pretty)
        sb.append('\n')
        return sb.toString()
    }

    private fun write(v: JV, sb: StringBuilder, depth: Int, pretty: Boolean) {
        when (v) {
            JV.Null -> sb.append("null")
            is JV.Bool -> sb.append(v.value.toString())
            is JV.Num -> sb.append(Canonical.normalizeNumber(v.value))
            is JV.Str -> writeStr(v.value, sb)
            is JV.Arr -> {
                if (v.list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.list.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    if (pretty) sb.append('\n').append("  ".repeat(depth + 1))
                    write(e, sb, depth + 1, pretty)
                }
                if (pretty) sb.append('\n').append("  ".repeat(depth))
                sb.append(']')
            }
            is JV.Obj -> {
                if (v.map.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                v.map.entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    if (pretty) sb.append('\n').append("  ".repeat(depth + 1))
                    writeStr(e.key, sb); sb.append(if (pretty) ": " else ":")
                    write(e.value, sb, depth + 1, pretty)
                }
                if (pretty) sb.append('\n').append("  ".repeat(depth))
                sb.append('}')
            }
        }
    }

    private fun writeStr(v: String, sb: StringBuilder) {
        sb.append('"')
        for (c in v) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    // ---- SNode <-> JV ----

    fun toJV(node: SNode?): JV = when (node) {
        null, is SSMissing -> JV.Null
        is SSDelete -> JV.Obj(linkedMapOf(ConfigParser.DELETE_KEY to JV.Bool(true)))
        is SSNull -> JV.Null
        is SSString -> JV.Str(node.value)
        is SSBool -> JV.Bool(node.value)
        is SSNumber -> JV.Num(node.value)
        is SSeq -> JV.Arr(node.items.map { toJV(it) })
        is SMap -> JV.Obj(LinkedHashMap(node.entries.mapValues { toJV(it.value) }))
    }

    fun fromJV(jv: JV, format: Format = Format.JSON): SNode {
        val doc = ConfigParser.parse(stringify(jv, pretty = false), Source.A, format)
        return doc.root
    }

    private class Parser(val s: String) {
        var pos = 0
        fun eof(): Boolean = pos >= s.length
        fun ws() { while (!eof() && s[pos].isWhitespace()) pos++ }

        fun value(): JV {
            ws()
            if (eof()) throw JParseException("意外结束")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JV.Str(str())
                't', 'f' -> bool()
                'n' -> nul()
                else -> num()
            }
        }

        fun obj(): JV.Obj {
            expect('{'); ws()
            val m = LinkedHashMap<String, JV>()
            if (peek() == '}') { pos++; return JV.Obj(m) }
            while (true) {
                ws(); val k = str(); ws(); expect(':')
                val v = value(); m[k] = v; ws()
                when (peek()) { ',' -> { pos++; continue } '}' -> { pos++; break }
                    else -> throw JParseException("对象缺少逗号 at $pos") }
            }
            return JV.Obj(m)
        }

        fun arr(): JV.Arr {
            expect('['); ws()
            val l = mutableListOf<JV>()
            if (peek() == ']') { pos++; return JV.Arr(l) }
            while (true) {
                l.add(value()); ws()
                when (peek()) { ',' -> { pos++; continue } ']' -> { pos++; break }
                    else -> throw JParseException("数组缺少逗号 at $pos") }
            }
            return JV.Arr(l)
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JParseException("字符串未闭合")
                val c = s[pos++]
                when (c) {
                    '"' -> break
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                val hex = s.substring(pos, pos + 4); pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JParseException("非法转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun bool(): JV.Bool = when {
            s.startsWith("true", pos) -> { pos += 4; JV.Bool(true) }
            s.startsWith("false", pos) -> { pos += 5; JV.Bool(false) }
            else -> throw JParseException("非法布尔 at $pos")
        }

        fun nul(): JV {
            if (!s.startsWith("null", pos)) throw JParseException("非法字面量 at $pos")
            pos += 4; return JV.Null
        }

        fun num(): JV.Num {
            val start = pos
            if (peek() == '-') pos++
            while (!eof() && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val txt = s.substring(start, pos)
            return try { JV.Num(BigDecimal(txt)) } catch (e: Exception) {
                throw JParseException("非法数字 $txt")
            }
        }

        fun peek(): Char = if (eof()) '\u0000' else s[pos]
        fun expect(c: Char) { ws(); if (peek() != c) throw JParseException("期望 '$c' at $pos"); pos++ }
    }
}
