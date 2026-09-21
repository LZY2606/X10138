package semmerge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import semmerge.merge.MergeStatus
import semmerge.merge.MValue
import semmerge.merge.MMap
import semmerge.merge.Resolution

class DecisionReplayTest {

    private val base = MergeTestSupport.yaml("x: 1\n")
    private val branchA = MergeTestSupport.yaml("x: 2\n")
    private val branchB = MergeTestSupport.yaml("x: 3\n")
    private val key = "\$.x#VALUE"
    private val fps = semmerge.iof.TripleFingerprint(
        semmerge.iof.Fingerprint.of(base),
        semmerge.iof.Fingerprint.of(branchA),
        semmerge.iof.Fingerprint.of(branchB),
    )

    @Test
    fun `matching decision is auto-applied on remerge`() {
        val rec = FakeDecisionSource.record(key, "\$.x", Resolution.TakeB, fps)
        val source = MergeTestSupport.sourceWith(base, branchA, branchB, mapOf(key to rec))
        val result = MergeTestSupport.merge(base, branchA, branchB, decisions = source)
        assertEquals(listOf(key), result.appliedDecisions)
        assertTrue(result.advisoryDecisions.isEmpty())
        val node = (result.tree as MMap).entries.single { it.key == "x" }.node as MValue
        assertEquals(MergeStatus.RESOLVED, node.status)
        assertEquals("3", (node.value as semmerge.model.SScalar).text)
    }

    @Test
    fun `decision bound to changed ancestor is advice only`() {
        val changedBase = MergeTestSupport.yaml("x: 100\n")
        val rec = FakeDecisionSource.record(key, "\$.x", Resolution.TakeB, fps)
        val staleFps = semmerge.iof.TripleFingerprint(
            semmerge.iof.Fingerprint.of(changedBase),
            semmerge.iof.Fingerprint.of(branchA),
            semmerge.iof.Fingerprint.of(branchB),
        )
        val source = FakeDecisionSource(mapOf(key to rec), 1, staleFps)
        val result = MergeTestSupport.merge(changedBase, branchA, branchB, decisions = source)
        assertTrue(result.appliedDecisions.isEmpty())
        assertEquals(listOf(key), result.advisoryDecisions)
        val node = (result.tree as MMap).entries.single { it.key == "x" }.node
        assertEquals(MergeStatus.CONFLICT, node.status)
        assertTrue(node.suggestion != null)
        assertTrue(node.suggestion!!.reason.contains("祖先"))
    }

    @Test
    fun `changed branch demotes decision to advice`() {
        val changedB = MergeTestSupport.yaml("x: 30\n")
        val rec = FakeDecisionSource.record(key, "\$.x", Resolution.TakeB, fps)
        val staleFps = semmerge.iof.TripleFingerprint(
            semmerge.iof.Fingerprint.of(base),
            semmerge.iof.Fingerprint.of(branchA),
            semmerge.iof.Fingerprint.of(changedB),
        )
        val source = FakeDecisionSource(mapOf(key to rec), 1, staleFps)
        val result = MergeTestSupport.merge(base, branchA, changedB, decisions = source)
        assertEquals(emptyList<String>(), result.appliedDecisions)
        assertEquals(listOf(key), result.advisoryDecisions)
    }

    @Test
    fun `custom yaml decision survives replay`() {
        val rec = FakeDecisionSource.record(key, "\$.x",
            Resolution.Custom("port: 9999", "yaml"), fps)
        val source = MergeTestSupport.sourceWith(base, branchA, branchB, mapOf(key to rec))
        val result = MergeTestSupport.merge(base, branchA, branchB, decisions = source)
        val node = (result.tree as MMap).entries.single { it.key == "x" }.node as MValue
        val out = node.value as semmerge.model.SMap
        assertEquals("9999", (out.get("port") as semmerge.model.SScalar).text)
        assertEquals(MergeStatus.RESOLVED, node.status)
    }
}
