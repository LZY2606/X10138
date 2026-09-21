package merger

/**
 * 结果树 -> YAML 文本。永远不输出锚点/别名，因此再导入也不会产生共享引用。
 */
object YamlWriter {
    fun write(node: Node): String {
        val sb = StringBuilder()
        writeNode(sb, node, 0, root = true)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, depth: Int) = repeat(depth * 2) { sb.append(' ') }

    private fun writeNode(sb: StringBuilder, node: Node, depth: Int, root: Boolean = false) {
        when (node) {
            is Node.Scalar -> sb.append(renderScalar(node.value))
            is Node.Obj -> writeObj(sb, node, depth, root)
            is Node.Arr -> writeArr(sb, node, depth, root)
        }
    }

    private fun writeObj(sb: StringBuilder, node: Node.Obj, depth: Int, root: Boolean) {
        if (node.children.isEmpty()) {
            if (root) sb.append("{}\n")
            return
        }
        node.children.entries.forEachIndexed { idx, (k, v) ->
            indent(sb, depth)
            sb.append(renderKey(k)).append(':')
            when (v) {
                is Node.Scalar -> {
                    sb.append(' ').append(renderScalar(v.value)).append('\n')
                }
                is Node.Obj -> {
                    if (v.children.isEmpty()) sb.append(" {}\n")
                    else { sb.append('\n'); writeObj(sb, v, depth + 1, false) }
                }
                is Node.Arr -> {
                    if (v.items.isEmpty()) sb.append(" []\n")
                    else { sb.append('\n'); writeArr(sb, v, depth + 1, false) }
                }
            }
        }
    }

    private fun writeArr(sb: StringBuilder, node: Node.Arr, depth: Int, root: Boolean) {
        if (node.items.isEmpty()) {
            if (root) sb.append("[]\n")
            return
        }
        for (item in node.items) {
            indent(sb, depth)
            sb.append("- ")
            when (item) {
                is Node.Scalar -> sb.append(renderScalar(item.value)).append('\n')
                is Node.Obj -> {
                    if (item.children.isEmpty()) {
                        sb.append("{}\n")
                    } else {
                        // 首个键放在 "- " 之后，其余键对齐。
                        val first = item.children.entries.first()
                        sb.append(renderKey(first.key)).append(':')
                        writeInlineChild(sb, first.value, depth + 1)
                        item.children.entries.drop(1).forEach { (k, v) ->
                            indent(sb, depth + 1)
                            sb.append(renderKey(k)).append(':')
                            writeInlineChild(sb, v, depth + 2)
                        }
                    }
                }
                is Node.Arr -> {
                    sb.append('\n')
                    writeArr(sb, item, depth + 1, false)
                }
            }
        }
    }

    private fun writeInlineChild(sb: StringBuilder, v: Node, childDepth: Int) {
        when (v) {
            is Node.Scalar -> sb.append(' ').append(renderScalar(v.value)).append('\n')
            is Node.Obj -> if (v.children.isEmpty()) sb.append(" {}\n") else {
                sb.append('\n'); writeObj(sb, v, childDepth, false)
            }
            is Node.Arr -> if (v.items.isEmpty()) sb.append(" []\n") else {
                sb.append('\n'); writeArr(sb, v, childDepth, false)
            }
        }
    }

    private fun renderKey(k: String): String =
        if (PLAIN_KEY.matches(k) && k !in RESERVED) k else JsonText.quote(k)

    private fun renderScalar(v: ScalarValue): String = when (v) {
        is ScalarValue.StrVal -> renderString(v.value)
        is ScalarValue.BoolVal -> if (v.value) "true" else "false"
        is ScalarValue.NumVal -> JsonWriter.normalizeNumber(v)
        ScalarValue.NullVal -> "null"
    }

    private val PLAIN_KEY = Regex("[A-Za-z0-9_./-]+")
    private val RESERVED = setOf("true", "false", "null", "yes", "no", "~")

    /**
     * 稳定、可逆的标量输出：含特殊字符或看起来像其它类型时加引号。
     */
    private fun renderString(s: String): String {
        if (s.isEmpty()) return "\"\""
        if (s.contains('\n')) return renderBlock(s)
        val needsQuote = s != s.trim() ||
            s.startsWith("#") ||
            s.startsWith("- ") ||
            s.startsWith("? ") ||
            s.startsWith(": ") ||
            s.startsWith("*") ||
            s.startsWith("&") ||
            s.startsWith("!") ||
            s.startsWith("|") ||
            s.startsWith(">") ||
            s.startsWith("'") ||
            s.startsWith("\"") ||
            s.startsWith("{") ||
            s.startsWith("[") ||
            s in RESERVED ||
            s == "~" ||
            looksLikeNumber(s) ||
            s.contains(" #") ||
            s.endsWith(':')
        return if (needsQuote) JsonText.quote(s) else s
    }

    private fun looksLikeNumber(s: String): Boolean {
        if (s.matches(Regex("-?[0-9]+"))) return true
        if (s.matches(Regex("-?[0-9]+\\.[0-9]+"))) return true
        if (s.matches(Regex("0x[0-9a-fA-F]+"))) return true
        return false
    }

    private fun renderBlock(s: String): String {
        val useClip = !s.endsWith("\n")
        val body = if (useClip) s.trimEnd('\n') else s.trimEnd('\n') + "\n"
        val lines = body.split('\n')
        val sb = StringBuilder("|-\n")
        for (l in lines) {
            sb.append("  ").append(l).append('\n')
        }
        return sb.toString().removeSuffix("\n")
    }
}
