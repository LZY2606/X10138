package semmerge.merge

import semmerge.iof.Fingerprint
import semmerge.iof.TripleFingerprint
import semmerge.json.JsonParser
import semmerge.json.JsonWriter
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.model.ScalarStyle
import semmerge.session.InputSide
import semmerge.session.InputParser
import semmerge.session.JsonCodec
import semmerge.session.PathRenderer
import semmerge.session.JsonCodec.asList
import semmerge.session.JsonCodec.asMap
import semmerge.session.JsonCodec.asString
import semmerge.session.JsonCodec.list
import semmerge.session.JsonCodec.map
import semmerge.session.JsonCodec.num
import semmerge.session.JsonCodec.obj
import semmerge.session.JsonCodec.str
import semmerge.session.JsonCodec.strOr
import semmerge.session.JsonCodec.longOr
import semmerge.session.RawInput
import semmerge.session.SessionState
import java.time.Instant


data class BundleSummary(
    val resultFingerprint: String,
    val conflictCount: Int,
    val appliedCount: Int,
    val advisoryCount: Int,
    val outputFormat: String,
)

data class ExportBundle(
    val state: SessionState,
    val result: MergeResult,
    val outputText: String,
    val outputFormat: String,
    val summary: BundleSummary,
) {
    fun toJson(): SNode {
        val inputNodes = InputSide.entries.map { side ->
            val raw = state.inputs[side] ?: RawInput.EMPTY
            SMap(listOf(
                "side" to JsonCodec.str(side.key),
                "format" to JsonCodec.str(raw.format),
                "text" to JsonCodec.str(raw.text),
                "fingerprint" to JsonCodec.str(Fingerprint.of(state.parsed[side])),
            ))
        }
        val policyNodes = state.policies.all().map { p ->
            SMap(listOf(
                "path" to JsonCodec.str(p.path.render()),
                "strategy" to JsonCodec.str(p.strategy.id),
                "idField" to JsonCodec.str(p.idField),
                "version" to num(p.version),
            ))
        }
        val decisionNodes = state.decisions.values.flatten().map { record ->
            SessionStoreJson.encodeDecision(record)
        }
        val flatProvenance = mutableListOf<SNode>()
        collectProvenance(result.tree, flatProvenance)

        return SMap(listOf(
            "bundleVersion" to JsonCodec.str("1"),
            "fingerprints" to SMap(listOf(
                "base" to JsonCodec.str(result.fingerprints.base),
                "branchA" to JsonCodec.str(result.fingerprints.branchA),
                "branchB" to JsonCodec.str(result.fingerprints.branchB),
                "result" to JsonCodec.str(summary.resultFingerprint),
            )),
            "inputs" to SList(inputNodes),
            "policies" to SList(policyNodes),
            "policyVersion" to num(state.policies.version.toLong()),
            "decisions" to SList(decisionNodes),
            "provenanceIndex" to SList(flatProvenance),
            "outputFormat" to JsonCodec.str(outputFormat),
            "output" to JsonCodec.str(outputText),
            "sessionVersion" to num(state.version),
        ))
    }

    companion object {
        fun collectProvenance(node: MNode, out: MutableList<SNode>) {
            val path = node.path.render()
            val steps = node.provenance.chain.map { step ->
                SMap(listOf(
                    "side" to JsonCodec.str(step.side.name),
                    "action" to JsonCodec.str(step.action),
                    "fingerprint" to JsonCodec.str(step.fingerprint),
                    "detail" to (step.detail?.let { JsonCodec.str(it) } ?: SScalar.NULL),
                ))
            }
            out += SMap(listOf(
                "path" to JsonCodec.str(path),
                "status" to JsonCodec.str(node.status.name),
                "explanation" to JsonCodec.str(node.provenance.explanation),
                "chain" to SList(steps),
            ))
            when (node) {
                is MMap -> node.entries.forEach { collectProvenance(it.node, out) }
                is MList -> {
                    node.items.forEach { collectProvenance(it.node, out) }
                    node.hunks.forEach { h -> h.merged.forEach { collectProvenance(it.node, out) } }
                }
                else -> Unit
            }
        }
    }
}

object BundleService {
    fun export(state: SessionState, result: MergeResult, format: String): ExportBundle {
        val outputNode = OutputBuilder.build(result)
        val outputText = renderOutput(outputNode, format)
        return ExportBundle(
            state = state,
            result = result,
            outputText = outputText,
            outputFormat = format,
            summary = BundleSummary(
                resultFingerprint = Fingerprint.of(outputNode),
                conflictCount = result.conflicts.size,
                appliedCount = result.appliedDecisions.size,
                advisoryCount = result.advisoryDecisions.size,
                outputFormat = format,
            ),
        )
    }

    fun renderOutput(node: SNode, format: String): String = when (format.lowercase()) {
        "json" -> JsonWriter.write(node, sortKeys = true)
        else -> semmerge.yaml.YamlEmitter.emit(node)
    }

    /** Reimport: rebuild state and re-merge; provenance/fingerprints must match. */
    fun importBundle(rootNode: SNode): Pair<SessionState, MergeResult> {
        val root = rootNode.asMap()
        val inputs = mutableMapOf<InputSide, RawInput>()
        for (node in root.list("inputs")) {
            val m = node.asMap()
            val side = InputSide.entries.first { it.key == m.str("side") }
            inputs[side] = RawInput(m.str("text"), m.strOr("format", "yaml"))
        }
        val fullInputs = InputSide.entries.associateWith { inputs[it] ?: RawInput.EMPTY }
        val parsed = fullInputs.mapValues { InputParser.parse(it.value) }

        val policyEntries = root.list("policies").map { n ->
            val m = n.asMap()
            PolicyEntry(
                path = PathRenderer.parse(m.str("path")),
                strategy = ListStrategy.entries.first { it.id == m.str("strategy") },
                idField = m.strOr("idField", "id"),
                version = (m["version"] as SScalar).text.toInt(),
            )
        }.associateBy { it.path.render() }
        val policyVersion = root.longOr("policyVersion", policyEntries.values.maxOfOrNull { it.version }?.toLong() ?: 1)
        val policies = PolicyRegistry(policyEntries, policyVersion.toInt())

        val decisions = root.list("decisions")
            .map { SessionStoreJson.decodeDecision(it) }
            .groupBy { it.conflictKey }

        val state = SessionState(
            inputs = fullInputs,
            parsed = parsed,
            policies = policies,
            decisions = decisions,
            version = root.longOr("sessionVersion", 1),
        )
        val result = Merger(
            parsed[InputSide.BASE],
            parsed[InputSide.BRANCH_A],
            parsed[InputSide.BRANCH_B],
            policies, state,
        ).merge()
        return state to result
    }

    /** Verify the reimported merge reproduces identical paths, chains and result fp. */
    fun verifyRoundTrip(original: ExportBundle, importedState: SessionState, importedResult: MergeResult): List<String> {
        val errors = mutableListOf<String>()
        val originalOutput = OutputBuilder.build(original.result)
        val importedOutput = OutputBuilder.build(importedResult)
        if (Fingerprint.of(originalOutput) != Fingerprint.of(importedOutput)) {
            errors += "结果指纹不一致"
        }
        val origProv = mutableListOf<SNode>()
        val newProv = mutableListOf<SNode>()
        ExportBundle.collectProvenance(original.result.tree, origProv)
        ExportBundle.collectProvenance(importedResult.tree, newProv)
        if (JsonWriter.write(SList(origProv), sortKeys = true) != JsonWriter.write(SList(newProv), sortKeys = true)) {
            errors += "来源链或路径集合不一致"
        }
        return errors
    }
}

/** Re-expose the store's decision codec for the bundle. */
object SessionStoreJson {
    fun encodeDecision(r: DecisionRecord): SNode {
        return SMap(listOf(
            "conflictKey" to JsonCodec.str(r.conflictKey),
            "path" to JsonCodec.str(r.path.render()),
            "kind" to JsonCodec.str(r.kind.name),
            "resolution" to encodeResolution(r.resolution),
            "fingerprints" to SMap(listOf(
                "base" to JsonCodec.str(r.fingerprints.base),
                "branchA" to JsonCodec.str(r.fingerprints.branchA),
                "branchB" to JsonCodec.str(r.fingerprints.branchB),
            )),
            "policyVersion" to num(r.policyVersion.toLong()),
            "decidedAt" to JsonCodec.str(r.decidedAt.toString()),
            "decidedBy" to JsonCodec.str(r.decidedBy),
            "baseSessionVersion" to num(r.baseSessionVersion),
            "note" to JsonCodec.str(r.note),
        ))
    }

    private fun encodeResolution(resolution: Resolution): SNode = when (resolution) {
        Resolution.TakeA -> obj("type" to JsonCodec.str("takeA"))
        Resolution.TakeB -> obj("type" to JsonCodec.str("takeB"))
        Resolution.KeepBase -> obj("type" to JsonCodec.str("keepBase"))
        Resolution.Delete -> obj("type" to JsonCodec.str("delete"))
        Resolution.SetNull -> obj("type" to JsonCodec.str("setNull"))
        is Resolution.Custom -> obj(
            "type" to JsonCodec.str("custom"),
            "text" to JsonCodec.str(resolution.text),
            "format" to JsonCodec.str(resolution.format),
        )
        is Resolution.CustomOrder -> obj(
            "type" to JsonCodec.str("customOrder"),
            "ids" to SList(resolution.ids.map { JsonCodec.str(it) }),
        )
    }

    fun decodeDecision(node: SNode): DecisionRecord {
        val m = node.asMap()
        val fps = m.map("fingerprints")
        return DecisionRecord(
            conflictKey = m.str("conflictKey"),
            path = PathRenderer.parse(m.str("path")),
            kind = ConflictKind.valueOf(m.str("kind")),
            resolution = decodeResolution(m.map("resolution")),
            fingerprints = TripleFingerprint(fps.str("base"), fps.str("branchA"), fps.str("branchB")),
            policyVersion = (m["policyVersion"] as SScalar).text.toInt(),
            decidedAt = Instant.parse(m.str("decidedAt")),
            decidedBy = m.strOr("decidedBy", "local-user"),
            baseSessionVersion = m.longOr("baseSessionVersion", 1),
            note = m.strOr("note", ""),
        )
    }

    private fun decodeResolution(m: Map<String, SNode>): Resolution = when (val type = m.str("type")) {
        "takeA" -> Resolution.TakeA
        "takeB" -> Resolution.TakeB
        "keepBase" -> Resolution.KeepBase
        "delete" -> Resolution.Delete
        "setNull" -> Resolution.SetNull
        "custom" -> Resolution.Custom(m.str("text"), m.strOr("format", "yaml"))
        "customOrder" -> Resolution.CustomOrder(m["ids"]!!.asList().map { it.asString() })
        else -> error("unknown resolution type $type")
    }
}
