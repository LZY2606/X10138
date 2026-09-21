package semmerge.yaml

import semmerge.model.ScalarKind
import semmerge.model.ScalarStyle
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar

/** Character-level YAML helpers: comments, scalars, flow collections. */
object YamlText {

    fun stripComment(raw: String): String {
        var inSingle = false
        var inDouble = false
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> when (c) {
                    '\\' -> k++
                    '"' -> inDouble = false
                }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '#' && (k == 0 || raw[k - 1] == ' ' || raw[k - 1] == '\t') ->
                    return raw.substring(0, k)
            }
            k++
        }
        return raw
    }

    /** Find a `: ` separator (or trailing colon) outside quotes/flow. */
    fun findColon(text: String): Int {
        var inSingle = false
        var inDouble = false
        var depth = 0
        var k = 0
        while (k < text.length) {
            val c = text[k]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> when (c) {
                    '\\' -> k++
                    '"' -> inDouble = false
                }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
                c == ':' && depth == 0 && (k == text.length - 1 || text[k + 1] == ' ' || text[k + 1] == '\t') ->
                    return k
            }
            k++
        }
        return -1
    }

    fun parsePlainOrQuotedScalar(text: String): Pair<SScalar, String> {
        var t = text
        t = t.trimStart()
        if (t.isEmpty()) return SScalar.NULL to ""
        val first = t[0]
        if (first == '\'') {
            val end = findSingleQuotedEnd(t, 0)
            val raw = decodeSingle(t.substring(1, end))
            return SScalar(ScalarKind.STRING, raw, ScalarStyle.SINGLE_QUOTED) to t.substring(end + 1)
        }
        if (first == '"') {
            val end = findDoubleQuotedEnd(t, 0)
            val raw = decodeDouble(t.substring(1, end))
            return SScalar(ScalarKind.STRING, raw, ScalarStyle.DOUBLE_QUOTED) to t.substring(end + 1)
        }
        var end = 0
        while (end < t.length && t[end] != ' ' && t[end] != '\t' &&
            t[end] != ',' && t[end] != '}' && t[end] != ']'
        ) end++
        val token = t.substring(0, end)
        return plainScalar(token) to t.substring(end)
    }

    fun plainScalar(token: String): SScalar {
        val t = token.trim()
        if (t.isEmpty() || t == "~" || t == "null" || t == "Null" || t == "NULL") {
            return SScalar(if (t.isEmpty()) ScalarKind.NULL else ScalarKind.NULL,
                if (t.isEmpty()) "" else t, ScalarStyle.PLAIN)
        }
        if (t == "true" || t == "True" || t == "TRUE" || t == "false" || t == "False" || t == "FALSE") {
            return SScalar(ScalarKind.BOOL, t, ScalarStyle.PLAIN)
        }
        if (intRegex.matches(t)) return SScalar(ScalarKind.INT, t, ScalarStyle.PLAIN)
        if (floatRegex.matches(t)) return SScalar(ScalarKind.FLOAT, t, ScalarStyle.PLAIN)
        return SScalar(ScalarKind.STRING, t, ScalarStyle.PLAIN)
    }

    private val intRegex = Regex("[-+]?[0-9]+")
    private val floatRegex = Regex("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?")

    private fun findSingleQuotedEnd(s: String, start: Int): Int {
        var k = start + 1
        while (k < s.length) {
            if (s[k] == '\'') {
                if (k + 1 < s.length && s[k + 1] == '\'') { k += 2; continue }
                return k
            }
            k++
        }
        throw YamlParseException("unterminated single-quoted scalar", -1)
    }

    private fun findDoubleQuotedEnd(s: String, start: Int): Int {
        var k = start + 1
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '"' -> return k
            }
            k++
        }
        throw YamlParseException("unterminated double-quoted scalar", -1)
    }

    private fun decodeSingle(raw: String): String = raw.replace("''", "'")

    private fun decodeDouble(raw: String): String {
        val sb = StringBuilder()
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            if (c != '\\') { sb.append(c); k++; continue }
            k++
            if (k >= raw.length) break
            when (val e = raw[k]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '0' -> sb.append('\u0000')
                'u' -> {
                    val hex = raw.substring(k + 1, minOf(k + 5, raw.length))
                    if (hex.length == 4) { sb.append(hex.toInt(16).toChar()); k += 4 }
                }
                else -> sb.append(e)
            }
            k++
        }
        return sb.toString()
    }

    /** Parse a flow value starting at `s[start] in '{[' or a scalar; returns node and index just past it. */
    fun readFlow(s: String, start: Int): Pair<SNode, Int> {
        var k = start
        while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
        return when (s[k]) {
            '{' -> readFlowMap(s, k)
            '[' -> readFlowSeq(s, k)
            '\'' -> {
                val end = findSingleQuotedEnd(s, k)
                SScalar(ScalarKind.STRING, decodeSingle(s.substring(k + 1, end)), ScalarStyle.SINGLE_QUOTED) to end + 1
            }
            '"' -> {
                val end = findDoubleQuotedEnd(s, k)
                SScalar(ScalarKind.STRING, decodeDouble(s.substring(k + 1, end)), ScalarStyle.DOUBLE_QUOTED) to end + 1
            }
            else -> {
                var e = k
                while (e < s.length && s[e] != ',' && s[e] != '}' && s[e] != ']') e++
                val token = s.substring(k, e).trim()
                plainScalar(token) to e
            }
        }
    }

    fun readFlowMap(s: String, start: Int): Pair<SNode, Int> {
        val entries = mutableListOf<Pair<String, SNode>>()
        var k = start + 1
        while (true) {
            while (k < s.length && (s[k] == ' ' || s[k] == '\t' || s[k] == '\n')) k++
            if (s[k] == '}') return SMap(entries) to k + 1
            val (keyNode, afterKey) = readFlow(s, k)
            val key = (keyNode as? SScalar)?.let { scalarString(it) }
                ?: throw YamlParseException("flow map key must be scalar", -1)
            k = afterKey
            while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
            if (k >= s.length || s[k] != ':') throw YamlParseException("expected ':' in flow map", -1)
            k++
            while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
            val (value, afterValue) = readFlow(s, k)
            entries += key to value
            k = afterValue
            while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
            when {
                k < s.length && s[k] == ',' -> { k++; continue }
                k < s.length && s[k] == '}' -> return SMap(entries) to k + 1
                else -> throw YamlParseException("unterminated flow map", -1)
            }
        }
    }

    fun readFlowSeq(s: String, start: Int): Pair<SNode, Int> {
        val items = mutableListOf<SNode>()
        var k = start + 1
        while (true) {
            while (k < s.length && (s[k] == ' ' || s[k] == '\t' || s[k] == '\n')) k++
            if (s[k] == ']') return SList(items) to k + 1
            val (value, afterValue) = readFlow(s, k)
            items += value
            k = afterValue
            while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
            when {
                k < s.length && s[k] == ',' -> { k++; continue }
                k < s.length && s[k] == ']' -> return SList(items) to k + 1
                else -> throw YamlParseException("unterminated flow sequence", -1)
            }
        }
    }

    fun scalarString(s: SScalar): String = when (s.kind) {
        ScalarKind.NULL -> "null"
        else -> s.text
    }
}
