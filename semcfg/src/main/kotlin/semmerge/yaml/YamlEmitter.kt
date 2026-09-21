package semmerge.yaml

import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind

/** Canonical YAML output: 2-space indent, block style, no anchors. */
object YamlEmitter {
    fun emit(node: SNode): String {
        val sb = StringBuilder()
        emitNode(sb, node, 0, atLineStart = true, root = true)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        return sb.toString()
    }

    private fun emitNode(sb: StringBuilder, node: SNode, indent: Int, atLineStart: Boolean, root: Boolean) {
        when (node) {
            is SScalar -> sb.append(scalarYaml(node))
            is SMap -> {
                if (node.size == 0) { sb.append("{}"); return }
                if (!atLineStart) sb.append('\n')
                for ((k, v) in node.entries()) {
                    sb.append(" ".repeat(indent)).append(keyYaml(k)).append(':')
                    emitChild(sb, v, indent + 2)
                }
            }
            is SList -> {
                if (node.items.isEmpty()) { sb.append("[]"); return }
                if (!atLineStart) sb.append('\n')
                for (item in node.items) {
                    sb.append(" ".repeat(indent)).append("-")
                    emitSeqChild(sb, item, indent)
                }
            }
        }
    }

    private fun emitChild(sb: StringBuilder, v: SNode, indent: Int) {
        when (v) {
            is SScalar -> { sb.append(' ').append(scalarYaml(v)).append('\n') }
            is SMap -> {
                if (v.size == 0) { sb.append(" {}\n"); return }
                sb.append(' ')
                val first = true
                emitInlineMap(sb, v, indent)
            }
            is SList -> {
                if (v.items.isEmpty()) { sb.append(" []\n"); return }
                sb.append('\n')
                emitNode(sb, v, indent, atLineStart = true, root = false)
            }
        }
    }

    private fun emitInlineMap(sb: StringBuilder, map: SMap, indent: Int) {
        val entries = map.entries()
        val (k0, v0) = entries[0]
        sb.append(keyYaml(k0)).append(':')
        when (v0) {
            is SScalar -> sb.append(' ').append(scalarYaml(v0)).append('\n')
            is SMap -> {
                if (v0.size == 0) sb.append(" {}\n")
                else { sb.append(' '); emitInlineMap(sb, v0, indent + 2) }
            }
            is SList -> {
                if (v0.items.isEmpty()) sb.append(" []\n")
                else { sb.append('\n'); emitNode(sb, v0, indent, atLineStart = true, root = false) }
            }
        }
        for (i in 1 until entries.size) {
            val (k, v) = entries[i]
            sb.append(" ".repeat(indent)).append(keyYaml(k)).append(':')
            emitChild(sb, v, indent + 2)
        }
    }

    private fun emitSeqChild(sb: StringBuilder, item: SNode, dashIndent: Int) {
        when (item) {
            is SScalar -> { sb.append(' ').append(scalarYaml(item)).append('\n') }
            is SMap -> {
                if (item.size == 0) { sb.append(" {}\n"); return }
                sb.append(' ')
                emitInlineMap(sb, item, dashIndent + 2)
            }
            is SList -> {
                if (item.items.isEmpty()) { sb.append(" []\n"); return }
                sb.append('\n')
                emitNode(sb, item, dashIndent + 2, atLineStart = true, root = false)
            }
        }
    }

    private fun keyYaml(key: String): String =
        if (needsQuote(key)) quote(key) else key

    private fun scalarYaml(s: SScalar): String = when (s.kind) {
        ScalarKind.NULL -> "null"
        ScalarKind.BOOL -> s.text
        ScalarKind.INT -> s.text
        ScalarKind.FLOAT -> s.text
        ScalarKind.STRING -> {
            if (s.text.contains('\n')) blockString(s.text)
            else if (needsQuote(s.text)) quote(s.text)
            else s.text
        }
    }

    private fun blockString(text: String): String {
        val t = if (text.endsWith("\n")) text.trimEnd('\n') else text
        val lines = t.split("\n")
        val sb = StringBuilder("|\n")
        for (l in lines) sb.append("  ").append(l).append('\n')
        return sb.toString().trimEnd('\n')
    }

    private val plainSafe = Regex("[A-Za-z0-9_./@%+=-]+")

    private fun needsQuote(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text != text.trim()) return true
        val first = text[0]
        if (first in "-?:,[]{}#&*!|>'\"%@`" || first.isDigit()) return true
        if (!plainSafe.matches(text)) return true
        if (text in setOf("null", "true", "false", "yes", "no", "~")) return true
        if (text.matches(Regex("[-+]?[0-9]+")) || text.matches(Regex("[-+]?[0-9.]+[eE]?[-+0-9.]*"))) return true
        if (text.contains(": ") || text.contains(" #")) return true
        return false
    }

    private fun quote(text: String): String = buildString {
        append('\'')
        for (c in text) when (c) {
            '\'' -> append("''")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('\'')
    }
}
