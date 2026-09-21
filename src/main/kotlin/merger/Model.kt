package merger

/**
 * Parsed document model. Every node carries provenance ([SourceInfo]):
 * which side it came from, its path in that document, ordering index and
 * optional anchor name. Maps/sequences keep insertion order.
 */
enum class Side { BASE, OURS, THEIRS }

data class Path(val segments: List<String>) {
    operator fun plus(segment: String) = Path(segments + segment)
    override fun toString() = segments.joinToString(".")
    companion object {
        val ROOT = Path(emptyList())
        fun of(vararg segs: String) = Path(segs.toList())
        fun parse(text: String): Path =
            if (text.isBlank()) ROOT else Path(text.split("."))
    }
}

data class SourceInfo(
    val side: Side,
    val path: Path,
    val order: Int = 0,
    val anchor: String? = null,
    val startLine: Int? = null,
)

sealed class PNode {
    abstract val src: SourceInfo
    /** Canonical fingerprint of the value (source-independent). */
    abstract fun fingerprint(): String
}

data class PScalar(val value: String?, val tag: ScalarTag, override val src: SourceInfo) : PNode() {
    override fun fingerprint(): String = "S:" + tag.code + ":" + (value ?: "∅")
}

enum class ScalarTag(val code: String) {
    STRING("str"),
    INT("int"),
    FLOAT("flt"),
    BOOL("bool"),
    NULL("null"),
}

data class PMap(val entries: List<MapEntry>, override val src: SourceInfo) : PNode() {
    fun get(key: String): PNode? = entries.firstOrNull { it.key == key }?.value
    override fun fingerprint(): String {
        val body = entries.joinToString(",") { e ->
            Canonical.jsonString(e.key) + ":" + e.value.fingerprint()
        }
        return "M{" + Hashing.sha256(body) + "}"
    }
}

data class MapEntry(val key: String, val value: PNode, val order: Int)

data class PSeq(val items: List<PNode>, override val src: SourceInfo, val anchors: List<String?> = emptyList()) : PNode() {
    override fun fingerprint(): String {
        val body = items.joinToString(",") { it.fingerprint() }
        return "Q[" + Hashing.sha256(body) + "]"
    }
}
