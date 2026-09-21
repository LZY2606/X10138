package merger

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.nodes.*

data class ParsedDoc(
    val side: Side,
    val format: String,
    val root: PNode,
    val rawText: String,
    val warnings: List<String>,
) {
    val contentFingerprint: String get() = root.fingerprint()
}

object Parser {
    private val INT_TAG = "tag:yaml.org,2002:int"
    private val FLOAT_TAG = "tag:yaml.org,2002:float"
    private val BOOL_TAG = "tag:yaml.org,2002:bool"
    private val NULL_TAG = "tag:yaml.org,2002:null"

    fun parse(side: Side, fileName: String, text: String): ParsedDoc {
        val format = when {
            fileName.lowercase().endsWith(".json") -> "json"
            text.trimStart().startsWith("{") || text.trimStart().startsWith("[") -> "json"
            else -> "yaml"
        }
        val warnings = mutableListOf<String>()
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = true
            isProcessComments = false
        }
        val yaml = org.yaml.snakeyaml.Yaml(options)
        val node = yaml.compose(text.reader())
        val anchors = mutableMapOf<String, PNode>()
        val root = if (node == null) {
            PScalar(null, ScalarTag.NULL, SourceInfo(side, Path.ROOT, 0))
        } else {
            build(node, side, Path.ROOT, 0, anchors, warnings)
        }
        return ParsedDoc(side, format, root, text, warnings)
    }

    private fun build(
        node: Node,
        side: Side,
        path: Path,
        order: Int,
        anchors: MutableMap<String, PNode>,
        warnings: MutableList<String>,
    ): PNode {
        val line = node.startMark?.let { it.line + 1 }
        val anchor = node.anchor
        when (node) {
            is ScalarNode -> {
                val (tag, value) = resolveScalar(node)
                val made = PScalar(value, tag, SourceInfo(side, path, order, anchor, line))
                if (anchor != null) anchors[anchor] = made
                return made
            }
            is SequenceNode -> {
                val items = node.value.mapIndexed { i, child ->
                    build(child, side, path + ("[" + i + "]"), i, anchors, warnings)
                }
                val made = PSeq(items, SourceInfo(side, path, order, anchor, line))
                if (anchor != null) anchors[anchor] = made
                return made
            }
            is MappingNode -> {
                val seen = mutableSetOf<String>()
                val entries = node.value.mapIndexedNotNull { i, tuple ->
                    val keyNode = tuple.keyNode
                    if (keyNode !is ScalarNode) {
                        warnings.add("line ${line}: non-scalar map key ignored")
                        return@mapIndexedNotNull null
                    }
                    val key = keyNode.value
                    if (!seen.add(key)) {
                        warnings.add("line ${line}: duplicate map key '$key' at path $path")
                    }
                    val value = build(tuple.valueNode, side, path + key, i, anchors, warnings)
                    MapEntry(key, value, i)
                }
                val made = PMap(entries, SourceInfo(side, path, order, anchor, line))
                if (anchor != null) anchors[anchor] = made
                return made
            }
            else -> Unit
        }
        if (node is AnchorNode) {
            val target = anchors[node.anchor]
                ?: throw MergeException("line ${line}: unresolved alias *${node.anchor}")
            val copy = deepCopyWithSource(target, SourceInfo(side, path, order, node.anchor, line))
            return copy
        }
        throw MergeException("unsupported node type ${node.nodeId}")
    }

    private fun resolveScalar(node: ScalarNode): Pair<ScalarTag, String?> {
        return when (node.tag?.value) {
            NULL_TAG -> ScalarTag.NULL to null
            INT_TAG -> ScalarTag.INT to node.value
            FLOAT_TAG -> ScalarTag.FLOAT to normalizeFloat(node.value)
            BOOL_TAG -> ScalarTag.BOOL to node.value.lowercase()
            STRING_TAG -> ScalarTag.STRING to node.value
            else -> ScalarTag.STRING to node.value
        }
    }

    private val STRING_TAG = "tag:yaml.org,2002:str"

    private fun normalizeFloat(raw: String): String {
        if (raw.any { it == 'n' || it == 'N' }) return raw
        val d = raw.replace("_", "").lowercase().toDoubleOrNull() ?: return raw
        return if (d.isFinite()) d.toString() else raw
    }

    /** Rebuild an aliased subtree with distinct objects and alias-site provenance. */
    private fun deepCopyWithSource(node: PNode, src: SourceInfo): PNode = when (node) {
        is PScalar -> node.copy(src = src)
        is PSeq -> PSeq(
            node.items.mapIndexed { i, child ->
                deepCopyWithSource(child, child.src.copy(side = src.side, path = src.path + ("[" + i + "]"), order = i))
            },
            src,
        )
        is PMap -> PMap(
            node.entries.map { e ->
                e.copy(value = deepCopyWithSource(e.value, e.value.src.copy(side = src.side, path = src.path + e.key, order = e.order)))
            },
            src,
        )
    }
}

class MergeException(message: String) : RuntimeException(message)
