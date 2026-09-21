package merger

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.Yaml
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node as YamlNode
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.nodes.Tag
import java.io.StringReader
import java.security.MessageDigest

/** 输入来源：哪一侧、哪个文档、哪一行（1 起始，0 表示未知）。 */
data class Origin(val side: String, val doc: String, val line: Int) {
    fun label(): String = if (line > 0) "$side:$doc#L$line" else "$side:$doc"
}

/** 语义节点。解析阶段为每个值带来来源信息；别名将展开为独立副本，绝不共享可变引用。 */
sealed class SNode {
    abstract val origin: Origin

    data class SNull(override val origin: Origin) : SNode()
    data class SBool(val value: Boolean, override val origin: Origin) : SNode()
    data class SNum(val raw: String, override val origin: Origin) : SNode()
    data class SStr(val value: String, override val origin: Origin) : SNode()
    data class SObj(val children: LinkedHashMap<String, SNode>, override val origin: Origin) : SNode()
    data class SArr(val items: List<SNode>, override val origin: Origin) : SNode()
}

object Fingerprints {
    fun canonical(node: SNode?): String = when (node) {
        null -> "∅"
        is SNode.SNull -> "null"
        is SNode.SBool -> "bool:${node.value}"
        is SNode.SNum -> "num:${node.raw}"
        is SNode.SStr -> "str:${node.value.length}:${node.value}"
        is SNode.SObj -> "obj:{" + node.children.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key.length}:${it.key}=${canonical(it.value)}" } + "}"
        is SNode.SArr -> "arr:[" + node.items.joinToString(",") { canonical(it) } + "]"
    }

    fun of(node: SNode?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical(node).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun short(node: SNode?): String = of(node).take(16)
}

class ParseException(message: String) : RuntimeException(message)

object Parser {
    /** 解析 YAML 或 JSON 文本为语义节点树。锚点/别名在转换时展开为独立副本。 */
    fun parse(text: String, format: String, side: String, doc: String): SNode? {
        val settings = LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(200)
            .build()
        val yaml = Yaml(settings)
        val root: YamlNode? = try {
            yaml.compose(StringReader(text))
        } catch (e: Exception) {
            throw ParseException("解析失败($doc): ${e.message}")
        } ?: return null
        if (format == "json" && root.tag != Tag.MAP && root.tag != Tag.SEQ) {
            throw ParseException("JSON 文档根节点必须是对象或数组($doc)")
        }
        return convert(root, side, doc)
    }

    private fun originOf(node: YamlNode, side: String, doc: String): Origin {
        val mark = node.startMark
        val line = if (mark.isPresent) mark.get().line + 1 else 0
        return Origin(side, doc, line)
    }

    private fun convert(node: YamlNode, side: String, doc: String): SNode {
        val origin = originOf(node, side, doc)
        return when (node) {
            is MappingNode -> {
                val children = LinkedHashMap<String, SNode>()
                for (tuple in node.value) {
                    val keyNode = tuple.keyNode
                    if (keyNode !is ScalarNode) throw ParseException("仅支持标量键($doc)")
                    val key = keyNode.value
                    if (children.containsKey(key)) throw ParseException("重复键 '$key'($doc)")
                    children[key] = convert(tuple.valueNode, side, doc)
                }
                SNode.SObj(children, origin)
            }
            is SequenceNode -> SNode.SArr(node.value.map { convert(it, side, doc) }, origin)
            is ScalarNode -> when (node.tag) {
                Tag.NULL -> SNode.SNull(origin)
                Tag.BOOL -> SNode.SBool(node.value.lowercase() == "true", origin)
                Tag.INT, Tag.FLOAT -> SNode.SNum(node.value, origin)
                else -> SNode.SStr(node.value, origin)
            }
            else -> throw ParseException("不支持的节点类型 ${node.nodeType}($doc)")
        }
    }
}

/** 稳定序列化：同一模型永远产生同一文本，且只输出展开后的值，绝不产生锚点/共享引用。 */
object Emit {
    fun toJson(node: SNode?): String = StringBuilder().also { writeJson(it, node, 0) }.toString() + "\n"

    private fun writeJson(sb: StringBuilder, node: SNode?, indent: Int) {
        val pad = "  ".repeat(indent)
        val padIn = "  ".repeat(indent + 1)
        when (node) {
            null -> sb.append("null")
            is SNode.SNull -> sb.append("null")
            is SNode.SBool -> sb.append(if (node.value) "true" else "false")
            is SNode.SNum -> sb.append(node.raw)
            is SNode.SStr -> sb.append(jsonString(node.value))
            is SNode.SObj -> {
                if (node.children.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                node.children.entries.forEachIndexed { i, (k, v) ->
                    sb.append(padIn).append(jsonString(k)).append(": ")
                    writeJson(sb, v, indent + 1)
                    if (i < node.children.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(pad).append("}")
            }
            is SNode.SArr -> {
                if (node.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                node.items.forEachIndexed { i, v ->
                    sb.append(padIn)
                    writeJson(sb, v, indent + 1)
                    if (i < node.items.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(pad).append("]")
            }
        }
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private val plainSafe = Regex("^[A-Za-z0-9_./+@-][A-Za-z0-9_./+@=-]*$")
    private val keywordLike = Regex("(?i)^(null|~|true|false|yes|no|on|off|[-+]?[0-9][0-9_]*(\\.[0-9_]*)?|[-+]?\\.inf|\\.nan)$")

    fun yamlScalar(node: SNode?): String = when (node) {
        null, is SNode.SNull -> "null"
        is SNode.SBool -> if (node.value) "true" else "false"
        is SNode.SNum -> node.raw
        is SNode.SStr -> if (plainSafe.matches(node.value) && !keywordLike.matches(node.value)) node.value
            else jsonString(node.value)
    }

    fun toYaml(node: SNode?): String {
        val lines = mutableListOf<String>()
        writeYaml(lines, node, 0, topLevel = true)
        return lines.joinToString("\n") + "\n"
    }

    private fun inline(node: SNode?): String = when (node) {
        is SNode.SObj -> if (node.children.isEmpty()) "{}" else ""
        is SNode.SArr -> if (node.items.isEmpty()) "[]" else ""
        else -> yamlScalar(node)
    }

    private fun writeYaml(lines: MutableList<String>, node: SNode?, indent: Int, topLevel: Boolean = false) {
        val pad = "  ".repeat(indent)
        when (node) {
            is SNode.SObj -> {
                if (node.children.isEmpty()) { lines.add("$pad{}"); return }
                for ((k, v) in node.children) {
                    val key = if (plainSafe.matches(k) && !keywordLike.matches(k)) k else jsonString(k)
                    val inl = inline(v)
                    if (inl.isNotEmpty()) {
                        lines.add("$pad$key: $inl")
                    } else {
                        lines.add("$pad$key:")
                        writeYaml(lines, v, indent + 1)
                    }
                }
            }
            is SNode.SArr -> {
                if (node.items.isEmpty()) { lines.add("$pad[]"); return }
                for (item in node.items) {
                    val inl = inline(item)
                    if (inl.isNotEmpty()) {
                        lines.add("$pad- $inl")
                    } else {
                        lines.add("$pad-")
                        writeYaml(lines, item, indent + 1)
                    }
                }
            }
            else -> lines.add("$pad${yamlScalar(node)}")
        }
    }
}
