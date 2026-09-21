package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArrayPolicyTest {
    private fun merge(base: String?, a: String, b: String, policy: ArrayPolicy, idKey: String? = null): MergeDocument {
        val registry = PolicyRegistry(
            1,
            mapOf("servers[]" to policy),
            idKey?.let { mapOf("servers[]" to it) } ?: emptyMap(),
        )
        return MergeEngine(registry).merge(
            base?.let { YamlParser("base.yaml").parse(it) },
            YamlParser("a.yaml").parse(a),
            YamlParser("b.yaml").parse(b),
        )
    }

    @Test
    fun `unregistered array policy produces MISSING_POLICY and no guess`() {
        val doc = merge(
            "servers:\n  - 1\n",
            "servers:\n  - 1\n  - 2\n",
            "servers:\n  - 1\n  - 3\n",
            // 用无策略引擎
            ArrayPolicy.REPLACE,
        ).let {
            MergeEngine(PolicyRegistry(1, emptyMap())).merge(
                YamlParser("base.yaml").parse("servers:\n  - 1\n"),
                YamlParser("a.yaml").parse("servers:\n  - 1\n  - 2\n"),
                YamlParser("b.yaml").parse("servers:\n  - 1\n  - 3\n"),
            )
        }
        assertEquals(1, doc.conflicts.size)
        assertEquals(ConflictType.MISSING_POLICY, doc.conflicts[0].type)
        assertTrue(doc.conflicts[0].message.contains("策略"))
    }

    @Test
    fun `replace policy merges when only one side changes`() {
        val doc = merge(
            "servers:\n  - 1\n",
            "servers:\n  - 1\n  - 2\n",
            "servers:\n  - 1\n",
            ArrayPolicy.REPLACE,
        )
        val arr = ((doc.result as RNode.RObj).children["servers"] as RNode.RArr)
        assertEquals(2, arr.items.size)
        assertTrue(doc.conflicts.isEmpty())
    }

    @Test
    fun `replace policy conflicts when both sides differ`() {
        val doc = merge(
            "servers:\n  - 1\n",
            "servers:\n  - 2\n",
            "servers:\n  - 3\n",
            ArrayPolicy.REPLACE,
        )
        assertEquals(1, doc.conflicts.size)
    }

    @Test
    fun `id policy merges element fields and additions independently`() {
        val doc = merge(
            """
            servers:
              - id: w1
                port: 8080
              - id: w2
                port: 8080
            """.trimIndent(),
            """
            servers:
              - id: w1
                port: 9090
              - id: w2
                port: 8080
              - id: w3
                port: 8080
            """.trimIndent(),
            """
            servers:
              - id: w1
                port: 8080
              - id: w2
                port: 8443
              - id: w4
                port: 8080
            """.trimIndent(),
            ArrayPolicy.ID,
        )
        assertTrue(doc.conflicts.isEmpty(), doc.conflicts.map { it.message }.toString())
        val arr = ((doc.result as RNode.RObj).children["servers"] as RNode.RArr)
        val byId = arr.items.associateBy { it.path.segments.filterIsInstance<PathSeg.Key>().last().key }
        assertEquals(4, byId.size)
        fun port(id: String) =
            (((byId[id] as RNode.RObj).children["port"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt()
        assertEquals(9090, port("w1"))
        assertEquals(8443, port("w2"))
    }

    @Test
    fun `id policy merges delete in one side`() {
        val doc = merge(
            """
            servers:
              - id: w1
                port: 8080
              - id: w2
                port: 8080
            """.trimIndent(),
            """
            servers:
              - id: w2
                port: 8080
            """.trimIndent(),
            """
            servers:
              - id: w1
                port: 8080
              - id: w2
                port: 8080
            """.trimIndent(),
            ArrayPolicy.ID,
        )
        val arr = ((doc.result as RNode.RObj).children["servers"] as RNode.RArr)
        val byId = arr.items.associateBy { it.path.segments.filterIsInstance<PathSeg.Key>().last().key }
        assertFalse(byId.containsKey("w1"))
        assertTrue(byId.containsKey("w2"))
    }

    @Test
    fun `duplicate id is reported as DUP_ID conflict`() {
        val doc = merge(
            """
            servers:
              - id: w1
                port: 8080
            """.trimIndent(),
            """
            servers:
              - id: w1
                port: 8080
              - id: w1
                port: 9090
            """.trimIndent(),
            """
            servers:
              - id: w1
                port: 8080
            """.trimIndent(),
            ArrayPolicy.ID,
        )
        assertEquals(1, doc.conflicts.size)
        assertEquals(ConflictType.DUP_ID, doc.conflicts[0].type)
        val detail = doc.conflicts[0].detail as ConflictDetail.DupId
        assertEquals("w1", detail.id)
        assertEquals(2, detail.count)
    }

    @Test
    fun `order move in one side is accepted without conflict`() {
        val doc = merge(
            """
            servers:
              - id: w1
              - id: w2
              - id: w3
            """.trimIndent(),
            """
            servers:
              - id: w3
              - id: w1
              - id: w2
            """.trimIndent(),
            """
            servers:
              - id: w1
              - id: w2
              - id: w3
            """.trimIndent(),
            ArrayPolicy.ID,
        )
        assertTrue(doc.conflicts.isEmpty(), doc.conflicts.map { it.message }.toString())
        val arr = ((doc.result as RNode.RObj).children["servers"] as RNode.RArr)
        val ids = arr.items.map { it.path.segments.filterIsInstance<PathSeg.Key>().last().key }
        assertEquals(listOf("w3", "w1", "w2"), ids)
    }

    @Test
    fun `incompatible moves both sides produce ORDER conflict`() {
        val doc = merge(
            """
            servers:
              - id: w1
              - id: w2
              - id: w3
            """.trimIndent(),
            """
            servers:
              - id: w3
              - id: w1
              - id: w2
            """.trimIndent(),
            """
            servers:
              - id: w2
              - id: w1
              - id: w3
            """.trimIndent(),
            ArrayPolicy.ID,
        )
        assertEquals(1, doc.conflicts.size)
        assertEquals(ConflictType.ORDER, doc.conflicts[0].type)
    }
}
