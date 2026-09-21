package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DecisionLifecycleTest {

    @TempDir lateinit var dir: Path

    private fun svc() = TestSupport.service(dir)

    private val base = "name: svc\nport: 80\n"
    private val a1 = "name: svc\nport: 8080\n"
    private val b1 = "name: svc\nport: 9090\n"

    @Test
    fun `decision binds fingerprints and becomes advisory after an input changes`() {
        val svc = svc()
        val s = svc.create("t")
        TestSupport.putAll(svc, s.id, base, a1, b1)
        var o = svc.remerge(s)
        assertEquals(1, o.unresolvedCount)
        val path = "\$.port"

        svc.submitDecisions(s.id, SubmitRequest(s.version, "alice",
            listOf(SubmitItem(path, "A", null, null))))
        svc.get(s.id).let {
            val oc = svc.remerge(it)
            assertEquals(0, oc.unresolvedCount)
            assertEquals("8080", Canonical.normalizeNumber((oc.materialized as SMap).let { m -> (m["port"] as SSNumber).value }))
        }

        // B 分支内容变化 -> 旧裁决指纹不再匹配，只作为建议
        val changed = svc.setInput(s.id, Source.B, "name: svc\nport: 9091\n", Format.YAML)
        o = svc.remerge(changed)
        val node = o.conflictIndex[path]
        assertNotNull(node, "重新出现冲突")
        assertNull(node!!.resolvedBy, "指纹不匹配时旧裁决不得自动套用")
        assertNotNull(node.advisory, "旧裁决应作为建议展示")
        assertEquals("A", node.advisory!!.suggestedChoice)
    }

    @Test
    fun `concurrent batch with optimistic version is rejected and cannot overwrite`() {
        val svc = svc()
        val s = svc.create("t")
        TestSupport.putAll(svc, s.id, base, a1, b1)
        svc.remerge(s)
        val staleVersion = s.version

        // 第一份提交成功
        val r1 = svc.submitDecisions(s.id, SubmitRequest(staleVersion, "alice",
            listOf(SubmitItem("\$.port", "A", null, null))))
        assertEquals(1, r1.newVersion - staleVersion)

        // 第二份基于过期版本 -> 整批拒绝（409 语义）
        val ex = assertThrows(ConflictBusyException::class.java) {
            svc.submitDecisions(s.id, SubmitRequest(staleVersion, "bob",
                listOf(SubmitItem("\$.port", "B", null, null))))
        }
        assertTrue(ex.message!!.contains("过期"))

        // 即使用最新版本，已解决项也不能被后到者覆盖
        val current = svc.get(s.id)
        val ex2 = assertThrows(ConflictBusyException::class.java) {
            svc.submitDecisions(current.id, SubmitRequest(current.version, "bob",
                listOf(SubmitItem("\$.port", "B", null, null))))
        }
        assertTrue(ex2.message!!.contains("已变化"))
        val final = svc.remerge(svc.get(s.id))
        // 仍然是 alice 的 A 结果
        assertEquals("8080", Canonical.normalizeNumber(((final.materialized as SMap)["port"] as SSNumber).value))
    }

    @Test
    fun `multiple conflicts in one batch apply atomically with optimistic version`() {
        val svc = svc()
        val s = svc.create("t")
        val b = "x: 1\ny: 2\n"
        val a = "x: 10\ny: 20\n"
        val bb = "x: 100\ny: 200\n"
        TestSupport.putAll(svc, s.id, b, a, bb)
        val o = svc.remerge(s)
        assertEquals(2, o.unresolvedCount)
        val r = svc.submitDecisions(s.id, SubmitRequest(s.version, "batch", listOf(
            SubmitItem("\$.x", "A", null, null),
            SubmitItem("\$.y", "B", null, null)
        )))
        assertTrue(r.accepted.size == 2)
        val after = svc.remerge(svc.get(s.id))
        val m = after.materialized as SMap
        assertEquals("10", Canonical.normalizeNumber((m["x"] as SSNumber).value))
        assertEquals("200", Canonical.normalizeNumber((m["y"] as SSNumber).value))
    }

    @Test
    fun `custom decision text is parsed and bound`() {
        val svc = svc()
        val s = svc.create("t")
        TestSupport.putAll(svc, s.id, base, a1, b1)
        svc.remerge(s)
        svc.submitDecisions(s.id, SubmitRequest(s.version, "me",
            listOf(SubmitItem("\$.port", "CUSTOM", "6500", Format.YAML))))
        val m = (svc.remerge(svc.get(s.id)).materialized as SMap)
        assertEquals("6500", Canonical.normalizeNumber((m["port"] as SSNumber).value))
    }
}
