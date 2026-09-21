package merger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode as YamlScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * 把 YAML / JSON 文本解析为带来源与顺序信息的 Node 树。
 * YAML 锚点/别名会被展开为独立节点（不产生共享可变引用），并在来源中记录别名关系。
 */
object Parse {
    private val jsonMapper = ObjectMapper()

    fun parse(text: String, doc: String): Pair<Node, String> {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return parseJson(text, doc) to "json"
            } catch (_: Exception) {
                // 回退到 YAML（YAML 是 JSON 超集）
            }
        }
        return parseYaml(text, doc) to "yaml"
    }

    fun parseJson(text: String, doc: String): Node =
        buildJson(jsonMapper.readTree(text), doc, "$")

    private fun buildJson(jn: JsonNode, doc: String, path: String): Node {
        val origins = listOf(Origin(doc, path))
        return when {
            jn.isObject -> {
                val entries = LinkedHashMap<String, Node>()
                val it = jn.fields()
                while (it.hasNext()) {
                    val (k, v) = it.next()
                    entries[k] = buildJson(v, doc, childPath(path, k))
                }
                ObjNode(entries, origins)
            }
            jn.isArray -> ArrNode(jn.mapIndexed { i, c -> buildJson(c, doc, "$path[$i]") }, origins)
            jn.isNull -> ScalarNode(null, origins)
            jn.isBoolean -> ScalarNode(jn.booleanValue(), origins)
            jn.isNumber -> ScalarNode(jn.decimalValue(), origins)
            jn.isTextual -> ScalarNode(jn.textValue(), origins)
            else -> ScalarNode(jn.asText(), origins)
        }
    }

    fun parseYaml(text: String, doc: String): Node {
        val root = Yaml().compose(StringReader(text))
            ?: return ScalarNode(null, listOf(Origin(doc, "$")))
        val seen = IdentityHashMap<org.yaml.snakeyaml.nodes.Node, String>()
        return buildYaml(root, doc, "$", seen)
    }

    private fun buildYaml(
        sn: org.yaml.snakeyaml.nodes.Node,
        doc: String,
        path: String,
        seen: IdentityHashMap<org.yaml.snakeyaml.nodes.Node, String>,
    ): Node {
        // 同一 YAML 节点第二次出现即为别名引用：展开为全新节点，并记录来源。
        val firstPath = seen.putIfAbsent(sn, path)
        val note = if (firstPath != null) "alias of $firstPath" else ""
        val origins = listOf(Origin(doc, path, note))
        return when (sn) {
            is MappingNode -> {
                val entries = LinkedHashMap<String, Node>()
                for (tuple in sn.value) {
                    val key = (tuple.keyNode as? YamlScalarNode)?.value ?: continueKeysNote(tuple.keyNode)
                    entries[key] = buildYaml(tuple.valueNode, doc, childPath(path, key), seen)
                }
                ObjNode(entries, origins)
            }
            is SequenceNode -> ArrNode(
                sn.value.mapIndexed { i, c -> buildYaml(c, doc, "$path[$i]", seen) },
                origins,
            )
            is YamlScalarNode -> ScalarNode(yamlScalar(sn), origins)
            else -> ScalarNode(null, origins)
        }
    }

    private fun continueKeysNote(node: org.yaml.snakeyaml.nodes.Node): String = node.toString()

    private fun yamlScalar(sn: YamlScalarNode): Any? {
        val v = sn.value
        return when (sn.tag.value) {
            "tag:yaml.org,2002:null" -> null
            "tag:yaml.org,2002:bool" -> v.lowercase() in listOf("true", "yes", "on")
            "tag:yaml.org,2002:int" -> parseInt(v) ?: v
            "tag:yaml.org,2002:float" -> parseFloat(v) ?: v
            else -> v
        }
    }

    private fun parseInt(raw: String): BigDecimal? {
        val v = raw.replace("_", "")
        return try {
            when {
                v.startsWith("0x") || v.startsWith("0X") -> BigDecimal(BigInteger(v.substring(2), 16))
                v.startsWith("0o") || v.startsWith("0O") -> BigDecimal(BigInteger(v.substring(2), 8))
                else -> BigDecimal(BigInteger(v))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFloat(raw: String): BigDecimal? {
        val v = raw.replace("_", "")
        if (v.startsWith(".") || v.equals("-.inf", true) || v.equals("+.inf", true)) return null
        return try {
            BigDecimal(v)
        } catch (_: Exception) {
            null
        }
    }

    fun childPath(parent: String, key: String): String =
        if (parent == "$") "$.$key" else "$parent.$key"
}
