package merger

/** Canonical, deterministic JSON serialization of parsed/merged node values. */
object Canonical {
    fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u")
                    sb.append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
