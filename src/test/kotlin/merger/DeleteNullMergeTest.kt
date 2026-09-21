package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeleteNullMergeTest {
    private val policies = PolicyRegistry(1, emptyMap())

    private fun merge(yamlBase: String?, yamlA: String, yamlB: String): MergeDocument {
        val base = yamlBase?.let { YamlParser("base.yaml").parse(it) }
        val a = YamlParser("a.yaml").parse(yamlA)
        val b = YamlParser("b.yaml").parse(yamlB)
        return MergeEngine(policies).merge(base, a, b)
    }

    @Test
    fun `delete versus untouched key auto-merges as deletion`() {
        val doc = merge(
            "x: 1\ny: 2\n",
            "y: 2\n",
            "x: 1\ny: 2\n",
        )
        val root = doc.result as RNode.RObj
        assertFalse(root.children.containsKey("x"))
        assertEquals(2, ((root.children["y"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt())
        assertTrue(doc.conflicts.isEmpty())
    }

    @Test
    fun `both delete auto-merges`() {
        val doc = merge("x: 1\n", "{}\n", "{}\n")
        val root = doc.result as RNode.RObj
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun `delete versus modify is a real conflict`() {
        val doc = merge(
            "x: 1\n",
            "{}\n",
            "x: 2\n",
        )
        assertEquals(1, doc.conflicts.size)
        assertEquals(ConflictType.VALUE, doc.conflicts[0].type)
        assertTrue(doc.conflicts[0].message.contains("删除"))
    }

    @Test
    fun `explicit null merges like a value and differs from deletion`() {
        // A 显式置 null，B 未改 -> 结果是显式 null，而非删除
        val doc = merge(
            "x: 1\n",
            "x: null\n",
            "x: 1\n",
        )
        val root = doc.result as RNode.RObj
        assertTrue(root.children.containsKey("x"), "显式 null 的键必须保留")
        assertEquals(ScalarValue.NullVal, (root.children["x"] as RNode.RScalar).value)
    }

    @Test
    fun `null on one side and different value on the other conflicts`() {
        val doc = merge(
            "x: 1\n",
            "x: null\n",
            "x: 2\n",
        )
        assertEquals(1, doc.conflicts.size)
    }

    @Test
    fun `new key added by one side merges even when other side lacks it`() {
        val doc = merge(
            "x: 1\n",
            "x: 1\ny: 2\n",
            "x: 1\n",
        )
        val root = doc.result as RNode.RObj
        assertEquals(2, root.children.size)
    }

    @Test
    fun `nested recursive merge`() {
        val doc = merge(
            """
            svc:
              replicas: 1
              timeout: 30
            """.trimIndent(),
            """
            svc:
              replicas: 2
              timeout: 30
            """.trimIndent(),
            """
            svc:
              replicas: 1
              timeout: 45
            """.trimIndent(),
        )
        val svc = (doc.result as RNode.RObj).children["svc"] as RNode.RObj
        assertEquals(2, ((svc.children["replicas"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt())
        assertEquals(45, ((svc.children["timeout"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt())
        assertTrue(doc.conflicts.isEmpty())
    }

    @Test
    fun `same change both sides auto merges`() {
        val doc = merge("x: 1\n", "x: 9\n", "x: 9\n")
        val root = doc.result as RNode.RObj
        assertEquals(9, ((root.children["x"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt())
    }
}
