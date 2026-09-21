package semmerge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** 来源引用：某个值来自哪份文档的哪个路径，附带说明（如自动合并原因或裁决 id）。 */
data class SourceRef(val doc: String, val path: String, val note: String = "")

sealed interface PathSeg {
    data class Key(val name: String) : PathSeg
    data class Idx(val index: Int) : PathSeg
}

typealias Path = List<PathSeg>

fun renderPath(path: Path): String {
    if (path.isEmpty()) return "/"
    val sb = StringBuilder()
    for (seg in path) when (seg) {
        is PathSeg.Key -> sb.append('/').append(seg.name)
        is PathSeg.Idx -> sb.append('[').append(seg.index).append(']')
    }
    return sb.toString()
}

/** 解析后的配置节点。不可变；每个节点携带来源链。 */
sealed interface Node {
    val provenance: List<SourceRef>
    fun withProvenance(prov: List<SourceRef>): Node
}

data class ObjNode(
    val entries: LinkedHashMap<String, Node>,
    override val provenance: List<SourceRef> = emptyList()
) : Node {
    override fun withProvenance(prov: List<SourceRef>) = copy(provenance = prov)
}

data class ArrNode(
    val items: List<Node>,
    override val provenance: List<SourceRef> = emptyList()
) : Node {
    override fun withProvenance(prov: List<SourceRef>) = copy(provenance = prov)
}

data class ScalarNode(
    val value: Any?, // String | Boolean | Long | Double
    override val provenance: List<SourceRef> = emptyList()
) : Node {
    override fun withProvenance(prov: List<SourceRef>) = copy(provenance = prov)
}

/** 显式 null —— 与缺失（MissingNode）严格区分。 */
data class NullNode(
    override val provenance: List<SourceRef> = emptyList()
) : Node {
    override fun withProvenance(prov: List<SourceRef>) = copy(provenance = prov)
}

/** 缺失标记：仅存在于合并过程，绝不出现在输出文档中。 */
data object MissingNode : Node {
    override val provenance: List<SourceRef> get() = emptyList()
    override fun withProvenance(prov: List<SourceRef>): Node = this
}

// ---------- 规范化（用于结构相等与指纹） ----------

fun canonical(node: Node): JsonElement = when (node) {
    is ObjNode -> JsonObject(
        node.entries.entries
            .sortedBy { it.key }
            .associate { it.key to canonical(it.value) }
    )
    is ArrNode -> JsonArray(node.items.map { canonical(it) })
    is ScalarNode -> when (val v = node.value) {
        is Boolean -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v.toLong())
        is Double -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toDouble())
        null -> JsonNull
        else -> JsonPrimitive(v.toString())
    }
    is NullNode -> JsonNull
    MissingNode -> JsonPrimitive(" missing-sentinel")
}

private fun typeTag(node: Node): String = when (node) {
    is ObjNode -> "o"
    is ArrNode -> "a"
    is ScalarNode -> "s"
    is NullNode -> "n"
    MissingNode -> "m"
}

/** 结构相等：忽略来源信息；null 与缺失不相等，标量类型敏感。 */
fun structEq(a: Node, b: Node): Boolean =
    typeTag(a) == typeTag(b) && canonical(a) == canonical(b)

fun fingerprint(node: Node): String {
    val text = typeTag(node) + ":" + canonical(node).toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

// ---------- 节点 <-> JSON（带来源链，用于持久化与裁决重放） ----------

fun nodeToJson(node: Node): JsonObject {
    val prov = JsonArray(node.provenance.map {
        JsonObject(
            mapOf(
                "doc" to JsonPrimitive(it.doc),
                "path" to JsonPrimitive(it.path),
                "note" to JsonPrimitive(it.note)
            )
        )
    })
    return when (node) {
        is ObjNode -> JsonObject(
            mapOf(
                "t" to JsonPrimitive("obj"), "p" to prov,
                "e" to JsonObject(node.entries.mapValues { nodeToJson(it.value) })
            )
        )
        is ArrNode -> JsonObject(
            mapOf(
                "t" to JsonPrimitive("arr"), "p" to prov,
                "i" to JsonArray(node.items.map { nodeToJson(it) })
            )
        )
        is ScalarNode -> JsonObject(
            mapOf(
                "t" to JsonPrimitive("scalar"), "p" to prov,
                "v" to when (val v = node.value) {
                    is Boolean -> JsonPrimitive(v)
                    is Long -> JsonPrimitive(v)
                    is Int -> JsonPrimitive(v.toLong())
                    is Double -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v.toDouble())
                    null -> JsonNull
                    else -> JsonPrimitive(v.toString())
                }
            )
        )
        is NullNode -> JsonObject(mapOf("t" to JsonPrimitive("null"), "p" to prov))
        MissingNode -> JsonObject(mapOf("t" to JsonPrimitive("missing"), "p" to prov))
    }
}

fun nodeFromJson(el: JsonObject): Node {
    val prov = (el["p"] as? JsonArray)?.mapNotNull { p ->
        (p as? JsonObject)?.let {
            SourceRef(
                (it["doc"] as? JsonPrimitive)?.content ?: "",
                (it["path"] as? JsonPrimitive)?.content ?: "",
                (it["note"] as? JsonPrimitive)?.content ?: ""
            )
        }
    } ?: emptyList()
    return when ((el["t"] as? JsonPrimitive)?.content) {
        "obj" -> ObjNode(
            LinkedHashMap((el["e"] as JsonObject).entries.associate { it.key to nodeFromJson(it.value as JsonObject) }),
            prov
        )
        "arr" -> ArrNode((el["i"] as JsonArray).map { nodeFromJson(it as JsonObject) }, prov)
        "scalar" -> {
            val v = el["v"]
            val value: Any? = when {
                v == null || v is JsonNull -> null
                v is JsonPrimitive && v.isString -> v.content
                v is JsonPrimitive -> v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
                else -> v.toString()
            }
            ScalarNode(value, prov)
        }
        "null" -> NullNode(prov)
        "missing" -> MissingNode
        else -> throw IllegalArgumentException("unknown node json: $el")
    }
}

// ---------- 输出（普通文档树 + 确定性 YAML/JSON 文本） ----------

fun toPlain(node: Node): Any? = when (node) {
    is ObjNode -> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in node.entries) m[k] = toPlain(v)
        m
    }
    is ArrNode -> node.items.map { toPlain(it) }
    is ScalarNode -> node.value
    is NullNode -> null
    MissingNode -> throw IllegalStateException("MissingNode 不得出现在输出中")
}

/** 确定性 JSON 序列化（保持合并后的键顺序）。 */
fun toJsonText(node: Node, indent: String = "  "): String {
    val sb = StringBuilder()
    fun write(n: Node, level: Int) {
        when (n) {
            is ObjNode -> {
                if (n.entries.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                val it = n.entries.entries.iterator()
                while (it.hasNext()) {
                    val (k, v) = it.next()
                    sb.append(indent.repeat(level + 1)).append(quoteJson(k)).append(": ")
                    write(v, level + 1)
                    if (it.hasNext()) sb.append(',')
                    sb.append('\n')
                }
                sb.append(indent.repeat(level)).append('}')
            }
            is ArrNode -> {
                if (n.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                n.items.forEachIndexed { i, v ->
                    sb.append(indent.repeat(level + 1))
                    write(v, level + 1)
                    if (i < n.items.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(indent.repeat(level)).append(']')
            }
            is ScalarNode -> when (val v = n.value) {
                is String -> sb.append(quoteJson(v))
                is Boolean -> sb.append(v.toString())
                is Number -> sb.append(v.toString())
                null -> sb.append("null")
                else -> sb.append(quoteJson(v.toString()))
            }
            is NullNode -> sb.append("null")
            MissingNode -> throw IllegalStateException("MissingNode 不得出现在输出中")
        }
    }
    write(node, 0)
    sb.append('\n')
    return sb.toString()
}

private fun quoteJson(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}

/** 收集路径 -> 来源链，用于持久化与导出包。 */
fun provenanceMap(node: Node, path: Path = emptyList()): List<Pair<String, List<SourceRef>>> {
    val out = mutableListOf<Pair<String, List<SourceRef>>>()
    if (node.provenance.isNotEmpty()) out.add(renderPath(path) to node.provenance)
    when (node) {
        is ObjNode -> node.entries.forEach { (k, v) -> out.addAll(provenanceMap(v, path + PathSeg.Key(k))) }
        is ArrNode -> node.items.forEachIndexed { i, v -> out.addAll(provenanceMap(v, path + PathSeg.Idx(i))) }
        else -> {}
    }
    return out
}
