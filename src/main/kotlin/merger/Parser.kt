package merger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.util.IdentityHashMap

enum class Format { YAML, JSON }

val yamlMapper: ObjectMapper = ObjectMapper(
    YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
).registerKotlinModule()
val jsonMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

fun mapperFor(format: Format): ObjectMapper = when (format) {
    Format.YAML -> yamlMapper
    Format.JSON -> jsonMapper
}

class ParseException(msg: String) : RuntimeException(msg)

/**
 * 把文本解析为带溯源信息的 CNode 树。
 * YAML 走 SnakeYAML Node 树：锚点/别名展开为共享 Node，按身份检测并打
 * alias-expanded 标记；`<<` 合并键在此展开；输出物化为独立节点，绝不共享可变引用。
 */
fun parse(text: String, format: Format, source: String): CNode = when (format) {
    Format.JSON -> parseJson(text, source)
    Format.YAML -> parseYaml(text, source)
}

private fun parseJson(text: String, source: String): CNode {
    val tree = try {
        jsonMapper.readTree(text)
    } catch (e: Exception) {
        throw ParseException("无法解析 JSON 输入 ($source): ${e.message}")
    } ?: throw ParseException("空输入 ($source)")
    return convertJson(tree, source)
}

private fun convertJson(jn: JsonNode, source: String): CNode {
    val origins = listOf(Origin(source))
    return when {
        jn.isObject -> {
            val map = LinkedHashMap<String, CNode>()
            val it = jn.fields()
            while (it.hasNext()) {
                val (k, v) = it.next()
                map[k] = convertJson(v, source)
            }
            CNode.CObject(map, origins)
        }
        jn.isArray -> CNode.CArray(jn.map { convertJson(it, source) }, origins)
        jn.isNull -> CNode.CNull(origins)
        jn.isTextual -> CNode.CScalar(jn.textValue(), origins)
        jn.isBoolean -> CNode.CScalar(jn.booleanValue(), origins)
        jn.isIntegralNumber -> CNode.CScalar(jn.longValue(), origins)
        jn.isNumber -> CNode.CScalar(jn.doubleValue(), origins)
        else -> CNode.CScalar(jn.asText(), origins)
    }
}

private fun parseYaml(text: String, source: String): CNode {
    val root = try {
        Yaml(SafeConstructor(LoaderOptions())).compose(StringReader(text))
    } catch (e: Exception) {
        throw ParseException("无法解析 YAML 输入 ($source): ${e.message}")
    } ?: throw ParseException("空输入 ($source)")
    val seen = IdentityHashMap<Node, Boolean>()
    return convertYaml(root, source, seen, false)
}

private val MERGE_TAG = Tag("tag:yaml.org,2002:merge")

private fun convertYaml(n: Node, source: String, seen: IdentityHashMap<Node, Boolean>, inAlias: Boolean): CNode {
    val aliased = inAlias || seen.put(n, true) != null
    val origins = listOf(Origin(source, if (aliased) "alias-expanded" else null))
    return when (n) {
        is MappingNode -> {
            val merged = LinkedHashMap<String, CNode>()
            val explicit = LinkedHashMap<String, CNode>()
            for (tuple in n.value) {
                val keyNode = tuple.keyNode as? ScalarNode
                    ?: throw ParseException("不支持非标量键 ($source)")
                val isMerge = keyNode.tag == MERGE_TAG || (keyNode.tag == Tag.STR && keyNode.value == "<<")
                if (isMerge) {
                    // 合并键：展开被引用的映射（序列中越靠前优先级越高）
                    val sources = when (val v = tuple.valueNode) {
                        is MappingNode -> listOf(v)
                        is SequenceNode -> v.value.filterIsInstance<MappingNode>().reversed()
                        else -> throw ParseException("合并键的值必须是映射或映射序列 ($source)")
                    }
                    for (m in sources) {
                        val mObj = convertYaml(m, source, seen, aliased) as CNode.CObject
                        mObj.entries.forEach { (k, v) -> merged.putIfAbsent(k, v) }
                    }
                } else {
                    val key = keyNode.value
                    if (explicit.containsKey(key)) throw ParseException("重复的键 '$key' ($source)")
                    explicit[key] = convertYaml(tuple.valueNode, source, seen, aliased)
                }
            }
            explicit.forEach { (k, v) -> merged[k] = v } // 显式键覆盖合并来的键
            CNode.CObject(merged, origins)
        }
        is SequenceNode -> CNode.CArray(n.value.map { convertYaml(it, source, seen, aliased) }, origins)
        is ScalarNode -> scalarOf(n, origins)
        else -> throw ParseException("不支持的 YAML 节点 ($source)")
    }
}

private fun scalarOf(n: ScalarNode, origins: List<Origin>): CNode = when (n.tag) {
    Tag.NULL -> CNode.CNull(origins)
    Tag.STR -> CNode.CScalar(n.value, origins)
    Tag.BOOL -> CNode.CScalar(n.value.lowercase() in setOf("true", "yes", "on"), origins)
    Tag.INT -> {
        val v = n.value.replace("_", "")
        val num = try {
            when {
                v.startsWith("0x") -> v.substring(2).toLong(16)
                v.startsWith("0o") -> v.substring(2).toLong(8)
                else -> v.toLong()
            }
        } catch (e: NumberFormatException) {
            throw ParseException("无法解析整数 '${n.value}'")
        }
        CNode.CScalar(num, origins)
    }
    Tag.FLOAT -> {
        val v = n.value.replace("_", "").lowercase()
        val num = when (v) {
            ".inf", "+.inf" -> Double.POSITIVE_INFINITY
            "-.inf" -> Double.NEGATIVE_INFINITY
            ".nan" -> Double.NaN
            else -> v.toDouble()
        }
        CNode.CScalar(num, origins)
    }
    else -> CNode.CScalar(n.value, origins) // 时间戳等按字符串保留
}

/** CNode -> 普通 Jackson 树（丢弃溯源），用于导出。 */
fun toJsonTree(n: CNode): JsonNode {
    val f = jsonMapper.nodeFactory
    return when (n) {
        is CNode.CNull -> f.nullNode()
        is CNode.CScalar -> when (val v = n.value) {
            is String -> f.textNode(v)
            is Boolean -> f.booleanNode(v)
            is Long -> f.numberNode(v)
            is Int -> f.numberNode(v)
            is Double -> f.numberNode(v)
            else -> f.textNode(v.toString())
        }
        is CNode.CArray -> f.arrayNode().apply { n.items.forEach { add(toJsonTree(it)) } }
        is CNode.CObject -> f.objectNode().apply {
            n.entries.forEach { (k, v) -> set<JsonNode>(k, toJsonTree(v)) }
        }
    }
}

/** 稳定序列化：键顺序按解析/合并保留的顺序输出，保证同内容同文本。 */
fun serialize(n: CNode, format: Format): String =
    mapperFor(format).writerWithDefaultPrettyPrinter().writeValueAsString(toJsonTree(n))
