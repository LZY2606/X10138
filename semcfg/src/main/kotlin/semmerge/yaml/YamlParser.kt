package semmerge.yaml

import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.model.ScalarStyle

class YamlParseException(message: String, val line: Int) : RuntimeException("$message (line ${line + 1})")

/**
 * YAML subset parser for configuration files:
 *  - block maps / block sequences with arbitrary nesting
 *  - flow maps and flow sequences (inline)
 *  - anchors (`&x`) and aliases (`*x`), expanded into independent copies
 *  - single/double quoted scalars and `|` / `>` block scalars
 *  - `---` / `...` document markers; exactly one document
 */
object YamlParser {

    private class Line(val num: Int, val indent: Int, val content: String, val raw: String) {
        val blank: Boolean get() = content.isEmpty()
    }

    fun parse(text: String): SNode {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = mutableListOf<Line>()
        for ((idx, raw0) in normalized.split("\n").withIndex()) {
            val raw = YamlText.stripComment(raw0)
            val cut = raw.trimEnd()
            if (cut.isBlank()) {
                lines += Line(idx + 1, Int.MAX_VALUE, "", raw)
                continue
            }
            val indent = cut.indexOfFirst { it != ' ' }
            if (raw.contains('\t') && cut.substring(0, indent).contains('\t')) {
                throw YamlParseException("tabs may not be used for indentation", idx)
            }
            val content = cut.substring(indent)
            if (content == "---" || content == "...") continue
            lines += Line(idx + 1, indent, content, raw)
        }
        val meaningful = lines.indexOfFirst { !it.blank }
        if (meaningful < 0) return SMap()
        val state = Cursor(lines, HashMap())
        val node = state.parseBlock(lines[meaningful].indent)
        state.skipBlanks()
        if (state.hasNext()) throw YamlParseException("unexpected extra content", state.peek()!!.num)
        return node
    }

    private class Cursor(val lines: List<Line>, val anchors: MutableMap<String, SNode>) {
        var pos = 0
        fun hasNext(): Boolean = pos < lines.size
        fun peek(): Line? = lines.getOrNull(pos)

        fun skipBlanks() {
            while (pos < lines.size && lines[pos].blank) pos++
        }

        fun parseBlock(col: Int): SNode {
            skipBlanks()
            val head = peek()?.content ?: throw YamlParseException("expected a node", lines.last().num)
            return when {
                head == "-" || head.startsWith("- ") -> parseSequence(col)
                YamlText.findColon(head) >= 0 -> parseMap(col)
                else -> {
                    val l = lines[pos]
                    pos++
                    resolveInline(col, l.content, l.num, expectChild = false)
                }
            }
        }

        fun parseMap(col: Int, firstContent: String? = null, firstLineNum: Int = -1): SNode {
            val entries = mutableListOf<Pair<String, SNode>>()
            val seen = HashSet<String>()

            fun consume(content: String, lineNum: Int) {
                val ci = YamlText.findColon(content)
                if (ci < 0) throw YamlParseException("expected map entry", lineNum)
                val key = parseKey(content.substring(0, ci), lineNum)
                if (!seen.add(key)) throw YamlParseException("duplicate key '$key'", lineNum)
                val valueText = if (ci + 1 < content.length) content.substring(ci + 1).trim() else ""
                val value = resolveValue(col, valueText, lineNum)
                entries += key to value
            }

            if (firstContent != null) consume(firstContent, firstLineNum)

            while (pos < lines.size) {
                val l = lines[pos]
                if (l.blank) { pos++; continue }
                if (l.indent < col) break
                if (l.indent > col) throw YamlParseException("unexpected indentation", l.num)
                if (l.content == "-" || l.content.startsWith("- ")) break
                pos++
                consume(l.content, l.num)
            }
            return SMap(entries)
        }

        fun parseSequence(col: Int): SNode {
            val items = mutableListOf<SNode>()
            while (pos < lines.size) {
                val l = lines[pos]
                if (l.blank) { pos++; continue }
                if (l.indent < col) break
                if (l.indent > col) throw YamlParseException("unexpected indentation", l.num)
                if (l.content != "-" && !l.content.startsWith("- ")) {
                    break
                }
                pos++
                val after = if (l.content == "-") "" else l.content.substring(2)
                when {
                    after.isEmpty() -> {
                        skipBlanks()
                        val next = peek()
                        if (next != null && next.indent > col) {
                            items += parseBlock(next.indent)
                        } else items += SMap()
                    }
                    YamlText.findColon(after) >= 0 -> {
                        items += parseMap(col + 2, firstContent = after, firstLineNum = l.num)
                    }
                    else -> items += resolveInline(col, after, l.num, expectChild = false)
                }
            }
            return SList(items)
        }

        private fun resolveValue(col: Int, valueText: String, lineNum: Int): SNode {
            if (valueText.isEmpty()) {
                skipBlanks()
                val next = peek()
                return if (next != null && next.indent > col) parseBlock(next.indent) else SMap()
            }
            return resolveInline(col, valueText, lineNum, expectChild = true)
        }

        /** Parse `&a &b *alias | > flow scalar` tokens; fall back to a child block when empty. */
        private fun resolveInline(col: Int, text0: String, lineNum: Int, expectChild: Boolean): SNode {
            var text = text0.trim()
            val names = mutableListOf<String>()
            while (text.startsWith("&") || text.startsWith("*")) {
                val sp = text.indexOf(' ').let { if (it < 0) text.length else it }
                val token = text.substring(0, sp)
                text = text.substring(sp).trimStart()
                when {
                    token.startsWith("*") -> {
                        if (names.isNotEmpty() || text.isNotEmpty())
                            throw YamlParseException("alias may not combine with other tokens", lineNum)
                        val original = anchors[token.substring(1)]
                            ?: throw YamlParseException("unknown anchor '${token.substring(1)}'", lineNum)
                        return original.deepCopy()
                    }
                    else -> names += token.substring(1)
                }
            }

            val node: SNode = when {
                text.isNotEmpty() -> parseInlineScalar(text, col, lineNum)
                expectChild -> {
                    skipBlanks()
                    val next = peek()
                    if (next != null && next.indent > col) parseBlock(next.indent) else SMap()
                }
                else -> SScalar.NULL
            }
            names.forEach { anchors[it] = node.deepCopy() }
            return node
        }

        private fun parseInlineScalar(text: String, col: Int, lineNum: Int): SNode {
            val t = text.trim()
            return when {
                t.startsWith("{") -> {
                    val (n, e) = YamlText.readFlowMap(t, 0)
                    if (t.substring(e).trim().isNotEmpty()) throw YamlParseException("bad flow map", lineNum)
                    n
                }
                t.startsWith("[") -> {
                    val (n, e) = YamlText.readFlowSeq(t, 0)
                    if (t.substring(e).trim().isNotEmpty()) throw YamlParseException("bad flow sequence", lineNum)
                    n
                }
                t.startsWith("|") || t.startsWith(">") -> blockScalar(t[0], t.substring(1), col)
                else -> {
                    val (s, left) = YamlText.parsePlainOrQuotedScalar(t)
                    if (left.trim().isNotEmpty())
                        throw YamlParseException("unexpected '${left.trim()}'", lineNum)
                    s
                }
            }
        }

        private fun parseKey(text: String, lineNum: Int): String {
            val t = text.trim()
            if (t.isEmpty()) throw YamlParseException("empty key", lineNum)
            if (t.startsWith("'") || t.startsWith("\"")) {
                val (s, left) = YamlText.parsePlainOrQuotedScalar(t)
                if (left.trim().isNotEmpty()) throw YamlParseException("bad key", lineNum)
                return YamlText.scalarString(s)
            }
            if (t.startsWith("{")) throw YamlParseException("complex keys are not supported", lineNum)
            return t
        }

        private fun blockScalar(header: Char, indicators: String, col: Int): SScalar {
            var chomp = 0
            var explicitIndent = 0
            for (c in indicators) when (c) {
                '-' -> chomp = -1
                '+' -> chomp = 1
                in '0'..'9' -> explicitIndent = c - '0'
            }
            val start = pos
            var blockIndent = -1
            val body = mutableListOf<String>()
            while (pos < lines.size) {
                val l = lines[pos]
                if (!l.blank) {
                    if (blockIndent < 0) {
                        if (l.indent <= col) break
                        blockIndent = if (explicitIndent > 0) col + explicitIndent else l.indent
                    }
                    if (l.indent < blockIndent) break
                }
                pos++
                if (blockIndent < 0) { body += ""; continue }
                val raw = l.raw.trimEnd()
                body += if (raw.length >= blockIndent) raw.substring(blockIndent) else ""
            }
            if (blockIndent < 0) {
                pos = start
                return SScalar(ScalarKind.STRING, "",
                    if (header == '|') ScalarStyle.LITERAL else ScalarStyle.FOLDED)
            }
            var last = body.indexOfLast { it.isNotEmpty() }
            if (last < 0) last = -1
            val core = if (last < 0) emptyList() else body.subList(0, last + 1)
            val trailingBlanks = if (last < 0) 0 else body.size - 1 - last
            val joined = if (header == '|') core.joinToString("\n") else fold(core)
            val suffix = when (chomp) {
                -1 -> ""
                1 -> "\n".repeat(1 + trailingBlanks)
                else -> "\n"
            }
            return SScalar(ScalarKind.STRING, joined + suffix,
                if (header == '|') ScalarStyle.LITERAL else ScalarStyle.FOLDED)
        }

        private fun fold(core: List<String>): String {
            val out = StringBuilder()
            var blankRun = 0
            var prevMoreIndented = false
            for ((idx, line) in core.withIndex()) {
                if (line.isEmpty()) { blankRun++; continue }
                val moreIndented = line.startsWith(" ")
                if (out.isNotEmpty()) {
                    val sep = when {
                        blankRun > 0 -> "\n".repeat(blankRun + 1)
                        moreIndented || prevMoreIndented -> "\n"
                        else -> " "
                    }
                    out.append(sep)
                }
                out.append(line)
                blankRun = 0
                prevMoreIndented = moreIndented
            }
            return out.toString()
        }
    }
}
