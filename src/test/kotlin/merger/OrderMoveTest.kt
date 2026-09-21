package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OrderMoveTest {

    private fun seq(vararg ids: Int): String =
        "items:\n" + ids.joinToString("\n") { "  - {id: $it, v: $it}\n" }

    private fun registry() = StrategyRegistry.EMPTY.with(Path.parse("\$.items"), ArrayStrategy.ID)

    @Test
    fun `moving an item on one side merges when other side keeps order`() {
        val base = seq(1, 2, 3)
        val aMoved = seq(2, 1, 3)
        val bSame = seq(1, 2, 3, 4) // B 只追加 4，未移动既有顺序
        val o = Merger.merge(TestSupport.docs(base, aMoved, bSame).copy(registry = registry()))
        assertEquals(0, o.unresolvedCount, "单边移动+另一边追加应自动合并")
        val items = ((o.materialized as SMap)["items"] as SSeq)
        val order = items.items.map { Canonical.normalizeNumber(((it as SMap)["id"] as SSNumber).value) }
        assertEquals(listOf("2", "1", "3", "4"), order)
    }

    @Test
    fun `moving same item differently on two sides is ARRAY_ORDER conflict`() {
        val base = seq(1, 2, 3)
        val a = seq(2, 3, 1)
        val b = seq(3, 1, 2)
        val o = Merger.merge(TestSupport.docs(base, a, b).copy(registry = registry()))
        assertTrue(o.unresolvedCount >= 1)
        val orderNode = o.conflictIndex[".items[order]".replace(".items", "\$.items")]
        assertNotNull(orderNode, "应产生顺序冲突节点")
        assertEquals(ConflictKind.ARRAY_ORDER, orderNode!!.conflict!!.kind)
        // 结果在裁决前不可物化
        assertNull((o.materialized as SMap).entries["items"])
    }

    @Test
    fun `resolving order conflict chooses one side ordering`() {
        val base = seq(1, 2, 3)
        val a = seq(2, 1, 3)
        val b = seq(1, 3, 2)
        var input = TestSupport.docs(base, a, b).copy(registry = registry())
        var o = Merger.merge(input)
        val orderPath = "\$.items[order]"
        val node = o.conflictIndex[orderPath]!!
        val dec = Decision(
            id = "d1", path = orderPath, choiceId = "A",
            baseFingerprint = input.base.fingerprint,
            aFingerprint = input.a.fingerprint,
            bFingerprint = input.b.fingerprint,
            conflictKind = ConflictKind.ARRAY_ORDER,
            createdAt = java.time.Instant.now(), baseVersion = 0
        )
        input = input.copy(decisions = listOf(dec))
        o = Merger.merge(input)
        assertEquals(0, o.unresolvedCount)
        val order = ((o.materialized as SMap)["items"] as SSeq).items.map {
            Canonical.normalizeNumber(((it as SMap)["id"] as SSNumber).value)
        }
        assertEquals(listOf("2", "1", "3"), order)
    }
}
