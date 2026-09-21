package merger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergerTest {

    private fun svc(): MergeService =
        MergeService(Store(Files.createTempDirectory("merge-test").toFile()))

    private fun MergeService.doc(text: String, format: String = "yaml", name: String = "d"): Document =
        addDocument(name, format, text)

    private fun MergeService.mergeOf(base: String, ours: String, theirs: String,
                                     policies: Map<String, ArrayPolicy> = emptyMap()): MergeSession {
        putPolicies(policies)
        val b = doc(base); val o = doc(ours); val t = doc(theirs)
        return createMerge(b.id, o.id, t.id)
    }

    private fun mapAt(n: Node?, key: String): Node? = (n as? MapNode)?.entries?.get(key)

    @Test
    fun `删除与设为 null 是不同动作`() {
        val s = svc()
        // 祖先有 a；A 删除 a；B 把 a 设为 null → 冲突而非自动合并
        val m = s.mergeOf(
            "{a: 1, keep: 1}", "{keep: 1}", "{a: null, keep: 1}")
        val c = m.conflicts.single()
        assertEquals("/a", c.path)
        assertEquals("delete-vs-modify", c.reason)
        // 单边设为 null → 自动合并为 null（字段仍存在，不是删除）
        val m2 = s.mergeOf("{a: 1}", "{a: null}", "{a: 1}")
        assertTrue(m2.conflicts.isEmpty())
        val a = mapAt(m2.resultTree, "a")
        assertTrue(a is NullNode)
        // 单边删除 → 字段消失
        val m3 = s.mergeOf("{a: 1}", "{}", "{a: 1}")
        assertTrue(m3.conflicts.isEmpty())
        assertNull(mapAt(m3.resultTree, "a"))
        assertFalse(structEq(NullNode(), null))
    }

    @Test
    fun `数组策略-替换`() {
        val s = svc()
        val pol = mapOf("/list" to ArrayPolicy(ArrayStrategy.REPLACE))
        // 仅 A 改 → 自动取 A
        val m = s.mergeOf("{list: [1, 2]}", "{list: [1, 2, 3]}", "{list: [1, 2]}", pol)
        assertTrue(m.conflicts.isEmpty())
        assertEquals(3, (mapAt(m.resultTree, "list") as SeqNode).items.size)
        // 两边都改 → 冲突
        val m2 = s.mergeOf("{list: [1, 2]}", "{list: [1, 2, 3]}", "{list: [9]}", pol)
        assertEquals("array-replace-both-changed", m2.conflicts.single().reason)
    }

    @Test
    fun `数组策略-按稳定 id 合并`() {
        val s = svc()
        val pol = mapOf("/features" to ArrayPolicy(ArrayStrategy.BY_ID, "id"))
        val m = s.mergeOf(
            "features: [{id: a, v: 1}, {id: b, v: 2}]",
            "features: [{id: a, v: 10}, {id: b, v: 2}]",
            "features: [{id: a, v: 1}, {id: b, v: 20}, {id: c, v: 30}]",
            pol)
        assertTrue(m.conflicts.isEmpty())
        val items = (mapAt(m.resultTree, "features") as SeqNode).items
        assertEquals(listOf("a", "b", "c"), items.map { (mapAt(it, "id") as ScalarNode).value })
        assertEquals("10", (mapAt(items[0], "v") as ScalarNode).value)
        assertEquals("20", (mapAt(items[1], "v") as ScalarNode).value)
    }

    @Test
    fun `数组策略-重复 id 报冲突`() {
        val s = svc()
        val pol = mapOf("/features" to ArrayPolicy(ArrayStrategy.BY_ID, "id"))
        val m = s.mergeOf(
            "features: [{id: a}]",
            "features: [{id: a}, {id: a}]",
            "features: [{id: a}, {id: b}]",
            pol)
        assertTrue(m.conflicts.any { it.reason.startsWith("array-by-id-duplicate-id") })
    }

    @Test
    fun `按 id 合并数组内的冲突可按 id 路径裁决`() {
        val s = svc()
        val pol = mapOf("/features" to ArrayPolicy(ArrayStrategy.BY_ID, "id"))
        val m = s.mergeOf(
            "features: [{id: a, v: 1}]",
            "features: [{id: a, v: 2}]",
            "features: [{id: a, v: 3}]",
            pol)
        val c = m.conflicts.single()
        assertEquals("/features/a/v", c.path)
        val done = s.resolve(m.id, m.version, listOf(AppliedResolution(c.id, c.path, "theirs", null)))
        assertTrue(done.conflicts.all { it.status == "resolved" })
        val items = (mapAt(done.resultTree, "features") as SeqNode).items
        assertEquals("3", (mapAt(items[0], "v") as ScalarNode).value)
    }

    @Test
    fun `数组策略-有序序列与顺序移动`() {
        val s = svc()
        val pol = mapOf("/list" to ArrayPolicy(ArrayStrategy.ORDERED))
        // 仅 A 移动顺序 → 自动采用 A 的顺序
        val m = s.mergeOf("{list: [a, b, c]}", "{list: [c, a, b]}", "{list: [a, b, c]}", pol)
        assertTrue(m.conflicts.isEmpty())
        assertEquals(listOf("c", "a", "b"),
            (mapAt(m.resultTree, "list") as SeqNode).items.map { (it as ScalarNode).value })
        // 等长时两边各改不同位置 → 按位合并
        val m2 = s.mergeOf("{list: [1, 2, 3]}", "{list: [10, 2, 3]}", "{list: [1, 20, 3]}", pol)
        assertTrue(m2.conflicts.isEmpty())
        assertEquals(listOf("10", "20", "3"),
            (mapAt(m2.resultTree, "list") as SeqNode).items.map { (it as ScalarNode).value })
        // 长度变化冲突
        val m3 = s.mergeOf("{list: [1, 2]}", "{list: [1, 2, 3]}", "{list: [1]}", pol)
        assertEquals("array-ordered-length-changed", m3.conflicts.single().reason)
    }

    @Test
    fun `未登记策略时不得猜测`() {
        val s = svc()
        val m = s.mergeOf("{list: [1, 2]}", "{list: [1, 2, 3]}", "{list: [1, 5]}")
        assertEquals("array-no-policy", m.conflicts.single().reason)
    }

    @Test
    fun `锚点别名展开且不共享可变引用`() {
        val s = svc()
        val d = s.doc("""
defaults: &d
  retries: 3
prod: *d
dev: *d
""".trimIndent())
        val root = parseDocument(DocFormat.YAML, d.text, d.id) as MapNode
        val prod = root.entries["prod"]!!
        val dev = root.entries["dev"]!!
        assertTrue(structEq(prod, dev))
        assertNotSame(prod, dev)
        assertNotSame((prod as MapNode).entries["retries"], (dev as MapNode).entries["retries"])
        // 修改一处不影响另一处
        (prod.entries["retries"] as ScalarNode).let { }
        (prod as MapNode).entries["retries"] = ScalarNode("9", ScalarKind.NUMBER)
        assertEquals("3", ((dev as MapNode).entries["retries"] as ScalarNode).value)
    }

    @Test
    fun `旧裁决在内容变化后仅作建议`() {
        val s = svc()
        // 产生冲突并裁决
        val m1 = s.mergeOf("{a: 1}", "{a: 2}", "{a: 3}")
        val c1 = m1.conflicts.single()
        s.resolve(m1.id, m1.version, listOf(AppliedResolution(c1.id, c1.path, "ours", null)))
        assertEquals(1, s.decisions.size)
        // 相同指纹再次合并 → 裁决自动套用
        val m2 = s.mergeOf("{a: 1}", "{a: 2}", "{a: 3}")
        assertTrue(m2.conflicts.single().status == "auto-decision")
        assertEquals("2", (mapAt(m2.resultTree, "a") as ScalarNode).value)
        // B 侧内容变化 → 旧裁决不自动套用，仅作建议
        val m3 = s.mergeOf("{a: 1}", "{a: 2}", "{a: 4}")
        val c3 = m3.conflicts.single()
        assertEquals("open", c3.status)
        assertEquals(1, m3.suggestions.size)
        assertEquals("2", (mapAt(m3.resultTree, "a") as ScalarNode).value) // 暂定 A 值，等待裁决
    }

    @Test
    fun `并发解决冲突使用乐观版本`() {
        val s = svc()
        val m = s.mergeOf("{a: 1, b: 1}", "{a: 2, b: 2}", "{a: 3, b: 3}")
        assertEquals(2, m.conflicts.size)
        val (c1, c2) = m.conflicts
        // 第一个提交成功，版本前进
        val after = s.resolve(m.id, 0, listOf(AppliedResolution(c1.id, c1.path, "ours", null)))
        assertEquals(1, after.version)
        // 过期页面用旧版本提交 → 拒绝
        val ex = assertThrows<VersionConflictException> {
            s.resolve(m.id, 0, listOf(AppliedResolution(c2.id, c2.path, "theirs", null)))
        }
        assertEquals(1, ex.currentVersion)
        // 用新版本可继续
        val done = s.resolve(m.id, 1, listOf(AppliedResolution(c2.id, c2.path, "theirs", null)))
        assertTrue(done.conflicts.all { it.status == "resolved" })
    }

    @Test
    fun `稳定序列化与导出导入往返一致`() {
        val s = svc()
        val pol = mapOf("/features" to ArrayPolicy(ArrayStrategy.BY_ID, "id"))
        s.putPolicies(pol)
        val b = s.doc("features: [{id: a, v: 1}]\nlimits: {cpu: 2}")
        val o = s.doc("features: [{id: a, v: 2}]\nlimits: {cpu: 2}")
        val t = s.doc("features: [{id: a, v: 1}, {id: b, v: 9}]\nlimits: {cpu: 4}")
        val m = s.createMerge(b.id, o.id, t.id)
        assertTrue(m.conflicts.isEmpty())
        val fp1 = fingerprint(m.resultTree)
        // 导出 YAML 再解析 → 指纹一致（稳定序列化）
        val exp = s.export(m.id, "yaml")
        assertEquals(fp1, exp.fingerprint)
        val reparsed = parseDocument(DocFormat.YAML, exp.text, "reimport")
        assertEquals(fp1, fingerprint(reparsed))
        // 导出 JSON 同样稳定
        val expJ = s.export(m.id, "json")
        assertEquals(fp1, fingerprint(parseDocument(DocFormat.JSON, expJ.text, "reimport2")))
        // 包导出 → 导入新仓库 → 重新合并后路径、来源链、指纹一致
        val bundle = s.exportBundle()
        val s2 = svc()
        s2.importBundle(bundle)
        val m2 = s2.createMerge(b.id, o.id, t.id)
        assertEquals(fp1, fingerprint(m2.resultTree))
        assertEquals(
            m.items.map { it.path to it.action },
            m2.items.map { it.path to it.action })
        fun chains(n: Node?, path: String = ""): List<Pair<String, List<String>>> {
            if (n == null) return emptyList()
            val self = listOf(path to n.sourceChain.map { it.toString() })
            return when (n) {
                is MapNode -> self + n.entries.flatMap { (k, v) -> chains(v, childPath(path, k)) }
                is SeqNode -> self + n.items.flatMapIndexed { i, v -> chains(v, "$path/$i") }
                else -> self
            }
        }
        assertEquals(chains(m.resultTree), chains(m2.resultTree))
    }

    @Test
    fun `JSON 与 YAML 输入可互相合并`() {
        val s = svc()
        val b = s.doc("""{"a": 1, "nested": {"x": 1}}""", "json")
        val o = s.doc("a: 1\nnested:\n  x: 2")
        val t = s.doc("""{"a": 5, "nested": {"x": 1}}""", "json")
        val m = s.createMerge(b.id, o.id, t.id)
        assertTrue(m.conflicts.isEmpty())
        assertEquals("5", (mapAt(m.resultTree, "a") as ScalarNode).value)
        assertEquals("2", (mapAt(mapAt(m.resultTree, "nested"), "x") as ScalarNode).value)
        // 来源链记录贡献者
        val a = mapAt(m.resultTree, "a")!!
        assertTrue(a.sourceChain.any { it.docId == t.id })
    }
}
