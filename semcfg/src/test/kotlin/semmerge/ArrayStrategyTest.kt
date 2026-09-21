package semmerge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import semmerge.merge.ConflictKind
import semmerge.merge.ListStrategy
import semmerge.merge.MList
import semmerge.merge.MMap
import semmerge.merge.MergeStatus
import semmerge.merge.OutputBuilder
import semmerge.merge.PolicyRegistry
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SScalar

class ArrayStrategyTest {

    private fun registry(path: String, strategy: ListStrategy, idField: String = "id"): PolicyRegistry {
        val (reg, _) = PolicyRegistry.EMPTY
            .with(PathRendererTest.parse(path), strategy, idField)
        return reg
    }

    @Test
    fun `unregistered array strategy is a needs-policy conflict, never guessed`() {
        val b = MergeTestSupport.yaml("items: [1, 2]\n")
        val a = MergeTestSupport.yaml("items: [1, 2, 3]\n")
        val c = MergeTestSupport.yaml("items: [0, 1, 2]\n")
        val result = MergeTestSupport.merge(b, a, c)
        assertEquals(1, result.conflicts.size)
        assertEquals(ConflictKind.POLICY_MISSING, result.conflicts.single().kind)
        val list = (result.tree as MMap).entries.single { it.key == "items" }.node as MList
        assertEquals(MergeStatus.NEEDS_POLICY, list.status)
        assertNull(list.strategy)
    }

    @Test
    fun `replace strategy takes the single side change and conflicts on divergence`() {
        val b = MergeTestSupport.yaml("items: [1, 2]\n")
        val a = MergeTestSupport.yaml("items: [1, 2, 3]\n")
        val c = MergeTestSupport.yaml("items: [1, 2]\n")
        val result = MergeTestSupport.merge(b, a, c, registry("\$.items", ListStrategy.REPLACE))
        val out = OutputBuilder.build(result) as SMap
        assertEquals(listOf("1", "2", "3"),
            (out.get("items") as SList).items.map { (it as SScalar).text })

        val c2 = MergeTestSupport.yaml("items: [9]\n")
        val conflicted = MergeTestSupport.merge(b, a, c2, registry("\$.items", ListStrategy.REPLACE))
        assertEquals(1, conflicted.conflicts.size)
        assertEquals(ConflictKind.VALUE, conflicted.conflicts.single().kind)
    }

    @Test
    fun `id merge keeps per-element edits and inserts new elements`() {
        val b = MergeTestSupport.yaml("""
            svcs:
              - id: web
                port: 80
              - id: db
                port: 5432
        """)
        val a = MergeTestSupport.yaml("""
            svcs:
              - id: web
                port: 8080
              - id: db
                port: 5432
              - id: cache
                port: 6379
        """)
        val c = MergeTestSupport.yaml("""
            svcs:
              - id: web
                port: 80
              - id: db
                port: 5433
        """)
        val result = MergeTestSupport.merge(b, a, c, registry("\$.svcs", ListStrategy.ID))
        assertTrue(result.conflicts.isEmpty(), result.conflicts.toString())
        val out = OutputBuilder.build(result) as SMap
        val svcs = (out as SMap).get("svcs") as SList
        assertEquals(3, svcs.items.size)
        val byId = svcs.items.associateBy { ((it as SMap).get("id") as SScalar).text }
        assertEquals("8080", ((byId["web"] as SMap).get("port") as SScalar).text)
        assertEquals("5433", ((byId["db"] as SMap).get("port") as SScalar).text)
        assertNotNull(byId["cache"])
    }

    @Test
    fun `duplicate stable id is a conflict`() {
        val b = MergeTestSupport.yaml("svcs: [{id: x}]\n")
        val a = MergeTestSupport.yaml("svcs: [{id: x}, {id: x}]\n")
        val c = MergeTestSupport.yaml("svcs: [{id: x}]\n")
        val result = MergeTestSupport.merge(b, a, c, registry("\$.svcs", ListStrategy.ID))
        assertTrue(result.conflicts.any { it.kind == ConflictKind.DUPLICATE_ID })
        assertEquals("x", result.conflicts.single { it.kind == ConflictKind.DUPLICATE_ID }.duplicateId)
    }

    @Test
    fun `element missing id field is a missing-id conflict`() {
        val b = MergeTestSupport.yaml("svcs: [{name: noid}]\n")
        val a = MergeTestSupport.yaml("svcs: [{name: noid}, {name: two}]\n")
        val c = MergeTestSupport.yaml("svcs: [{name: noid}]\n")
        val result = MergeTestSupport.merge(b, a, c, registry("\$.svcs", ListStrategy.ID))
        assertTrue(result.conflicts.any { it.kind == ConflictKind.MISSING_ID })
    }

    @Test
    fun `one-sided reorder merges automatically, both-side reorders conflict`() {
        val b = MergeTestSupport.yaml("items: [{id: 1}, {id: 2}, {id: 3}]\n")
        val aReordered = MergeTestSupport.yaml("items: [{id: 3}, {id: 1}, {id: 2}]\n")
        val cSame = MergeTestSupport.yaml("items: [{id: 1}, {id: 2}, {id: 3}]\n")
        val auto = MergeTestSupport.merge(b, aReordered, cSame, registry("\$.items", ListStrategy.ID))
        assertTrue(auto.conflicts.isEmpty(), auto.conflicts.toString())
        val autoOut = ((OutputBuilder.build(auto) as SMap).get("items") as SList)
        assertEquals(listOf("3", "1", "2"), autoOut.items.map { ((it as SMap).get("id") as SScalar).text })

        val cReordered = MergeTestSupport.yaml("items: [{id: 2}, {id: 3}, {id: 1}]\n")
        val conflicted = MergeTestSupport.merge(b, aReordered, cReordered, registry("\$.items", ListStrategy.ID))
        assertTrue(conflicted.conflicts.any { it.kind == ConflictKind.ORDER })
    }

    @Test
    fun `ordered sequence merges non-overlapping edits and conflicts on overlap`() {
        val b = MergeTestSupport.yaml("lines: [a, b, c, d]\n")
        val a = MergeTestSupport.yaml("lines: [a, b, c, d, E]\n")
        val c = MergeTestSupport.yaml("lines: [a, B2, c, d]\n")
        val result = MergeTestSupport.merge(b, a, c, registry("\$.lines", ListStrategy.SEQ))
        assertTrue(result.conflicts.isEmpty(), result.conflicts.toString())
        val out = ((OutputBuilder.build(result) as SMap).get("lines") as SList)
        assertEquals(listOf("a", "B2", "c", "d", "E"), out.items.map { (it as SScalar).text })

        val a2 = MergeTestSupport.yaml("lines: [a, X, c, d]\n")
        val c2 = MergeTestSupport.yaml("lines: [a, Y, c, d]\n")
        val conflicted = MergeTestSupport.merge(b, a2, c2, registry("\$.lines", ListStrategy.SEQ))
        assertTrue(conflicted.conflicts.any { it.kind == ConflictKind.SEQ })
    }
}
