package merger

/**
 * 结果树 -> JSON 文本。删除节点由上层在构建结果时剔除，不会出现在这里。
 */
object JsonWriter {
    fun write(node: Node, indent: Int = 2): String {
        val sb = StringBuilder()
        writeNode(sb, node, 0, indent)
        sb.append('\n')
        return sb.toString()
    }

    private fun writeNode(sb: StringBuilder, node: Node, depth: Int, indent: Int) {
        when (node) {
            is Node.Scalar -> writeScalar(sb, node.value)
            is Node.Obj -> writeObj(sb, node, depth, indent)
            is Node.Arr -> writeArr(sb, node, depth, indent)
        }
    }

    private fun writeScalar(sb: StringBuilder, v: ScalarValue) {
        when (v) {
            is ScalarValue.StrVal -> sb.append(JsonText.quote(v.value))
            is ScalarValue.BoolVal -> sb.append(if (v.value) "true" else "false")
            is ScalarValue.NumVal -> sb.append(normalizeNumber(v))
            ScalarValue.NullVal -> sb.append("null")
        }
    }

    private fun pad(sb: StringBuilder, depth: Int, indent: Int) {
        repeat(depth * indent) { sb.append(' ') }
    }

    private fun writeObj(sb: StringBuilder, node: Node.Obj, depth: Int, indent: Int) {
        if (node.children.isEmpty()) { sb.append("{}"); return }
        sb.append("{\n")
        node.children.entries.forEachIndexed { i, (k, v) ->
            pad(sb, depth + 1, indent)
            sb.append(JsonText.quote(k)).append(": ")
            writeNode(sb, v, depth + 1, indent)
            if (i < node.children.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, depth, indent)
        sb.append('}')
    }

    private fun writeArr(sb: StringBuilder, node: Node.Arr, depth: Int, indent: Int) {
        if (node.items.isEmpty()) { sb.append("[]"); return }
        sb.append("[\n")
        node.items.forEachIndexed { i, v ->
            pad(sb, depth + 1, indent)
            writeNode(sb, v, depth + 1, indent)
            if (i < node.items.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, depth, indent)
        sb.append(']')
    }

    /** 输出稳定数字：整数值不带小数点，浮点保留原文规范形式。 */
    fun normalizeNumber(v: ScalarValue.NumVal): String {
        val bd = v.value
        return if (bd.signum() == 0 || bd.stripTrailingZeros().scale() <= 0) {
            bd.toBigIntegerExact().toString()
        } else {
            bd.stripTrailingZeros().toPlainString()
        }
    }
}
