package merger

import java.math.BigDecimal

/**
 * 结果输出。两种格式都手写发射：
 *  - 不使用 YAML emitter 的锚点/别名机制，别名在解析阶段已展开为独立副本，
 *    输出中绝不会产生共享引用或 *anchor；
 *  - 稳定序列化：同一模型输出字节级一致（保留数组与键的合并顺序）。
 */
object Emitter {

    fun toJson(node: SNode, pretty: Boolean = true, indentStep: Int = 2): String {
        val sb = StringBuilder()
        emitJson(node, sb, 0, pretty, indentStep)
        sb.append('\n')
        return sb.toString()
    }

    private fun emitJson(node: SNode, sb: StringBuilder, depth: Int, pretty: Boolean, step: Int) {
        when (node) {
            is SSDelete, is SSMissing -> sb.append("null") // 墓碑在物化阶段已剔除；兜底
            is SSNull -> sb.append("null")
            is SSBool -> sb.append(node.value.toString())
            is SSString -> writeJsonString(node.value, sb)
            is SSNumber -> sb.append(Canonical.normalizeNumber(node.value))
            is SSeq -> {
                if (node.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                node.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (pretty) sb.append('\n').append(" ".repeat((depth + 1) * step))
                    emitJson(item, sb, depth + 1, pretty, step)
                }
                if (pretty) sb.append('\n').append(" ".repeat(depth * step))
                sb.append(']')
            }
            is SMap -> {
                if (node.entries.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                node.entries.entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    if (pretty) sb.append('\n').append(" ".repeat((depth + 1) * step))
                    writeJsonString(e.key, sb)
                    sb.append(if (pretty) ": " else ":")
                    emitJson(e.value, sb, depth + 1, pretty, step)
                }
                if (pretty) sb.append('\n').append(" ".repeat(depth * step))
                sb.append('}')
            }
        }
    }

    private fun writeJsonString(v: String, sb: StringBuilder) {
        sb.append('"')
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun toYaml(root: SNode): String {
        val sb = StringBuilder()
        emitYaml(root, sb, indent = 0, inSeq = false, atSeqItem = false)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        return sb.toString()
    }

    private val YAML_NULL_LIKE = Regex("(null|Null|NULL|~|true|True|TRUE|false|False|FALSE|-?[0-9].*|-.*|[\\[\\]{}>!&*|?'\\\"#%@`,].*)")

    private fun yamlKey(k: String, sb: StringBuilder) {
        when {
            k.isEmpty() -> sb.append("\"\"")
            needsYamlQuote(k) || k.startsWith("- ") -> writeDoubleQuoted(k, sb)
            else -> sb.append(k)
        }
    }

    private fun needsYamlQuote(v: String): Boolean {
        if (v == "null" || v == "~" || v == "true" || v == "false") return true
        if (v.startsWith(' ') || v.endsWith(' ')) return true
        if (YAML_NULL_LIKE.matches(v)) {
            // 数字形态或会被隐式解析的形态需要引号
            if (v.toBigDecimalOrNull() != null) return true
            if (v.startsWith("-") && v.drop(1).trim().toBigDecimalOrNull() != null) return true
        }
        if (v.any { it.code <= 0x1f }) return true
        return false
    }

    private fun writeDoubleQuoted(v: String, sb: StringBuilder) {
        sb.append('"')
        for (c in v) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        sb.append('"')
    }

    /**
     * 块式 YAML。容器在键行发射 ": " 后按缩进递归；标量走 flow/引号规则。
     */
    private fun emitYaml(node: SNode, sb: StringBuilder, indent: Int, inSeq: Boolean, atSeqItem: Boolean) {
        when (node) {
            is SSDelete, is SSMissing -> sb.append("null\n")
            is SSNull -> sb.append("null\n")
            is SSBool -> sb.append(node.value.toString()).append('\n')
            is SSString -> {
                if (node.value.isEmpty()) sb.append("\"\"")
                else if (needsYamlQuote(node.value)) writeDoubleQuoted(node.value, sb)
                else if (node.value.contains('\n')) emitBlockScalar(node.value, sb, indent)
                else sb.append(node.value)
                sb.append('\n')
            }
            is SSNumber -> { sb.append(Canonical.normalizeNumber(node.value)); sb.append('\n') }
            is SSeq -> emitSeq(node, sb, indent)
            is SMap -> emitMap(node, sb, indent)
        }
    }

    private fun emitBlockScalar(v: String, sb: StringBuilder, indent: Int) {
        val lines = v.split('\n')
        sb.append("|-\n")
        for (line in lines) {
            sb.append(" ".repeat(indent + 2))
            sb.append(line).append('\n')
        }
    }

    private fun emitSeq(node: SSeq, sb: StringBuilder, indent: Int) {
        if (node.items.isEmpty()) { sb.append("[]\n"); return }
        node.items.forEach { item ->
            sb.append(" ".repeat(indent)).append("- ")
            when (item) {
                is SMap -> {
                    if (item.entries.isEmpty()) sb.append("{}\n")
                    else {
                        // 第一项与 "- " 同行，其余项缩进 indent+2
                        emitMapInline(item, sb, indent + 2)
                    }
                }
                is SSeq -> {
                    if (item.items.isEmpty()) sb.append("[]\n")
                    else {
                        sb.append('\n')
                        emitSeq(item, sb, indent + 2)
                    }
                }
                else -> emitYaml(item, sb, indent + 2, inSeq = true, atSeqItem = true)
            }
        }
    }

    private fun emitMap(node: SMap, sb: StringBuilder, indent: Int) {
        if (node.entries.isEmpty()) { sb.append("{}\n"); return }
        for ((k, v) in node.entries) {
            sb.append(" ".repeat(indent))
            yamlKey(k, sb)
            sb.append(':')
            emitValue(v, sb, indent)
        }
    }

    private fun emitMapInline(node: SMap, sb: StringBuilder, indent: Int) {
        var first = true
        for ((k, v) in node.entries) {
            if (first) {
                first = false
                sb.append(' ')
            } else {
                sb.append(" ".repeat(indent))
            }
            yamlKey(k, sb)
            sb.append(':')
            emitValue(v, sb, indent)
        }
    }

    private fun emitValue(v: SNode, sb: StringBuilder, indent: Int) {
        when (v) {
            is SMap -> {
                if (v.entries.isEmpty()) sb.append(" {}\n")
                else { sb.append('\n'); emitMap(v, sb, indent + 2) }
            }
            is SSeq -> {
                if (v.items.isEmpty()) sb.append(" []\n")
                else { sb.append('\n'); emitSeq(v, sb, indent + 2) }
            }
            is SSString -> {
                sb.append(' ')
                emitYaml(v, sb, indent + 2, false, false)
            }
            else -> { sb.append(' '); emitYaml(v, sb, indent + 2, false, false) }
        }
    }
}
