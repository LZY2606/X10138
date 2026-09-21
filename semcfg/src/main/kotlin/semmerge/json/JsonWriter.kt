package semmerge.json

import semmerge.model.ScalarKind
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar

/**
 * Stable JSON serialization: fixed 2-space indent, LF newlines, and — when
 * [sortKeys] is on — object keys emitted in lexicographic order. Map entry
 * order never matters for fingerprints and must never cause spurious diffs.
 */
object JsonWriter {
    fun write(node: SNode, sortKeys: Boolean = false, indent: Int = 2): String {
        val sb = StringBuilder()
        emit(sb, node, 0, indent, sortKeys)
        sb.append('\n')
        return sb.toString()
    }

    private fun emit(sb: StringBuilder, node: SNode, depth: Int, unit: Int, sort: Boolean) {
        when (node) {
            is SScalar -> sb.append(scalarToJson(node))
            is SList -> {
                if (node.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                val child = depth + 1
                node.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    sb.append('\n').append(pad(child * unit))
                    emit(sb, item, child, unit, sort)
                }
                sb.append('\n').append(pad(depth * unit)).append(']')
            }
            is SMap -> {
                if (node.size == 0) { sb.append("{}"); return }
                val entries = if (sort) node.entries().sortedBy { it.first } else node.entries()
                sb.append('{')
                val child = depth + 1
                entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    sb.append('\n').append(pad(child * unit))
                    emitString(sb, k)
                    sb.append(": ")
                    emit(sb, v, child, unit, sort)
                }
                sb.append('\n').append(pad(depth * unit)).append('}')
            }
        }
    }

    fun scalarToJson(s: SScalar): String = when (s.kind) {
        ScalarKind.NULL -> "null"
        ScalarKind.BOOL -> s.text
        ScalarKind.INT, ScalarKind.FLOAT -> s.text
        ScalarKind.STRING -> buildString { emitString(this, s.text) }
    }

    fun emitString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) {
                sb.append("\\u")
                val hex = c.code.toString(16)
                repeat(4 - hex.length) { sb.append('0') }
                sb.append(hex)
            } else sb.append(c)
        }
        sb.append('"')
    }

    private fun pad(n: Int): String = " ".repeat(n)
}
