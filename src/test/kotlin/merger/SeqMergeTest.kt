package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SeqMergeTest {
    private fun seqDoc(base: String?, a: String, b: String): MergeDocument {
        val registry = PolicyRegistry(1, mapOf("items[]" to ArrayPolicy.SEQ))
        return MergeEngine(registry).merge(
            base?.let { YamlParser("base.yaml").parse(it) },
            YamlParser("a.yaml").parse(a),
            YamlParser("b.yaml").parse(b),
        )
    }

    @Test
    fun `independent insertions at different positions merge via diff3`() {
        val doc = seqDoc(
            """
            items:
              - x1
              - x2
              - x3
            """.trimIndent(),
            """
            items:
              - x1
              - aa
              - x2
              - x3
            """.trimIndent(),
            """
            items:
              - x1
              - x2
              - x3
              - bb
            """.trimIndent(),
        )
        assertTrue(doc.conflicts.isEmpty(), doc.conflicts.map { it.message }.toString())
        val arr = (doc.result as RNode.RObj).children["items"] as RNode.RArr
        val vals = arr.items.map { ((it as RNode.RScalar).value as ScalarValue.StrVal).value }
        assertEquals(listOf("x1", "aa", "x2", "x3", "bb"), vals)
    }

    @Test
    fun `same insertion both sides appears once`() {
        val doc = seqDoc(
            "items:\n  - x1\n",
            "items:\n  - n\n  - x1\n",
            "items:\n  - n\n  - x1\n",
        )
        val arr = (doc.result as RNode.RObj).children["items"] as RNode.RArr
        val vals = arr.items.map { ((it as RNode.RScalar).value as ScalarValue.StrVal).value }
        assertEquals(listOf("n", "x1"), vals)
        assertTrue(doc.conflicts.isEmpty())
    }

    @Test
    fun `overlapping edits create SEQ_HUNK conflict`() {
        val doc = seqDoc(
            "items:\n  - x1\n  - x2\n  - x3\n",
            "items:\n  - x1\n  - aa\n  - x3\n",
            "items:\n  - x1\n  - bb\n  - x3\n",
        )
        assertEquals(1, doc.conflicts.size)
        assertEquals(ConflictType.SEQ_HUNK, doc.conflicts[0].type)
        val hunk = doc.conflicts[0].detail as ConflictDetail.SeqHunk
        fun text(nodes: List<Node>) = nodes.map { ((it as Node.Scalar).value as ScalarValue.StrVal).value }
        assertEquals(listOf("x2"), text(hunk.base))
        assertEquals(listOf("aa"), text(hunk.a))
        assertEquals(listOf("bb"), text(hunk.b))
        // 结果中保留一个冲突占位
        val arr = (doc.result as RNode.RObj).children["items"] as RNode.RArr
        assertTrue(arr.items.any { it is RNode.RConflictRef })
    }

    @Test
    fun `deletion on one side and edit on other merges via diff3`() {
        val doc = seqDoc(
            "items:\n  - x1\n  - x2\n  - x3\n",
            "items:\n  - x1\n  - x3\n",
            "items:\n  - x1\n  - x2+\n  - x3\n",
        )
        assertTrue(doc.conflicts.isEmpty(), doc.conflicts.map { it.message }.toString())
        val arr = (doc.result as RNode.RObj).children["items"] as RNode.RArr
        val vals = arr.items.map { ((it as RNode.RScalar).value as ScalarValue.StrVal).value }
        assertEquals(listOf("x1", "x2+", "x3"), vals)
    }
}
