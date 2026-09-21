package semmerge

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Node -> 可 JSON 序列化的结构（含来源链）。 */
fun nodeToJson(n: Node?): Any? = when (n) {
    null -> null
    is Node.NullNode -> mapOf("type" to "null", "prov" to provJson(n.prov))
    is Node.Scalar -> mapOf(
        "type" to "scalar", "kind" to n.kind.name, "value" to n.value, "prov" to provJson(n.prov)
    )
    is Node.Arr -> mapOf(
        "type" to "arr", "items" to n.items.map { nodeToJson(it) }, "prov" to provJson(n.prov)
    )
    is Node.Obj -> mapOf(
        "type" to "obj",
        "entries" to n.entries.map { (k, v) -> mapOf("key" to k, "value" to nodeToJson(v)) },
        "prov" to provJson(n.prov)
    )
}

fun provJson(prov: List<Prov>) = prov.map { mapOf("side" to it.side, "path" to it.path) }

@Suppress("UNCHECKED_CAST")
fun nodeFromJson(j: Any?): Node? {
    if (j == null) return null
    val m = j as Map<String, Any?>
    val prov = (m["prov"] as? List<Map<String, Any?>>)?.map {
        Prov(it["side"] as String, it["path"] as String)
    } ?: emptyList()
    return when (m["type"] as String) {
        "null" -> Node.NullNode(prov)
        "scalar" -> Node.Scalar(m["value"] as String, Node.Kind.valueOf(m["kind"] as String), prov)
        "arr" -> Node.Arr((m["items"] as List<Any?>).map { nodeFromJson(it) ?: Node.NullNode(emptyList()) }, prov)
        "obj" -> Node.Obj(
            LinkedHashMap((m["entries"] as List<Map<String, Any?>>).map {
                (it["key"] as String) to (nodeFromJson(it["value"]) ?: Node.NullNode(emptyList()))
            }.toMap().entries.associate { it.toPair() }),
            prov
        )
        else -> throw IllegalArgumentException("未知节点类型: ${m["type"]}")
    }
}

/** 稳定 YAML 输出：保持键顺序、块风格、无锚点别名（绝不产生共享引用）。 */
object YamlEmitter {
    fun emit(n: Node?): String {
        if (n == null) return ""
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val yaml = Yaml(opts)
        return yaml.dump(toPlain(n))
    }

    fun toPlain(n: Node?): Any? = when (n) {
        null -> null
        is Node.NullNode -> null
        is Node.Scalar -> when (n.kind) {
            Node.Kind.NUMBER -> n.value.toBigDecimalOrNull() ?: n.value
            Node.Kind.BOOL -> n.value.toBooleanStrictOrNull() ?: n.value
            Node.Kind.STRING -> n.value
        }
        is Node.Arr -> n.items.map { toPlain(it) }
        is Node.Obj -> LinkedHashMap(n.entries.map { (k, v) -> k to toPlain(v) }.toMap())
    }
}
