package merger

import java.math.BigDecimal

/**
 * YAML 子集解析器（缩进块映射/序列、flow 集合、引号标量、块标量、
 * 锚点与别名、<< 合并键、注释）。锚点/别名在解析时展开为深拷贝，
 * 不保留共享引用；每个节点带原始行列出处。
 */
class YamlParser(private val fileName: String) {
    private val anchors = LinkedHashMap<String, Node>()
    private lateinit var rawLines: List<String>
    private var cursor = 0
    private var lines: MutableList<YLine> = mutableListOf()

    private data class YLine(
        val indent: Int,
        val text: String,
        val number: Int,
        val colBase: Int = 1,
    ) {
        fun colAt(offset: Int) = colBase + indent + offset
    }

    fun parse(text: String): Node {
        rawLines = text.split('\n')
        lines = mutableListOf()
        rawLines.forEachIndexed { idx, raw ->
            val stripped = stripComment(raw)
            val indent = stripped.takeWhile { it == ' ' }.length
            val body = stripped.substring(indent)
            if (body.isNotBlank()) {
                if (body == "---" || body == "...") {
                    if (lines.isNotEmpty()) throw ParseException("$fileName:${idx + 1}:1: 不支持多文档 YAML")
                    return@forEachIndexed
                }
                lines.add(YLine(indent, body.trimEnd(), idx + 1))
            }
        }
        if (lines.isEmpty()) throw ParseException("$fileName:1:1: 空文档")
        cursor = 0
        val node = parseNode(lines[0].indent)
        if (cursor < lines.size) {
            val extra = lines[cursor]
            throw ParseException("$fileName:${extra.number}:1: 顶层出现多余内容")
        }
        return node
    }

    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '\\') i++ else if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') ->
                    return line.substring(0, i)
            }
            i++
        }
        return line
    }

    // ---------- 块结构 ----------

    private fun peekLine(): YLine? = lines.getOrNull(cursor)

    private fun parseNode(parentIndent: Int): Node {
        val line = peekLine() ?: throw ParseException("$fileName:1:1: 期望节点但文档结束")
        if (line.indent < parentIndent) {
            throw ParseException("$fileName:${line.number}:${line.colAt(0)}: 缩进不足")
        }
        return when {
            line.text.startsWith("- ") || line.text == "-" -> parseSeq(line.indent)
            line.text.startsWith("{") || line.text.startsWith("[") -> {
                cursor++
                parseScalarInline(line.text, line.number, line.colAt(0))
            }
            else -> parseMap(line.indent)
        }
    }

    private fun parseSeq(indent: Int): Node.Arr {
        val first = lines[cursor]
        val origin = Origin(Side.BASE, fileName, first.number, first.colAt(0))
        val items = mutableListOf<Node>()
        while (true) {
            val line = peekLine() ?: break
            if (line.indent != indent || (!line.text.startsWith("- ") && line.text != "-")) break
            val number = line.number
            val colBase = line.colBase
            val rest = if (line.text == "-") "" else line.text.substring(2)
            lines[cursor] = if (rest.isEmpty()) {
                cursor++
                val childLine = peekLine()
                if (childLine == null || childLine.indent <= indent) {
                    items.add(Node.Scalar(ScalarValue.NullVal, Origin(Side.BASE, fileName, number, colBase)))
                    continue
                }
                parseNode(indent + 1)
                    .also { items.add(it) }
                continue
            } else {
                YLine(
                    indent = indent + 2,
                    text = rest,
                    number = number,
                    colBase = colBase + 2,
                )
            }
            // rest 非空：可能是嵌套 map、内联标量或 flow。
            val node = when {
                isMapStart(rest) -> {
                    // 虚拟行成为嵌套映射首行；parseMap 自己推进游标。
                    parseMap(indent + 2)
                }
                else -> {
                    cursor++
                    parseScalarInline(rest, number, colBase + 2)
                }
            }
            items.add(node)
        }
        return Node.Arr(items, origin)
    }

    private fun isMapStart(text: String): Boolean {
        val keyEnd = findColon(text)
        if (keyEnd < 0) return false
        // key: value 或 key: 结尾；flow 起始不算 map。
        if (text.startsWith("{") || text.startsWith("[")) return false
        return keyEnd == text.length - 1 || text[keyEnd + 1] == ' '
    }

    private fun parseMap(indent: Int): Node.Obj {
        val first = lines[cursor]
        val origin = Origin(Side.BASE, fileName, first.number, first.colAt(0))
        val children = LinkedHashMap<String, Node>()
        val merges = mutableListOf<Node.Obj>()
        while (true) {
            val line = peekLine() ?: break
            if (line.indent != indent) break
            if (line.text.startsWith("- ") || line.text == "-") break
            val colon = findColon(line.text)
            if (colon < 0) {
                throw ParseException("$fileName:${line.number}:${line.colAt(0)}: 映射项缺少 ':'")
            }
            if (colon != line.text.length - 1 && line.text[colon + 1] != ' ') {
                throw ParseException("$fileName:${line.number}:${line.colAt(colon)}: 键中的 ':' 后需要空格")
            }
            val keyToken = line.text.substring(0, colon)
            val key = parsePlainKey(keyToken, line.number, line.colAt(0))
            val valueText = if (colon == line.text.length - 1) "" else line.text.substring(colon + 2)
            cursor++
            val childLine = peekLine()
            val value: Node = when {
                valueText.isNotEmpty() -> parseInlineOrBlock(valueText, line, childLine, indent)
                childLine != null && childLine.indent > indent -> parseNode(indent + 1)
                else -> Node.Scalar(ScalarValue.NullVal, Origin(Side.BASE, fileName, line.number, line.colAt(colon)))
            }
            if (key == "<<") {
                collectMerges(value, merges)
            } else {
                children[key] = value
            }
        }
        if (merges.isNotEmpty()) {
            val merged = LinkedHashMap<String, Node>()
            merges.forEach { obj -> obj.children.forEach { (k, v) -> merged.putIfAbsent(k, v) } }
            children.forEach { (k, v) -> merged[k] = v }
            return Node.Obj(merged, origin)
        }
        return Node.Obj(children, origin)
    }

    private fun collectMerges(value: Node, out: MutableList<Node.Obj>) {
        when (value) {
            is Node.Obj -> out.add(value)
            is Node.Arr -> value.items.forEach {
                if (it !is Node.Obj) throw ParseException("$fileName:${it.origin.line}: '<<' 合并键要求映射")
                collectMerges(it, out)
            }
            else -> throw ParseException("$fileName:${value.origin.line}: '<<' 合并键要求映射")
        }
    }

    private fun parseInlineOrBlock(
        valueText: String,
        line: YLine,
        childLine: YLine?,
        indent: Int,
    ): Node {
        var text = valueText.trimStart()
        val leading = valueText.length - text.length
        val valCol = line.colAt(line.text.length - valueText.length + leading)
        if (text == "|" || text.startsWith("|") || text == ">" || text.startsWith(">")) {
            return parseBlockScalar(line, indent, text)
        }
        if (text.startsWith("&")) {
            val sp = text.indexOf(' ')
            val name = if (sp < 0) text.substring(1) else text.substring(1, sp)
            val rest = if (sp < 0) "" else text.substring(sp + 1)
            val node: Node = if (rest.isEmpty()) {
                val child = peekLine()
                if (child != null && child.indent > indent) parseNode(indent + 1)
                else Node.Scalar(ScalarValue.NullVal, Origin(Side.BASE, fileName, line.number, valCol))
            } else {
                parseInlineValue(rest, line.number, valCol + sp + 1)
            }
            anchors[name] = node
            return node
        }
        if (text.startsWith("*")) {
            val name = text.substring(1).trim()
            val target = anchors[name]
                ?: throw ParseException("$fileName:${line.number}:${valCol}: 未定义的锚点别名 *$name")
            return target.deepCopyWithOrigin(Origin(Side.BASE, fileName, line.number, valCol))
        }
        return parseInlineValue(text, line.number, valCol)
    }

    private fun parseScalarInline(text: String, number: Int, col: Int): Node =
        parseInlineValue(text.trim(), number, col + text.length - text.trimStart().length)

    private fun parseInlineValue(text: String, number: Int, col: Int): Node {
        val origin = Origin(Side.BASE, fileName, number, col)
        val trimmed = text.trimEnd()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return FlowParser(fileName, number, col, anchors).parse(trimmed)
        }
        return Node.Scalar(parseScalarValue(trimmed), origin)
    }

    private fun parsePlainKey(token: String, number: Int, col: Int): String {
        val t = token.trim()
        val tCol = col + token.indexOf(t)
        return when {
            t.startsWith("\"") -> readQuoted(t, 0, tCol, double = true)
            t.startsWith("'") -> readQuoted(t, 0, tCol, double = false)
            else -> t
        }
    }

    private fun parseBlockScalar(line: YLine, parentIndent: Int, header: String): Node {
        val folded = header.startsWith(">")
        val indicator = header.drop(1).trim()
        var explicitIndent: Int? = null
        var chomp = "clip"
        for (c in indicator) {
            when (c) {
                '-' -> chomp = "strip"
                '+' -> chomp = "keep"
                in '1'..'9' -> explicitIndent = c.digitToInt()
            }
        }
        val startLineIdx = cursor + 1
        var endLineIdx = startLineIdx
        while (endLineIdx < lines.size && lines[endLineIdx].indent > parentIndent) endLineIdx++
        val blockLines = if (startLineIdx < endLineIdx) {
            lines.subList(startLineIdx, endLineIdx)
        } else emptyList()
        val blockIndent = explicitIndent?.let { parentIndent + it }
            ?: blockLines.minOfOrNull { it.indent }
            ?: parentIndent + 1
        val pieces = mutableListOf<String>()
        for (bl in blockLines) {
            val raw = rawLines[bl.number - 1]
            pieces.add(if (raw.length >= blockIndent) raw.substring(blockIndent) else "")
        }
        val joined: String = when {
            pieces.isEmpty() -> if (chomp == "keep") "\n" else ""
            folded -> {
                val body = pieces.joinToString("\n")
                foldBlock(body)
            }
            else -> pieces.joinToString("\n")
        }
        val value = when (chomp) {
            "strip" -> joined.trimEnd('\n')
            "keep" -> joined + "\n"
            else -> joined.trimEnd('\n') + "\n"
        }
        cursor = endLineIdx
        val origin = Origin(Side.BASE, fileName, line.number, line.colAt(line.text.indexOfFirst { it == '|' || it == '>' }))
        return Node.Scalar(ScalarValue.StrVal(value), origin)
    }

    private fun foldBlock(body: String): String {
        val lines = body.split('\n').toMutableList()
        val sb = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.isEmpty()) {
                sb.append('\n')
                while (i < lines.size && lines[i].isEmpty()) { if (i + 1 < lines.size) sb.append('\n'); i++ }
                continue
            }
            if (l.startsWith(" ") || l.startsWith("- ") || l.startsWith("* ")) {
                sb.append(l).append('\n')
            } else {
                sb.append(l)
                if (i + 1 < lines.size && lines[i + 1].isNotEmpty() &&
                    !lines[i + 1].startsWith(" ")
                ) sb.append(' ') else if (i + 1 < lines.size) sb.append('\n')
            }
            i++
        }
        return sb.toString()
    }

    /** 找到表示键值分隔的冒号（冒号位于行尾或后跟空格），忽略引号与 flow。 */
    private fun findColon(text: String): Int {
        var inSingle = false
        var inDouble = false
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inSingle -> { if (c == '\'') inSingle = false }
                inDouble -> { if (c == '\\') i++ else if (c == '"') inDouble = false }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
                c == ':' && depth == 0 && (i == text.length - 1 || text[i + 1] == ' ') -> return i
            }
            i++
        }
        return -1
    }

    private fun readQuoted(text: String, start: Int, col: Int, double: Boolean): String {
        var i = start
        val quote = if (double) '"' else '\''
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            when {
                c == quote -> {
                    if (!double && i + 1 < text.length && text[i + 1] == '\'') {
                        sb.append('\''); i += 2; continue
                    }
                    return sb.toString()
                }
                double && c == '\\' && i + 1 < text.length -> {
                    when (val e = text[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(e)
                    }
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw ParseException("$fileName:1:${col}: 引号未闭合")
    }

    private fun parseScalarValue(raw: String): ScalarValue {
        val t = raw.trim()
        if (t.startsWith("\"")) return ScalarValue.StrVal(readQuoted(t, 0, 1, double = true))
        if (t.startsWith("'")) return ScalarValue.StrVal(readQuoted(t, 0, 1, double = false))
        return parsePlainScalar(t)
    }

    companion object {
        fun parsePlainScalar(raw: String): ScalarValue {
            val t = raw.trim()
            if (t.isEmpty() || t == "~" || t == "null" || t == "Null" || t == "NULL") return ScalarValue.NullVal
            if (t == "true" || t == "True" || t == "TRUE") return ScalarValue.BoolVal(true)
            if (t == "false" || t == "False" || t == "FALSE") return ScalarValue.BoolVal(false)
            val intText = toIntegerText(t)
            if (intText != null) return ScalarValue.NumVal(BigDecimal(intText))
            toDecimalText(t)?.let { return ScalarValue.NumVal(it) }
            return ScalarValue.StrVal(t)
        }

        private fun toIntegerText(t: String): String? {
            if (t == "0") return "0"
            return when {
                t.matches(Regex("[-+]?[1-9][0-9]*")) -> t
                t.matches(Regex("0x[0-9a-fA-F]+")) -> t.substring(2).toLong(16).toString()
                t.matches(Regex("0o[0-7]+")) -> t.substring(2).toLong(8).toString()
                t.contains('_') && t.replace("_", "").matches(Regex("-?[0-9]+")) -> t.replace("_", "")
                else -> null
            }
        }

        private fun toDecimalText(t: String): BigDecimal? {
            if (!t.matches(Regex("[-+]?[0-9][0-9_]*(\\.[0-9]+)?([eE][-+]?[0-9]+)?"))) return null
            if (!t.contains('.') && !t.contains('e') && !t.contains('E')) return null
            return try { BigDecimal(t.replace("_", "")) } catch (_: NumberFormatException) { null }
        }
    }

}

/** Flow 风格集合解析（{...} / [...]），支持锚点与别名展开。 */
private class FlowParser(
    private val fileName: String,
    private val lineNumber: Int,
    private val startCol: Int,
    private val anchors: Map<String, Node>,
) {
    private lateinit var s: String
    private var i = 0

    fun parse(text: String): Node {
        s = text
        val node = parseValue()
        if (i != s.length) throw ParseException("$fileName:$lineNumber:${startCol + i}: flow 集合尾部多余字符")
        return node
    }

    private fun originAt(offset: Int) = Origin(Side.BASE, fileName, lineNumber, startCol + offset)

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
    }

    private fun parseValue(): Node {
        skipWs()
        val start = i
        return when (s[i]) {
            '{' -> parseMap()
            '[' -> parseSeq()
            '&' -> {
                i++
                val nameStart = i
                while (i < s.length && s[i] !in ",}]: \t") i++
                val name = s.substring(nameStart, i)
                skipWs()
                val node = if (i < s.length && (s[i] == '{' || s[i] == '[')) parseValue()
                else parseScalar(start)
                (anchors as? LinkedHashMap<String, Node>)?.put(name, node)
                node
            }
            '*' -> {
                i++
                val nameStart = i
                while (i < s.length && s[i] !in ",}]: \t") i++
                val name = s.substring(nameStart, i)
                val target = anchors[name]
                    ?: throw ParseException("$fileName:$lineNumber:${startCol + start}: 未定义的锚点 *$name")
                target.deepCopyWithOrigin(originAt(start))
            }
            else -> parseScalar(start)
        }
    }

    private fun parseMap(): Node.Obj {
        val origin = originAt(i)
        i++ // {
        val children = LinkedHashMap<String, Node>()
        skipWs()
        if (s[i] == '}') { i++; return Node.Obj(children, origin) }
        while (true) {
            skipWs()
            val key = when (s[i]) {
                '"' -> readDoubleKey()
                '\'' -> readSingleKey()
                else -> {
                    val ks = i
                    while (i < s.length && s[i] != ':') i++
                    s.substring(ks, i).trim()
                }
            }
            skipWs()
            if (i >= s.length || s[i] != ':') throw ParseException("$fileName:$lineNumber:${startCol + i}: flow 映射缺少 ':'")
            i++
            skipWs()
            val value = parseValue()
            if (key != "<<") children[key] = value
            skipWs()
            when (s.getOrNull(i)) {
                ',' -> { i++; continue }
                '}' -> { i++; break }
                else -> throw ParseException("$fileName:$lineNumber:${startCol + i}: flow 映射未闭合")
            }
        }
        return Node.Obj(children, origin)
    }

    private fun parseSeq(): Node.Arr {
        val origin = originAt(i)
        i++ // [
        val items = mutableListOf<Node>()
        skipWs()
        if (s[i] == ']') { i++; return Node.Arr(items, origin) }
        while (true) {
            skipWs()
            items.add(parseValue())
            skipWs()
            when (s.getOrNull(i)) {
                ',' -> { i++; continue }
                ']' -> { i++; break }
                else -> throw ParseException("$fileName:$lineNumber:${startCol + i}: flow 序列未闭合")
            }
        }
        return Node.Arr(items, origin)
    }

    private fun parseScalar(start: Int): Node {
        val end = findScalarEnd()
        val raw = s.substring(start, end)
        val scalar = YamlParser.parsePlainScalar(raw)
        return Node.Scalar(scalar, originAt(start))
    }

    private fun findScalarEnd(): Int {
        var j = i
        if (s[j] == '"' || s[j] == '\'') {
            val quote = s[j]
            j++
            while (j < s.length) {
                if (s[j] == quote) { j++; break }
                if (quote == '"' && s[j] == '\\') j++
                j++
            }
            i = j
            return j
        }
        while (j < s.length && s[j] !in ",]}") j++
        i = j
        return j
    }

    private fun readDoubleKey(): String {
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
        }
        i++
        return sb.toString()
    }

    private fun readSingleKey(): String {
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '\'') { sb.append(s[i]); i++ }
        i++
        return sb.toString()
    }
}
