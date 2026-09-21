package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArrayStrategyTest {

    private fun reg(vararg pairs: Pair<String, ArrayStrategy>): StrategyRegistry {
        var r = StrategyRegistry.EMPTY
        pairs.forEach { (p, st) -> r = r.with(Path.parse(p), st) }
        return r
    }

    @Test
    fun `unregistered array path blocks and must not guess`() {
        val base = "list:\n  - 1\n  - 2\n"
        val a = "list:\n  - 1\n  - 2\n  - 3\n"
        val b = "list:\n  - 0\n  - 1\n  - 2\n"
        val o = Merger.merge(TestSupport.docs(base, a, b))
        val node = o.conflictIndex["\$.list"]
        assertNotNull(node)
        assertEquals(ConflictKind.NO_STRATEGY, node!!.conflict!!.kind)
        assertEquals(MergeStatus.BLOCKED, node.status)
    }

    @Test
    fun `replace strategy chooses whole side and auto-merges single-side edit`() {
        val base = "servers:\n  - a\n  - b\n"
        val a = "servers:\n  - x\n  - y\n"
        val b = "servers:\n  - a\n  - b\n  - c\n"
        val input = TestSupport.docs(base, a, b).copy(
            registry = reg("\$.servers" to ArrayStrategy.REPLACE))
        // 两边都改 -> VALUE 冲突
        var o = Merger.merge(input)
        assertTrue(o.conflictIndex.containsKey("\$.servers"))
        // 只有一边改：无需冲突，直接采用改动方（即使路径登记了 REPLACE）
        val one = TestSupport.docs(base, base, b).copy(
            registry = reg("\$.servers" to ArrayStrategy.REPLACE))
        o = Merger.merge(one)
        assertEquals(0, o.unresolvedCount)
        assertEquals(listOf("a", "b", "c"),
            ((o.materialized as SMap)["servers"] as SSeq).items.map { (it as SSString).value })
    }

    @Test
    fun `id strategy merges by stable id and deletes per side`() {
        val base = """
            servers:
              - {id: web, port: 80}
              - {id: db,  port: 5432}
        """.trimIndent()
        val a = """
            servers:
              - {id: web, port: 8080}
              - {id: cache, port: 6379}
        """.trimIndent()
        val b = """
            servers:
              - {id: web, port: 80}
              - {id: db, port: 5433}
        """.trimIndent()
        val input = TestSupport.docs(base, a, b).copy(
            registry = reg("\$.servers" to ArrayStrategy.ID))
        val o = Merger.merge(input)
        assertEquals(emptySet<String>(), TestSupport.conflictPaths(o),
            "A 删 db 加 cache 改 web；B 改 db 端口：应全部自动合并")
        val servers = ((o.materialized as SMap)["servers"] as SSeq).items.map { it as SMap }
        val byId = servers.associateBy { (it["id"] as SSString).value }
        assertEquals(3, byId.size)
        assertEquals(8080, (byId["web"]!!["port"] as SSNumber).value.toInt())
        assertEquals(5433, (byId["db"]!!["port"] as SSNumber).value.toInt())
        assertTrue(byId.containsKey("cache"))
    }

    @Test
    fun `sequence strategy diff3 merges independent edits and conflicts on same region`() {
        val base = "items:\n  - a\n  - b\n  - c\n"
        // A 在尾部追加；B 在头部插入 -> 不同区域，自动合并
        val aAdd = "items:\n  - a\n  - b\n  - c\n  - d\n"
        val bPre = "items:\n  - z\n  - a\n  - b\n  - c\n"
        val input = TestSupport.docs(base, aAdd, bPre).copy(
            registry = reg("\$.items" to ArrayStrategy.SEQUENCE))
        var o = Merger.merge(input)
        assertEquals(0, o.unresolvedCount)
        val merged = ((o.materialized as SMap)["items"] as SSeq).items.map { (it as SSString).value }
        assertEquals(listOf("z", "a", "b", "c", "d"), merged)

        // 同一区域两边都改成不同内容 -> SEQUENCE_REGION 冲突
        val a2 = "items:\n  - a\n  - B1\n  - c\n"
        val b2 = "items:\n  - a\n  - B2\n  - c\n"
        o = Merger.merge(TestSupport.docs(base, a2, b2).copy(
            registry = reg("\$.items" to ArrayStrategy.SEQUENCE)))
        assertTrue(TestSupport.conflictPaths(o).any { it.contains("region") })
    }

    @Test
    fun `duplicate ids on one side raise DUP_ID conflict`() {
        val base = "xs:\n  - {id: 1, v: a}\n"
        val a = "xs:\n  - {id: 1, v: a}\n  - {id: 1, v: dup}\n  - {id: 2, v: c}\n"
        val b = "xs:\n  - {id: 1, v: a}\n  - {id: 2, v: d}\n"
        val o = Merger.merge(TestSupport.docs(base, a, b).copy(
            registry = reg("\$.xs" to ArrayStrategy.ID)))
        assertNotNull(o.conflictIndex["\$.xs"])
        assertEquals(ConflictKind.DUP_ID, o.conflictIndex["\$.xs"]!!.conflict!!.kind)
    }
}
