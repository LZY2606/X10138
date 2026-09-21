package semmerge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Tag

enum class Format { JSON, YAML }

object Parser {

    fun detectFormat(text: String): Format {
        val t = text.trimStart()
        return if (t.startsWith("{") || t.startsWith("[")) Format.JSON else Format.YAML
    }

    /** 解析文本为语义模型；side 用于标记来源。 */
    fun parse(text: String, side: String, format: Format? = null): Node? {
        val fmt = format ?: detectFormat(text)
        if (text.isBlank()) return null
        return when (fmt) {
            Format.JSON -> fromJson(ObjectMapper().readTree(text), side, "$")
            Format.YAML -> fromYaml(text, side)
        }
    }

    private fun fromJson(jn: JsonNode?, side: String, path: String): Node? {
        if (jn == null || jn.isMissingNode) return null
        val prov = listOf(Prov(side, path))
        return when {
            jn.isNull -> Node.NullNode(prov)
            jn.isObject -> {
                val map = LinkedHashMap<String, Node>()
                val it = jn.fields()
                while (it.hasNext()) {
                    val e = it.next()
                    map[e.key] = fromJson(e.value, side, childPath(path, e.key))
                        ?: Node.NullNode(listOf(Prov(side, childPath(path, e.key))))
                }
                Node.Obj(map, prov)
            }
            jn.isArray -> Node.Arr(
                jn.mapIndexed { i, c -> fromJson(c, side, indexPath(path, i)) ?: Node.NullNode() },
                prov
            )
            jn.isBoolean -> Node.Scalar(jn.asText(), Node.Kind.BOOL, prov)
            jn.isNumber -> Node.Scalar(jn.asText(), Node.Kind.NUMBER, prov)
            else -> Node.Scalar(jn.asText(), Node.Kind.STRING, prov)
        }
    }

    /**
     * YAML：使用 compose 得到节点图，锚点/别名在图中是共享引用；
     * 转换时每次出现都独立构造语义节点（展开别名），输出不会制造共享可变引用。
     */
    private fun fromYaml(text: String, side: String): Node? {
        val yaml = Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(LoaderOptions()))
        val root = yaml.compose(text.reader()) ?: return null
        return convertYaml(root, side, "$", HashSet())
    }

    private fun convertYaml(n: org.yaml.snakeyaml.nodes.Node, side: String, path: String, seen: MutableSet<Int>): Node {
        val prov = listOf(Prov(side, path))
        return when (n) {
            is ScalarNode -> when (n.tag) {
                Tag.NULL -> Node.NullNode(prov)
                Tag.BOOL -> Node.Scalar(n.value.lowercase(), Node.Kind.BOOL, prov)
                Tag.INT, Tag.FLOAT -> Node.Scalar(n.value, Node.Kind.NUMBER, prov)
                else -> Node.Scalar(n.value, Node.Kind.STRING, prov)
            }
            is SequenceNode -> Node.Arr(
                n.value.mapIndexed { i, c -> convertYaml(c, side, indexPath(path, i), seen) },
                prov
            )
            is MappingNode -> {
                val map = LinkedHashMap<String, Node>()
                for (t: NodeTuple in n.value) {
                    val keyNode = t.keyNode as? ScalarNode
                        ?: throw IllegalArgumentException("不支持的 YAML 键类型 at $path")
                    val key = keyNode.value
                    val cp = childPath(path, key)
                    map[key] = convertYaml(t.valueNode, side, cp, seen)
                }
                Node.Obj(map, prov)
            }
            else -> throw IllegalArgumentException("不支持的 YAML 节点 at $path: ${n.nodeId}")
        }
    }
}
