package merger

import java.math.BigDecimal

/**
 * 严格 JSON 解析器。每个节点携带在原文件中的行列出处，键顺序与数组顺序保留。
 */
class JsonParser(private val fileName: String) {
    private lateinit var src: String
    private var pos = 0
    private var line = 1
    private var col = 1

    fun parse(text: String): Node {
        src = text
        pos = 0
        line = 1
        col = 1
        skipWs()
        val node = parseValue()
        skipWs()
        if (pos < src.length) fail("尾部多余字符: '${src[pos]}'")
        return node
    }

    private fun here(): Origin = Origin(Side.BASE, fileName, line, col)

    private fun eof(): Boolean = pos >= src.length
    private fun peek(): Char = src[pos]

    private fun advance(): Char {
        val c = src[pos++]
        if (c == '\n') { line++; col = 1 } else col++
        return c
    }

    private fun skipWs() {
        while (!eof()) {
            val c = peek()
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') advance() else break
        }
    }

    private fun expect(c: Char) {
        if (eof() || peek() != c) fail("期望 '$c'")
        advance()
    }

    private fun parseValue(): Node {
        if (eof()) fail("意外结束")
        val origin = here()
        return when (peek()) {
            '{' -> parseObject(origin)
            '[' -> parseArray(origin)
            '"' -> Node.Scalar(ScalarValue.StrVal(parseString()), origin)
            't' -> parseLit("true", Node.Scalar(ScalarValue.BoolVal(true), origin))
            'f' -> parseLit("false", Node.Scalar(ScalarValue.BoolVal(false), origin))
            'n' -> parseLit("null", Node.Scalar(ScalarValue.NullVal, origin))
            '-', in '0'..'9' -> parseNumber(origin)
            else -> fail("无法识别的值起始: '${peek()}'")
        }
    }

    private fun parseLit(lit: String, node: Node): Node {
        if (!src.startsWith(lit, pos)) fail("无法识别的字面量")
        repeat(lit.length) { advance() }
        return node
    }

    private fun parseObject(origin: Origin): Node.Obj {
        expect('{')
        val children = LinkedHashMap<String, Node>()
        skipWs()
        if (!eof() && peek() == '}') { advance(); return Node.Obj(children, origin) }
        while (true) {
            skipWs()
            if (eof() || peek() != '"') fail("对象键必须是字符串")
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            val value = parseValue()
            children[key] = value
            skipWs()
            when {
                !eof() && peek() == ',' -> { advance(); continue }
                !eof() && peek() == '}' -> { advance(); break }
                else -> fail("对象中期望 ',' 或 '}'")
            }
        }
        return Node.Obj(children, origin)
    }

    private fun parseArray(origin: Origin): Node.Arr {
        expect('[')
        val items = mutableListOf<Node>()
        skipWs()
        if (!eof() && peek() == ']') { advance(); return Node.Arr(items, origin) }
        while (true) {
            skipWs()
            items.add(parseValue())
            skipWs()
            when {
                !eof() && peek() == ',' -> { advance(); continue }
                !eof() && peek() == ']' -> { advance(); break }
                else -> fail("数组中期望 ',' 或 ']'")
            }
        }
        return Node.Arr(items, origin)
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (eof()) fail("字符串未闭合")
            val c = advance()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (eof()) fail("转义未完成")
                    when (val e = advance()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > src.length) fail("\\u 转义不完整")
                            val hex = src.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("非法的 \\u 转义")
                            repeat(4) { advance() }
                            sb.append(code.toChar())
                        }
                        else -> fail("非法转义: \\$e")
                    }
                }
                '\n' -> fail("字符串中出现裸换行")
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(origin: Origin): Node {
        val start = pos
        if (!eof() && peek() == '-') advance()
        if (eof() || peek() !in '0'..'9') fail("非法数字")
        if (peek() == '0') {
            advance()
        } else {
            while (!eof() && peek() in '0'..'9') advance()
        }
        var isFloat = false
        if (!eof() && peek() == '.') {
            isFloat = true
            advance()
            if (eof() || peek() !in '0'..'9') fail("小数部分缺失")
            while (!eof() && peek() in '0'..'9') advance()
        }
        if (!eof() && (peek() == 'e' || peek() == 'E')) {
            isFloat = true
            advance()
            if (!eof() && (peek() == '+' || peek() == '-')) advance()
            if (eof() || peek() !in '0'..'9') fail("指数部分缺失")
            while (!eof() && peek() in '0'..'9') advance()
        }
        val text = src.substring(start, pos)
        // JSON 语义：含小数点/指数视为浮点，否则整数；统一用 BigDecimal 承载，保留精度。
        val num = BigDecimal(text)
        return Node.Scalar(ScalarValue.NumVal(num), origin)
    }

    private fun fail(msg: String): Nothing =
        throw ParseException("$fileName:${line}:${col}: $msg")
}

class ParseException(message: String) : RuntimeException(message)
