package semmerge.iof

import semmerge.json.JsonWriter
import semmerge.model.SNode
import java.security.MessageDigest

/**
 * Content fingerprints. Canonical JSON (sorted keys) is hashed with SHA-256
 * so the same semantic value always has the same fingerprint regardless of
 * key order, quoting, anchors or input format.
 */
object Fingerprint {
    const val MISSING = "missing:0000000000000000000000000000000000000000000000000000000000000000"
    private const val DELETED = "deleted:0000000000000000000000000000000000000000000000000000000000000000"

    fun of(node: SNode?): String {
        if (node == null) return MISSING
        return sha256(JsonWriter.write(node, sortKeys = true))
    }

    /** Fingerprint of an explicit delete action (distinct from null or missing). */
    fun deleted(): String = DELETED

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Fingerprint bundle of the three inputs a decision was made against. */
data class TripleFingerprint(val base: String, val branchA: String, val branchB: String) {
    fun toMap(): Map<String, String> = mapOf("base" to base, "branchA" to branchA, "branchB" to branchB)

    companion object {
        fun fromMap(m: Map<String, Any?>): TripleFingerprint =
            TripleFingerprint(m["base"] as String, m["branchA"] as String, m["branchB"] as String)
    }
}
