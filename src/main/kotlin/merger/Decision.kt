package merger

/**
 * A human decision. Bound to fingerprints of base/ours/theirs at the path and
 * to the policy registry version. If any side changes, the fingerprint stops
 * matching and the old decision becomes advisory only.
 */
data class Decision(
    val id: String,
    val path: Path,
    val baseFp: String?,
    val oursFp: String?,
    val theirsFp: String?,
    val policyVersion: Int,
    val choice: Choice,
    /** When choice == CUSTOM, a parsed replacement value. */
    val customText: String? = null,
    val customFormat: String = "yaml",
    /** For ordered-sequence chunks: OURS/THEIRS/BASE selection per chunk. */
    val chunkChoices: Map<Int, Choice> = emptyMap(),
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun fingerprintBinding(): String = listOf(
        path.toString(), baseFp ?: "∅", oursFp ?: "∅", theirsFp ?: "∅", policyVersion.toString(),
    ).joinToString("|")

    fun matches(path: Path, base: PNode?, ours: PNode?, theirs: PNode?, policyVersion: Int): Boolean =
        this.path == path &&
            baseFp == base?.fingerprint() &&
            oursFp == ours?.fingerprint() &&
            theirsFp == theirs?.fingerprint() &&
            this.policyVersion == policyVersion
}

enum class DecisionBindState { BOUND, STALE }
