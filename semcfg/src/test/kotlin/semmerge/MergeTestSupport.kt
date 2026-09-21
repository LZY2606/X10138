package semmerge

import semmerge.iof.TripleFingerprint
import semmerge.merge.DecisionRecord
import semmerge.merge.DecisionSource
import semmerge.merge.ListStrategy
import semmerge.merge.Merger
import semmerge.merge.MergeResult
import semmerge.merge.PolicyEntry
import semmerge.merge.PolicyRegistry
import semmerge.merge.Resolution
import semmerge.model.Path
import semmerge.model.SNode
import semmerge.yaml.YamlParser
import java.time.Instant

class FakeDecisionSource(
    private val records: Map<String, DecisionRecord> = emptyMap(),
    private val policyVersion: Int = 1,
    private val currentFps: TripleFingerprint,
) : DecisionSource {
    override fun find(conflictKey: String): DecisionRecord? = records[conflictKey]
    override fun history(conflictKey: String): List<DecisionRecord> =
        records[conflictKey]?.let { listOf(it) } ?: emptyList()
    override fun currentPolicyVersion(): Int = policyVersion
    override fun currentFingerprints(): TripleFingerprint = currentFps

    companion object {
        fun record(key: String, path: String, resolution: Resolution,
                   fps: TripleFingerprint, policyVersion: Int = 1): DecisionRecord =
            DecisionRecord(
                conflictKey = key,
                path = PathRendererTest.parse(path),
                kind = semmerge.merge.ConflictKind.VALUE,
                resolution = resolution,
                fingerprints = fps,
                policyVersion = policyVersion,
                decidedAt = Instant.parse("2026-01-01T00:00:00Z"),
                decidedBy = "tester",
                baseSessionVersion = 1,
            )
    }
}

object PathRendererTest {
    fun parse(text: String): Path = semmerge.session.PathRenderer.parse(text)
}

object MergeTestSupport {
    fun yaml(text: String): SNode = YamlParser.parse(text.trimIndent())

    fun policy(path: String, strategy: ListStrategy, idField: String = "id", version: Int = 1): PolicyEntry =
        PolicyEntry(PathRendererTest.parse(path), strategy, idField, version)

    fun merge(
        base: SNode?,
        a: SNode?,
        b: SNode?,
        policies: PolicyRegistry = PolicyRegistry.EMPTY,
        decisions: DecisionSource? = null,
    ): MergeResult {
        val fps = TripleFingerprint(
            semmerge.iof.Fingerprint.of(base),
            semmerge.iof.Fingerprint.of(a),
            semmerge.iof.Fingerprint.of(b),
        )
        val source = decisions ?: FakeDecisionSource(emptyMap(), policies.version, fps)
        return Merger(base, a, b, policies, source).merge()
    }

    fun sourceWith(
        base: SNode?, a: SNode?, b: SNode?,
        records: Map<String, DecisionRecord>,
        policies: PolicyRegistry = PolicyRegistry.EMPTY,
        policyVersion: Int? = null,
    ): DecisionSource {
        val fps = TripleFingerprint(
            semmerge.iof.Fingerprint.of(base),
            semmerge.iof.Fingerprint.of(a),
            semmerge.iof.Fingerprint.of(b),
        )
        return FakeDecisionSource(records, policyVersion ?: policies.version, fps)
    }
}
