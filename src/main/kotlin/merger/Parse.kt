package merger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.util.IdentityHashMap
import org.yaml.snakeyaml.nodes.Node as YNode
import org.yaml.snakeyaml.nodes.ScalarNode as YScalar

enum class DocFormat { YAML, JSON }

fun parseDocument(format: DocFormat, text: String, docId: String): Node? = when (format) {
    DocFormat.YAML -> parseYaml(text, docId)
    DocFormat.JSON -> parseJson(text, docId)
}

/** YAML 解析：锚点/别名展开为独立副本参与比较，输出模型不含共享可变引用 */
fun parseYaml(text: String, docId: String): Node? {
    val root = Yaml().compose(StringReader(text)) ?: return null
    val seen = IdentityHashMap<YNode, Node>()
    return fromYaml(root, docId, "", -1, seen)
}

private fun fromYaml(yn: YNode, docId: String, path: String, order: Int, seen: IdentityHashMap<YNode, Node>): Node {
    seen[yn]?.let { return it.copyDeep() } // 别名：展开为深拷贝
    val node: Node = when (yn) {
        is MappingNode -> MapNode().also { map ->
            yn.value.forEachIndexed { i, tuple ->
                val key = (tuple.keyNode as YScalar).value
                map.entries[key] = fromYaml(tuple.valueNode, docId, childPath(path, key), i, seen)
            }
        }
        is SequenceNode -> SeqNode().also { seq ->
            yn.value.forEachIndexed { i, child ->
                seq.items.add(fromYaml(child, docId, "$path/$i", i, seen))
            }
        }
        is YScalar -> when (yn.tag) {
            Tag.NULL -> NullNode()
            Tag.BOOL -> ScalarNode(yn.value.lowercase(), ScalarKind.BOOLEAN)
            Tag.INT, Tag.FLOAT -> ScalarNode(yn.value, ScalarKind.NUMBER)
            else -> ScalarNode(yn.value, ScalarKind.STRING)
        }
        else -> ScalarNode(yn.toString(), ScalarKind.STRING)
    }
    seen[yn] = node
    node.source = SourceRef(docId, path, order)
    node.sourceChain = listOfNotNull(node.source)
    return node
}

private val jsonMapper = ObjectMapper()

fun parseJson(text: String, docId: String): Node? {
    val tree = jsonMapper.readTree(text) ?: return null
    return fromJson(tree, docId, "", -1)
}

private fun fromJson(jn: JsonNode, docId: String, path: String, order: Int): Node {
    val node: Node = when {
        jn.isObject -> MapNode().also { map ->
            var i = 0
            val it = jn.fields()
            while (it.hasNext()) {
                val (k, v) = it.next()
                map.entries[k] = fromJson(v, docId, childPath(path, k), i++)
            }
        }
        jn.isArray -> SeqNode().also { seq ->
            jn.forEachIndexed { i, child -> seq.items.add(fromJson(child, docId, "$path/$i", i)) }
        }
        jn.isNull -> NullNode()
        jn.isBoolean -> ScalarNode(jn.asText(), ScalarKind.BOOLEAN)
        jn.isNumber -> ScalarNode(jn.asText(), ScalarKind.NUMBER)
        else -> ScalarNode(jn.asText(), ScalarKind.STRING)
    }
    node.source = SourceRef(docId, path, order)
    node.sourceChain = listOfNotNull(node.source)
    return node
}

/** 从 JSON 字面量构造节点（用于人工裁决的自定义值） */
fun nodeFromJsonLiteral(json: String): Node? = parseJson(json, "manual")
