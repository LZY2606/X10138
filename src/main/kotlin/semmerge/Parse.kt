package semmerge

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node as YNode
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode as YScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/**
 * 把 YAML / JSON 文本解析为带路径来源的 Node 树（JSON 是 YAML 子集，同一入口）。
 * 锚点与别名在转换时展开为独立副本 —— 模型不可变，输出不会制造共享可变引用。
 */
object Parser {

    fun parse(text: String, docId: String): Node {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 1000
        }
        val yml = Yaml(options)
        val root: YNode = yml.compose(text.reader())
            ?: return NullNode(listOf(SourceRef(docId, "/", "empty document")))
        return convert(root, docId, emptyList(), HashSet())
    }

    private fun convert(yn: YNode, docId: String, path: Path, visiting: MutableSet<YNode>): Node {
        val here = renderPath(path)
        val prov = listOf(SourceRef(docId, here))
        return when (yn) {
            is MappingNode -> {
                if (!visiting.add(yn)) throw IllegalArgumentException("recursive anchor not supported: $here")
                val entries = LinkedHashMap<String, Node>()
                for (tuple: NodeTuple in yn.value) {
                    val keyNode = tuple.keyNode as? YScalarNode
                        ?: throw IllegalArgumentException("non-string key not supported: $here")
                    entries[keyNode.value] = convert(tuple.valueNode, docId, path + PathSeg.Key(keyNode.value), visiting)
                }
                visiting.remove(yn)
                ObjNode(entries, prov)
            }
            is SequenceNode -> {
                if (!visiting.add(yn)) throw IllegalArgumentException("recursive anchor not supported: $here")
                val items = yn.value.mapIndexed { i, child ->
                    convert(child, docId, path + PathSeg.Idx(i), visiting)
                }
                visiting.remove(yn)
                ArrNode(items, prov)
            }
            is YScalarNode -> {
                if (yn.tag == Tag.NULL) NullNode(prov)
                else ScalarNode(scalarValue(yn), prov)
            }
            else -> throw IllegalArgumentException("unknown YAML node: $here")
        }
    }

    private fun scalarValue(yn: YScalarNode): Any? {
        val raw = yn.value
        return when (yn.tag) {
            Tag.BOOL -> raw.equals("true", ignoreCase = true) || raw.equals("on", ignoreCase = true)
            Tag.INT -> raw.replace("_", "").toLongOrNull() ?: raw
            Tag.FLOAT -> raw.replace("_", "").toDoubleOrNull() ?: raw
            Tag.NULL -> null
            else -> raw
        }
    }
}
