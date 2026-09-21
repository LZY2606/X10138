package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeleteNullAliasTest {

    @Test
    fun `missing, explicit null and delete are three distinct states`() {
        val base = """
            name: svc
            keep: 1
            gone: x
        """.trimIndent()
        // A 删除 gone；B 把 gone 设为 null；name 两边一致；keep 只有 A 改
        val a = """
            name: svc
            keep: 2
            gone: !delete
        """.trimIndent()
        val b = """
            name: svc
            keep: 1
            gone: null
        """.trimIndent()
        val o = Merger.merge(TestSupport.docs(base, a, b))
        val gone = o.conflictIndex["\$.gone"]
        assertNotNull(gone, "删除 vs null 必须是冲突")
        assertEquals(ConflictKind.DELETE_NULL, gone!!.conflict!!.kind)
        val all = mutableListOf<MergeNode>()
        fun walk(n: MergeNode) { all.add(n); n.children.values.forEach { walk(it) } }
        walk(o.root)
        assertTrue(all.any { it.path == "\$.keep" && it.status == MergeStatus.AUTO })
        // 缺失字段（A 中 gone 缺失）不应等于删除墓碑
        // A 中 gone 是显式删除墓碑；另验证真正缺失的字段根本不存在于条目表
        val aDoc = ConfigParser.parse(a, Source.A)
        assertTrue((aDoc.root as SMap).entries["gone"] is SSDelete)
        val missingDoc = ConfigParser.parse("name: svc\n", Source.A)
        assertFalse((missingDoc.root as SMap).entries.containsKey("gone"))
    }

    @Test
    fun `explicit delete tag removes key while null stays null when only one side acts`() {
        val base = "a: 1\nb: 2\nc: 3\n"
        val a = "a: !delete\n# b untouched\nc: 3\n"
        val b = "a: 1\nb: null\nc: 3\n"
        val o = Merger.merge(TestSupport.docs(base, a, b))
        // a: A 删除；B 未动 a -> 自动采用删除；b: B 设 null，A 未动 -> 自动 null
        val mat = o.materialized as SMap
        assertFalse(mat.entries.containsKey("a"), "删除的键不应出现在结果中")
        assertTrue(mat.entries["b"] is SSNull, "显式 null 必须保留为 null")
    }

    @Test
    fun `aliases expand into independent non-shared structures`() {
        val yaml = """
            defaults: &d
              timeout: 30
              retries: 3
            x: *d
            y: *d
        """.trimIndent()
        val doc = ConfigParser.parse(yaml, Source.BASE)
        val root = doc.root as SMap
        val x = root["x"] as SMap
        val y = root["y"] as SMap
        assertNotSame(x, y)
        // 修改 x 不影响 y：无共享可变引用
        x.with("timeout", SSNumber(java.math.BigDecimal(99)))
        assertEquals("30", Canonical.normalizeNumber((y["timeout"] as SSNumber).value))
        // 来源元数据记录了别名
        val xMeta = doc.metaOf(x)
        assertTrue(xMeta.any { it.viaAlias && it.name == "d" })
        // 输出不含锚点/别名
        val out = Emitter.toYaml(root)
        assertFalse(out.contains("*d"))
        assertFalse(out.contains("&d"))
    }

    @Test
    fun `alias changes merge per side without aliasing result`() {
        val base = """
            t: &t { port: 80 }
            u: *t
        """.trimIndent()
        val a = """
            t: &t { port: 8080 }
            u: *t
        """.trimIndent()
        val b = """
            t: &t { port: 80 }
            u: *t
        """.trimIndent()
        val o = Merger.merge(TestSupport.docs(base, a, b))
        assertEquals(0, o.unresolvedCount)
        val mat = o.materialized as SMap
        assertEquals(java.math.BigDecimal(8080), (mat["u"] as SMap)["port"]?.let { (it as SSNumber).value })
        assertNotSame((mat["t"] as SMap), (mat["u"] as SMap))
    }

    @Test
    fun `duplicate keys are rejected`() {
        val bad = "a: 1\na: 2\n"
        assertThrows(ParseException::class.java) { ConfigParser.parse(bad, Source.A) }
    }
}
