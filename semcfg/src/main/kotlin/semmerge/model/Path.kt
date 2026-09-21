package semmerge.model

/** Immutable configuration path. Rendered as JSONPath-ish: `$.services[0].port`. */
class Path internal constructor(val segments: List<Segment>) {

    sealed class Segment {
        data class Key(val name: String) : Segment()
        data class Index(val value: Int) : Segment()
        /** Element addressed by stable id inside an id-merged array. */
        data class Id(val id: String) : Segment()
    }

    fun child(key: String): Path = Path(segments + Segment.Key(key))
    fun index(i: Int): Path = Path(segments + Segment.Index(i))
    fun id(id: String): Path = Path(segments + Segment.Id(id))

    fun render(): String {
        if (segments.isEmpty()) return "$"
        val sb = StringBuilder("$")
        for (s in segments) when (s) {
            is Segment.Key -> {
                sb.append('.')
                if (isSimple(s.name)) sb.append(s.name) else sb.append('[').append(jsonishQuote(s.name)).append(']')
            }
            is Segment.Index -> sb.append('[').append(s.value).append(']')
            is Segment.Id -> sb.append("[id=").append(jsonishQuote(s.id)).append(']')
        }
        return sb.toString()
    }

    override fun toString(): String = render()
    override fun equals(other: Any?): Boolean = other is Path && other.segments == segments
    override fun hashCode(): Int = segments.hashCode()

    companion object {
        val ROOT = Path(emptyList())
        fun of(vararg keys: String): Path = Path(keys.map { Segment.Key(it) })

        private fun isSimple(name: String): Boolean =
            name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        private fun jsonishQuote(s: String): String =
            buildString {
                append('\'')
                for (c in s) when (c) {
                    '\'' -> append("\\'")
                    '\\' -> append("\\\\")
                    else -> append(c)
                }
                append('\'')
            }
    }
}
