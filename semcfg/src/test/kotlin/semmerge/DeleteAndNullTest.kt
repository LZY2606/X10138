package semmerge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import semmerge.merge.ConflictKind
import semmerge.merge.MDeleted
import semmerge.merge.MMap
import semmerge.merge.MValue
import semmerge.merge.MergeStatus
import semmerge.merge.OutputBuilder
import semmerge.model.SMap
import semmerge.model.SScalar
import semmerge.model.ScalarKind

class DeleteAndNullTest {

    private val base = MergeTestSupport.yaml("x: keep\nremoveMe: v1\nnullMe: v2\n")
    private val branchA = MergeTestSupport.yaml("x: keep\nnullMe: null\n")
    private val branchB = MergeTestSupport.yaml("x: changed\nremoveMe: v1\nnullMe: v2\n")

    @Test
    fun `delete explicit-null and missing stay distinct`() {
        val result = MergeTestSupport.merge(base, branchA, branchB)
        val root = result.tree as MMap
        val byKey = root.entries.associateBy { it.key }

        // removeMe: A deleted, B untouched -> auto deleted, key omitted from output
        assertTrue(byKey["removeMe"]!!.node is MDeleted)
        val output = OutputBuilder.build(result) as SMap
        assertFalse(output.keys.contains("removeMe"))

        // nullMe: A set explicit null -> MValue with NULL, key present in output
        val nullNode = byKey["nullMe"]!!.node as MValue
        assertEquals(ScalarKind.NULL, (nullNode.value as SScalar).kind)
        assertTrue(output.keys.contains("nullMe"))
        assertEquals(ScalarKind.NULL, (output.get("nullMe") as SScalar).kind)

        // x: B changed, A untouched -> auto B
        assertEquals(MergeStatus.AUTO_B, byKey["x"]!!.node.status)
    }

    @Test
    fun `delete versus edit is a conflict, null versus edit is a value conflict`() {
        val b = MergeTestSupport.yaml("d: 1\nn: 1\n")
        val a = MergeTestSupport.yaml("n: null\n")
        val c = MergeTestSupport.yaml("d: 2\nn: 2\n")
        val result = MergeTestSupport.merge(b, a, c)
        val kinds = result.conflicts.map { it.kind }.toSet()
        assertTrue(ConflictKind.DELETE_EDIT in kinds)
        assertTrue(ConflictKind.VALUE in kinds)
        assertEquals(2, result.conflicts.size)
    }

    @Test
    fun `both sides deleting is an automatic delete`() {
        val b = MergeTestSupport.yaml("x: 1\ny: 2\n")
        val a = MergeTestSupport.yaml("y: 2\n")
        val c = MergeTestSupport.yaml("y: 2\n")
        val result = MergeTestSupport.merge(b, a, c)
        val output = OutputBuilder.build(result) as SMap
        assertEquals(listOf("y"), output.keys)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `resolution delete versus set null produces different outputs`() {
        val b = MergeTestSupport.yaml("k: 1\n")
        val a = MergeTestSupport.yaml("k: 2\n")
        val c = MergeTestSupport.yaml("k: 3\n")
        val fps = semmerge.iof.TripleFingerprint(
            semmerge.iof.Fingerprint.of(b),
            semmerge.iof.Fingerprint.of(a),
            semmerge.iof.Fingerprint.of(c),
        )
        val conflictKey = "\$.k#VALUE"

        run {
            val rec = FakeDecisionSource.record(conflictKey, "\$.k",
                semmerge.merge.Resolution.Delete, fps)
            val src = MergeTestSupport.sourceWith(b, a, c, mapOf(conflictKey to rec))
            val out = OutputBuilder.build(MergeTestSupport.merge(b, a, c, decisions = src)) as SMap
            assertFalse(out.keys.contains("k"))
        }
        run {
            val rec = FakeDecisionSource.record(conflictKey, "\$.k",
                semmerge.merge.Resolution.SetNull, fps)
            val src = MergeTestSupport.sourceWith(b, a, c, mapOf(conflictKey to rec))
            val out = OutputBuilder.build(MergeTestSupport.merge(b, a, c, decisions = src)) as SMap
            assertEquals(ScalarKind.NULL, (out.get("k") as SScalar).kind)
        }
    }
}
