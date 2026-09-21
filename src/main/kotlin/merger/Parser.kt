package merger

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.nodes.AnchorNode
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.NodeType
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader
import java.math.BigDecimal
import java.util.IdentityHashMap

/**
 * 解析 YAML/JSON 为语义模型：
 *  - snakeyaml-engine 会把别名解析成“同一个节点实例”。这里用对象身份识别共享：
 *    第一次出现视为锚点定义，之后出现视为别名，每次都递归生成独立副本，
 *    并在副本根上记录 AnchorRef(viaAlias=true)，输出绝不共享可变引用；
 *  - 显式删除标记：YAML 用 !delete 标签；两种格式都可用 {"$delete": true}；
 *  - 重复键报错，不静默覆盖；
 *  - 缺失 / 显式 null / 删除在模型层面是三种不同的东西。
 */
object ConfigParser {

    const val DELETE_TAG = "!delete"
    const val DELETE_KEY = "\$delete"

    fun parse(raw: String, source: Source, formatHint: Format? = null): ParsedDoc {
        val t0 = System.currentTimeMillis()
        val format = formatHint ?: detect(raw)
        val settings = LoadSettings.builder().setAllowDuplicateKeys(false).build()
        val rootNode = try {
            Composer(settings, ParserImpl(settings, StreamReader(settings, raw))).getSingleNode().orElse(null)
        } catch (e: Exception) {
            throw ParseException("${format.name} 解析失败: ${e.message}", e)
        } ?: throw ParseException("内容为空，无法解析")

        val meta = IdentityHashMap<SNode, MutableList<AnchorRef>>()
        // 已完成转换的节点实例 -> (锚点名, 结果)，用于识别别名共享
        val seen = HashMap<Node, Pair<String, SNode>>()
        val active = HashSet<String>()
        val root = Context(meta, seen).convert(rootNode, active)
        return ParsedDoc(
            source = source, format = format, raw = raw, root = root,
            fingerprint = Canonical.fingerprint(root), meta = meta,
            parseMs = System.currentTimeMillis() - t0
        )
    }

    fun detect(raw: String): Format {
        val t = raw.trimStart()
        if (t.isEmpty()) return Format.YAML
        val first = t.first()
        if (first != '{' && first != '[') return Format.YAML
        var dObj = 0; var dArr = 0; var inStr = false; var esc = false
        for (c in t) {
            if (inStr) { when { esc -> esc = false; c == '\\' -> esc = true; c == '"' -> inStr = false }; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> dObj++; '}' -> dObj--
                '[' -> dArr++; ']' -> dArr--
                '\n' -> return Format.YAML
            }
            if (dObj == 0 && dArr == 0) return Format.JSON
        }
        return Format.YAML
    }

    private class Context(
        val meta: IdentityHashMap<SNode, MutableList<AnchorRef>>,
        val seen: HashMap<Node, Pair<String, SNode>>
    ) {
        fun convert(node: Node, active: HashSet<String>): SNode {
            val current = if (node is AnchorNode) node.realNode else node
            val anchor = current.anchor.map { it.value }.orElse(null)
            val isAliasOccurrence = seen.containsKey(current)

            if (anchor != null) {
                if (!active.add(anchor)) throw ParseException("检测到 YAML 锚点循环引用: *$anchor")
            }
            try {
                if (current.tag.value == DELETE_TAG) return SSDelete.INSTANCE

                val result: SNode = when (current.nodeType) {
                    NodeType.SCALAR -> convertScalar(current as ScalarNode)
                    NodeType.SEQUENCE -> SSeq((current as SequenceNode).value.map { convert(it, HashSet(active)) })
                    NodeType.MAPPING -> {
                        val map = current as MappingNode
                        if (isDeleteMarker(map)) SSDelete.INSTANCE else {
                            val out = LinkedHashMap<String, SNode>()
                            for (tuple in map.value) {
                                val key = (tuple.keyNode as? ScalarNode)?.value
                                    ?: throw ParseException("第 ${lineOf(tuple.keyNode)} 行: 仅支持标量映射键")
                                if (out.containsKey(key))
                                    throw ParseException("第 ${lineOf(tuple.keyNode)} 行: 检测到重复键 \"$key\"，拒绝静默覆盖")
                                out[key] = convert(tuple.valueNode, HashSet(active))
                            }
                            SMap(out)
                        }
                    }
                    NodeType.ANCHOR -> throw ParseException("不允许嵌套锚点引用")
                }

                if (anchor != null) {
                    meta.getOrPut(result) { mutableListOf<AnchorRef>() }
                        .add(AnchorRef(anchor, viaAlias = isAliasOccurrence, line = lineOf(current)))
                    seen[current] = anchor to result
                } else if (isAliasOccurrence) {
                    // 有别名共享但该实例无锚点名（理论少见），仍标记来源
                    val (name, _) = seen.getValue(current)
                    meta.getOrPut(result) { mutableListOf<AnchorRef>() }
                        .add(AnchorRef(name, viaAlias = true, line = lineOf(current)))
                }
                return result
            } finally {
                if (anchor != null) active.remove(anchor)
            }
        }

        private fun isDeleteMarker(map: MappingNode): Boolean {
            if (map.value.size != 1) return false
            val tuple = map.value[0]
            val key = (tuple.keyNode as? ScalarNode)?.value ?: return false
            if (key != DELETE_KEY) return false
            val v = tuple.valueNode
            return v is ScalarNode && v.tag.value.endsWith(":bool") && v.value == "true"
        }

        private fun lineOf(n: Node): Int? = n.startMark.map { it.line + 1 }.orElse(null)

        private fun convertScalar(node: ScalarNode): SNode {
            val tag = node.tag.value
            val raw = node.value
            return when {
                tag.endsWith(":null") -> SSNull.INSTANCE
                tag.endsWith(":bool") -> SSBool(raw == "true" || raw == "True" || raw == "TRUE")
                tag.endsWith(":int") || tag.endsWith(":float") -> SSNumber(parseNumber(raw))
                else -> SSString(raw)
            }
        }

        private fun parseNumber(raw: String): BigDecimal {
            val clean = raw.replace("_", "").lowercase()
            val u = clean.removePrefix("+").removePrefix("-")
            val neg = clean.startsWith("-")
            return try {
                when {
                    u.startsWith("0x") -> BigDecimal((if (neg) "-" else "") + u.toLong(16).toString())
                    u.startsWith("0o") -> BigDecimal((if (neg) "-" else "") + u.removePrefix("0o").toLong(8).toString())
                    u.contains(":") -> {
                        var acc = BigDecimal.ZERO
                        for (p in u.split(':')) acc = acc.multiply(BigDecimal(60)).add(BigDecimal(p))
                        if (neg) acc.negate() else acc
                    }
                    else -> BigDecimal(clean)
                }
            } catch (e: Exception) {
                throw ParseException("无法解析数字: $raw")
            }
        }
    }
}
