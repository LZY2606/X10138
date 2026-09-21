package merger

/** SNode 与可持久化 JSON 结构（Map/List/String）之间的双向转换，保留来源信息。 */
object NodeJson {
    fun encode(node: SNode?): Any? = when (node) {
        null -> mapOf("t" to "absent")
        is SNode.SNull -> mapOf("t" to "null", "o" to encOrigin(node.origin))
        is SNode.SBool -> mapOf("t" to "bool", "v" to node.value, "o" to encOrigin(node.origin))
        is SNode.SNum -> mapOf("t" to "num", "v" to node.raw, "o" to encOrigin(node.origin))
        is SNode.SStr -> mapOf("t" to "str", "v" to node.value, "o" to encOrigin(node.origin))
        is SNode.SObj -> mapOf(
            "t" to "obj", "o" to encOrigin(node.origin),
            "ch" to node.children.map { (k, v) -> mapOf("k" to k, "v" to encode(v)) }
        )
        is SNode.SArr -> mapOf(
            "t" to "arr", "o" to encOrigin(node.origin),
            "items" to node.items.map { encode(it) }
        )
    }

    private fun encOrigin(o: Origin) = mapOf("side" to o.side, "doc" to o.doc, "line" to o.line)

    @Suppress("UNCHECKED_CAST")
    fun decode(raw: Any?): SNode? {
        val m = raw as? Map<String, Any?> ?: return null
        val origin = decOrigin(m["o"] as? Map<String, Any?>)
        return when (m["t"] as? String) {
            "absent" -> null
            "null" -> SNode.SNull(origin)
            "bool" -> SNode.SBool(m["v"] == true, origin)
            "num" -> SNode.SNum(m["v"] as? String ?: "0", origin)
            "str" -> SNode.SStr(m["v"] as? String ?: "", origin)
            "obj" -> {
                val children = LinkedHashMap<String, SNode>()
                for (e in (m["ch"] as? List<Map<String, Any?>>) ?: emptyList()) {
                    val key = e["k"] as String
                    val value = decode(e["v"]) ?: continue
                    children[key] = value
                }
                SNode.SObj(children, origin)
            }
            "arr" -> SNode.SArr(
                (m["items"] as? List<Any?>)?.mapNotNull { decode(it) } ?: emptyList(), origin
            )
            else -> null
        }
    }

    private fun decOrigin(m: Map<String, Any?>?): Origin {
        if (m == null) return Origin("?", "?", 0)
        val line = (m["line"] as? Number)?.toInt() ?: 0
        return Origin(m["side"] as? String ?: "?", m["doc"] as? String ?: "?", line)
    }
}

/** 把 Map/List 结构写成稳定 JSON 文本。 */
object JsonOut {
    fun write(value: Any?): String =
        StringBuilder().also { w(it, value, 0) }.toString() + "\n"

    @Suppress("UNCHECKED_CAST")
    private fun w(sb: StringBuilder, v: Any?, indent: Int) {
        val pad = "  ".repeat(indent)
        val padIn = "  ".repeat(indent + 1)
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> sb.append(v.toString())
            is String -> sb.append(Emit.jsonString(v))
            is Map<*, *> -> {
                val entries = v.entries.toList()
                if (entries.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                entries.forEachIndexed { i, (k, x) ->
                    sb.append(padIn).append(Emit.jsonString(k.toString())).append(": ")
                    w(sb, x, indent + 1)
                    if (i < entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(pad).append("}")
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.forEachIndexed { i, x ->
                    sb.append(padIn)
                    w(sb, x, indent + 1)
                    if (i < v.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(pad).append("]")
            }
            else -> sb.append(Emit.jsonString(v.toString()))
        }
    }
}
