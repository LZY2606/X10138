package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.IdentityHashMap

private fun y(s: String, src: String = "test") = parse(s.trimIndent(), Format.YAML, src)

private fun policies(vararg ps: ArrayPolicy) =
    PolicySet("v1", ps.associateBy { it.path })

class MergerTest {

    @Test
    fun `删除与 null 是不同动作`() {
        val base = y("a: 1\nb: 2\nc: 3", "base")
        val ours = y("a: 1\nb: 2", "ours")            // 删除 c
        val theirs = y("a: 1\nb: 2\nc:", "theirs")    // c 设为 null
        val r = Merger(policies()).merge(base, ours, theirs)
        assertEquals(1, r.conflicts.size)
        val c = r.conflicts[0]
        assertEquals("/c", c.path)
        assertEquals("delete-vs-modify", c.reason)
        assertEquals(MISSING_FP, c.oursFp)
        assertNotEquals(MISSING_FP, c.theirsFp) // null 有自己的指纹
        assertTrue(c.theirs is CNode.CNull)
    }

    @Test
    fun `缺失字段与 null 不混为一谈`() {
        val base = y("a: 1", "base")
        val ours = y("a: 1\nb:", "ours")   // 新增 null
        val theirs = y("a: 1", "theirs")   // 未动
        val r = Merger(policies()).merge(base, ours, theirs)
        assertTrue(r.conflicts.isEmpty())
        val b = nodeAt(r.output, pathFromString("/b"))
        assertTrue(b is CNode.CNull) // 自动采用 ours 的显式 null
    }

    @Test
    fun `数组 REPLACE 策略整体三方合并`() {
        val p = policies(ArrayPolicy("/list", ArrayStrategy.REPLACE))
        // 一侧修改 → 自动取修改侧
        val r1 = Merger(p).merge(y("list: [1, 2]", "base"), y("list: [1, 2, 3]", "ours"), y("list: [1, 2]", "theirs"))
        assertTrue(r1.conflicts.isEmpty())
        assertEquals(3, (nodeAt(r1.output, pathFromString("/list")) as CNode.CArray).items.size)
        // 两侧都改 → 冲突
        val r2 = Merger(p).merge(y("list: [1, 2]", "base"), y("list: [9]", "ours"), y("list: [8]", "theirs"))
        assertEquals("both-modified-array", r2.conflicts.single().reason)
    }

    @Test
    fun `数组 BY_ID 策略按稳定 id 合并`() {
        val p = policies(ArrayPolicy("/items", ArrayStrategy.BY_ID, "id"))
        val base = y("items:\n  - id: a\n    v: 1\n  - id: b\n    v: 2", "base")
        val ours = y("items:\n  - id: a\n    v: 10\n  - id: b\n    v: 2", "ours")   // 改 a
        val theirs = y("items:\n  - id: a\n    v: 1\n  - id: c\n    v: 3", "theirs") // 删 b 增 c
        val r = Merger(p).merge(base, ours, theirs)
        assertTrue(r.conflicts.isEmpty(), r.conflicts.toString())
        val items = (nodeAt(r.output, pathFromString("/items")) as CNode.CArray).items
        val ids = items.map { ((it as CNode.CObject).entries["id"] as CNode.CScalar).value }
        assertEquals(listOf("a", "c"), ids) // b 被 theirs 删除，c 追加
        val a = items[0] as CNode.CObject
        assertEquals(10L, (a.entries["v"] as CNode.CScalar).value)
    }

    @Test
    fun `重复 id 报冲突而不猜测`() {
        val p = policies(ArrayPolicy("/items", ArrayStrategy.BY_ID, "id"))
        val base = y("items:\n  - id: a\n    v: 1", "base")
        val ours = y("items:\n  - id: a\n    v: 1\n  - id: a\n    v: 2", "ours") // 重复 id
        val theirs = y("items:\n  - id: a\n    v: 1", "theirs")
        val r = Merger(p).merge(base, ours, theirs)
        assertEquals("duplicate-id", r.conflicts.single().reason)
    }

    @Test
    fun `未登记策略的数组不得猜测`() {
        val r = Merger(policies()).merge(
            y("list: [1, 2]", "base"), y("list: [1, 2, 3]", "ours"), y("list: [1, 4]", "theirs"))
        assertEquals("no-array-policy", r.conflicts.single().reason)
    }

    @Test
    fun `SEQUENCE 策略识别顺序移动`() {
        val p = policies(ArrayPolicy("/steps", ArrayStrategy.SEQUENCE))
        val base = y("steps: [a, b, c]", "base")
        val ours = y("steps: [b, a, c]", "ours")   // 纯重排
        val theirs = y("steps: [a, b, c]", "theirs")
        val r = Merger(p).merge(base, ours, theirs)
        assertTrue(r.conflicts.isEmpty())
        val items = (nodeAt(r.output, pathFromString("/steps")) as CNode.CArray).items
        assertEquals(listOf("b", "a", "c"), items.map { (it as CNode.CScalar).value })
    }

    @Test
    fun `SEQUENCE 策略两侧不同重排产生冲突`() {
        val p = policies(ArrayPolicy("/steps", ArrayStrategy.SEQUENCE))
        val r = Merger(p).merge(
            y("steps: [a, b, c]", "base"),
            y("steps: [b, a, c]", "ours"),
            y("steps: [c, b, a]", "theirs"))
        assertEquals("both-reordered", r.conflicts.single().reason)
    }

    @Test
    fun `BY_ID 顺序移动可自动合并`() {
        val p = policies(ArrayPolicy("/items", ArrayStrategy.BY_ID, "id"))
        val base = y("items:\n  - id: a\n  - id: b\n  - id: c", "base")
        val ours = y("items:\n  - id: c\n  - id: a\n  - id: b", "ours") // 移动 c 到最前
        val theirs = y("items:\n  - id: a\n  - id: b\n  - id: c", "theirs")
        val r = Merger(p).merge(base, ours, theirs)
        assertTrue(r.conflicts.isEmpty())
        val items = (nodeAt(r.output, pathFromString("/items")) as CNode.CArray).items
        assertEquals(listOf("c", "a", "b"),
            items.map { ((it as CNode.CObject).entries["id"] as CNode.CScalar).value })
    }

    @Test
    fun `别名展开参与比较且输出不共享引用`() {
        val text = """
        defaults: &d
          retries: 3
        a:
          <<: *d
          name: x
        b:
          <<: *d
          name: y
        """.trimIndent()
        val node = y(text, "src")
        val a = nodeAt(node, pathFromString("/a/retries"))
        val b = nodeAt(node, pathFromString("/b/retries"))
        assertEquals(3L, (a as CNode.CScalar).value)
        assertEquals(3L, (b as CNode.CScalar).value)
        // 展开副本带有 alias-expanded 溯源标记
        assertTrue(a.origins.any { it.note == "alias-expanded" })
        // 树中不存在共享引用
        val seen = IdentityHashMap<CNode, Boolean>()
        fun check(n: CNode) {
            assertFalse(seen.containsKey(n), "发现共享引用")
            seen[n] = true
            when (n) {
                is CNode.CObject -> n.entries.values.forEach(::check)
                is CNode.CArray -> n.items.forEach(::check)
                else -> {}
            }
        }
        check(node)
    }

    @Test
    fun `旧裁决在内容变化后仅作建议`() {
        val p = policies()
        val base = y("x: 1", "base"); val ours = y("x: 2", "ours"); val theirs = y("x: 3", "theirs")
        val r1 = Merger(p).merge(base, ours, theirs)
        val c1 = r1.conflicts.single()
        val decision = Decision("d-1", c1.path, c1.baseFp, c1.oursFp, c1.theirsFp, "ours")
        // 指纹一致 → 自动套用
        val r2 = Merger(p, listOf(decision)).merge(base, ours, theirs)
        assertTrue(r2.conflicts.isEmpty())
        assertEquals(2L, ((nodeAt(r2.output, pathFromString("/x"))) as CNode.CScalar).value)
        // ours 内容变化 → 旧裁决只作为建议，不自动套用
        val ours2 = y("x: 20", "ours")
        val r3 = Merger(p, listOf(decision)).merge(base, ours2, theirs)
        val c3 = r3.conflicts.single()
        assertNotNull(c3.suggestion)
        assertNull(c3.appliedDecisionId)
    }

    @Test
    fun `并发解决冲突使用乐观版本`(@TempDir dir: Path) {
        val store = Store(dir)
        val s = store.createSession("t", SessionInput(Format.YAML,
            "x: 1\ny: 1", "x: 2\ny: 2", "x: 3\ny: 3"), policies())
        assertEquals(2, s.result!!.conflicts.size)
        // 第一个客户端成功
        val ok = store.resolve(s.id, 0, listOf(Resolution("/x", "ours")))
        assertNotNull(ok)
        assertEquals(1, ok!!.version)
        // 过期页面（仍持有版本 0）提交被拒绝，不能覆盖已解决的项
        val stale = store.resolve(s.id, 0, listOf(Resolution("/x", "theirs")))
        assertNull(stale)
        val x = nodeAt(store.get(s.id)!!.result!!.output, pathFromString("/x"))
        assertEquals(2L, (x as CNode.CScalar).value)
        // 用新版本可继续解决剩余冲突
        val ok2 = store.resolve(s.id, 1, listOf(Resolution("/y", "theirs")))
        assertNotNull(ok2)
        assertEquals(0, ok2!!.result!!.conflicts.size)
    }

    @Test
    fun `稳定序列化与导出导入往返一致`(@TempDir dir: Path) {
        val store = Store(dir)
        val p = PolicySet("v1", mapOf("/items" to ArrayPolicy("/items", ArrayStrategy.BY_ID, "id")))
        val s = store.createSession("rt", SessionInput(Format.YAML,
            "name: svc\nitems:\n  - id: a\n    v: 1\n  - id: b\n    v: 2",
            "name: svc\nitems:\n  - id: a\n    v: 9\n  - id: b\n    v: 2",
            "name: svc2\nitems:\n  - id: a\n    v: 1\n  - id: b\n    v: 2\n  - id: c\n    v: 3"), p)
        val b1 = store.export(s, Format.YAML)
        val b2 = store.export(s, Format.YAML)
        assertEquals(b1.text, b2.text)                       // 稳定序列化
        assertEquals(b1.resultFingerprint, b2.resultFingerprint)
        val check = store.verifyImport(b1)                   // 导出再导入
        assertEquals(true, check["ok"], check.toString())
        assertEquals(b1.resultFingerprint, check["resultFingerprint"])
        // JSON 导出同样可往返
        val bj = store.export(s, Format.JSON)
        assertEquals(true, store.verifyImport(bj)["ok"])
        // 来源链在清单中完整保留
        assertTrue(b1.manifest.isNotEmpty())
        assertTrue(b1.manifest.all { it.origins.isNotEmpty() || it.path == "/" })
    }

    @Test
    fun `持久化后重载会话与裁决`(@TempDir dir: Path) {
        val store = Store(dir)
        val s = store.createSession("persist", SessionInput(Format.YAML,
            "x: 1", "x: 2", "x: 3"), policies())
        store.resolve(s.id, 0, listOf(Resolution("/x", "theirs")))
        val store2 = Store(dir) // 模拟重启
        val s2 = store2.get(s.id)!!
        assertEquals(1, s2.version)
        assertEquals(1, s2.decisions.size)
        assertEquals(0, s2.result!!.conflicts.size)
        val x = nodeAt(s2.result!!.output, pathFromString("/x"))
        assertEquals(3L, (x as CNode.CScalar).value)
        assertEquals("v1", s2.policySet.version)
    }

    @Test
    fun `JSON 输入与 YAML 输入指纹一致`() {
        val yNode = y("a: 1\nb:\n  - x\n  - y", "yaml")
        val jNode = parse("""{"a":1,"b":["x","y"]}""", Format.JSON, "json")
        assertEquals(fingerprint(yNode), fingerprint(jNode))
    }
}
