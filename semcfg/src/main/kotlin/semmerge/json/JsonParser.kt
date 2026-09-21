package semmerge.json

import semmerge.model.ScalarKind
import semmerge.model.ScalarStyle
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar

class JsonParseException(message: String, val offset: Int) : RuntimeException("$message (at char $offset)")

object JsonParser {
    fun parse(text: String): SNode {
        val p = Cursor(text)
        p.ws()
        val node = p.value()
        p.ws()
        if (!p.eof) throw JsonParseException("trailing content after document", p.pos)
        return node
    }

    private class Cursor(val s: String) {
        var pos = 0
        val eof: Boolean get() = pos >= s.length

        fun ws() {
            while (!eof) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else return
            }
        }

        fun expect(c: Char) {
            if (eof || s[pos] != c) throw JsonParseException("expected '$c'", pos)
            pos++
        }

        fun value(): SNode {
            ws()
            if (eof) throw JsonParseException("unexpected end of document", pos)
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> SScalar(ScalarKind.STRING, string(), ScalarStyle.DOUBLE_QUOTED)
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> nul()
                else -> number()
            }
        }

        private fun literal(word: String, value: Boolean): SScalar {
            if (!s.startsWith(word, pos)) throw JsonParseException("invalid literal", pos)
            pos += word.length
            return SScalar(ScalarKind.BOOL, word, ScalarStyle.PLAIN)
        }

        private fun nul(): SScalar {
            if (!s.startsWith("null", pos)) throw JsonParseException("invalid literal", pos)
            pos += 4
            return SScalar.NULL
        }

        private fun number(): SScalar {
            val start = pos
            if (!eof && (s[pos] == '-' || s[pos] == '+')) pos++
            var dot = false
            var exp = false
            var digit = false
            while (!eof) {
                val c = s[pos]
                when {
                    c.isDigit() -> { digit = true; pos++ }
                    c == '.' -> {
                        if (dot || exp) throw JsonParseException("invalid number", pos)
                        dot = true; pos++
                    }
                    c == 'e' || c == 'E' -> {
                        if (exp) throw JsonParseException("invalid number", pos)
                        exp = true; pos++
                        if (!eof && (s[pos] == '+' || s[pos] == '-')) pos++
                    }
                    else -> break
                }
            }
            if (!digit || pos == start) throw JsonParseException("invalid token", start)
            val raw = s.substring(start, pos)
            val kind = if (dot || exp) ScalarKind.FLOAT else ScalarKind.INT
            return SScalar(kind, raw, ScalarStyle.PLAIN)
        }

        fun obj(): SNode {
            expect('{')
            val entries = mutableListOf<Pair<String, SNode>>()
            val seen = HashSet<String>()
            ws()
            if (!eof && s[pos] == '}') { pos++; return SMap(entries) }
            while (true) {
                ws()
                val key = string()
                if (!seen.add(key)) throw JsonParseException("duplicate key '$key'", pos)
                ws()
                expect(':')
                val v = value()
                entries += key to v
                ws()
                when {
                    !eof && s[pos] == ',' -> { pos++; ws(); if (!eof && s[pos] == '}') throw JsonParseException("trailing comma", pos) }
                    !eof && s[pos] == '}' -> { pos++; return SMap(entries) }
                    else -> throw JsonParseException("expected ',' or '}'", pos)
                }
            }
        }

        fun arr(): SNode {
            expect('[')
            val items = mutableListOf<SNode>()
            ws()
            if (!eof && s[pos] == ']') { pos++; return SList(items) }
            while (true) {
                items += value()
                ws()
                when {
                    !eof && s[pos] == ',' -> { pos++; ws(); if (!eof && s[pos] == ']') throw JsonParseException("trailing comma", pos) }
                    !eof && s[pos] == ']' -> { pos++; return SList(items) }
                    else -> throw JsonParseException("expected ',' or ']'", pos)
                }
            }
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof) throw JsonParseException("unterminated string", pos)
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof) throw JsonParseException("bad escape", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("bad unicode escape", pos)
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonParseException("invalid escape \\$e", pos - 1)
                        }
                    }
                    '\n', '\r', '\t' -> throw JsonParseException("unescaped control character", pos - 1)
                    else -> sb.append(c)
                }
            }
        }
    }
}
