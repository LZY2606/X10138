package semmerge.web

import semmerge.json.JsonWriter
import semmerge.merge.Advice
import semmerge.merge.BundleService
import semmerge.merge.Conflict
import semmerge.merge.ConflictKind
import semmerge.merge.DecisionRecord
import semmerge.merge.ListStrategy
import semmerge.merge.MDeleted
import semmerge.merge.MEntry
import semmerge.merge.MIdItem
import semmerge.merge.MList
import semmerge.merge.MMap
import semmerge.merge.MNode
import semmerge.merge.MSeqElem
import semmerge.merge.MValue
import semmerge.merge.MergeResult
import semmerge.merge.MergeStatus
import semmerge.merge.OutputBuilder
import semmerge.merge.ProvStep
import semmerge.merge.Resolution
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.session.InputSide
import semmerge.session.SessionState
import semmerge.session.JsonCodec.asList
import semmerge.session.JsonCodec.asMap
import semmerge.session.JsonCodec.asString
import semmerge.session.JsonCodec.list
import semmerge.session.JsonCodec.num
import semmerge.session.JsonCodec.obj
import semmerge.session.JsonCodec.str

data class ViewState(
    val sessionVersion: Long,
    val policyVersion: Int,
    val merged: SNode,
    val inputs: SNode,
    val policies: SNode,
    val conflicts: SNode,
    val stats: SNode,
    val decisions: SNode,
    val advisories: SNode,
)

object ViewStateBuilder {

    fun build(state: SessionState, result: MergeResult): ViewState {
        val output = runCatching { OutputBuilder.build(result) }.getOrElse { semmerge.model.SMap() }
        val mergedTree = treeNode(result.tree)

        val inputs = obj(
            "base" to inputView(state, InputSide.BASE),
            "branchA" to inputView(state, InputSide.BRANCH_A),
            "branchB" to inputView(state, InputSide.BRANCH_B),
        )

        val policyNodes = state.policies.all().map { p ->
            obj(
                "path" to str(p.path.render()),
                "strategy" to str(p.strategy.id),
                "label" to str(p.strategy.label),
                "idField" to str(p.idField),
                "version" to num(p.version),
            )
        }

        val conflictNodes = result.conflicts.map { conflictView(it, state, result) }
        val autoCount = countStatus(result.tree) {
            it == MergeStatus.AUTO_A || it == MergeStatus.AUTO_B || it == MergeStatus.AUTO_BOTH ||
                it == MergeStatus.DELETED_A || it == MergeStatus.DELETED_B
        }
        val stats = obj(
            "version" to num(state.version),
            "policyVersion" to num(state.policies.version.toLong()),
            "totalConflicts" to num(result.conflicts.size),
            "resolved" to num(result.appliedDecisions.size),
            "advisory" to num(result.advisoryDecisions.size),
            "autoMerged" to num(autoCount),
            "resultFingerprint" to str(semmerge.iof.Fingerprint.of(output)),
            "canExport" to boolNode(result.conflicts.size == result.appliedDecisions.size),
        )

        val decisionNodes = state.decisions.values.flatten().map { decisionView(it) }
        val advisoryNodes = result.advisoryDecisions.map { key ->
            val rec = state.find(key)
            obj(
                "conflictKey" to str(key),
                "resolution" to str(rec?.resolution?.label() ?: ""),
                "decidedAt" to str(rec?.decidedAt?.toString() ?: ""),
            )
        }

        return ViewState(
            sessionVersion = state.version,
            policyVersion = state.policies.version,
            merged = obj(
                "tree" to mergedTree,
                "outputPreviewYaml" to str(runCatching {
                    BundleService.renderOutput(output, "yaml")
                }.getOrElse { "（仍有冲突，解决后才能导出）\n" + (it.message ?: "") }),
                "outputPreviewJson" to str(runCatching {
                    BundleService.renderOutput(output, "json")
                }.getOrElse { "" }),
            ),
            inputs = inputs,
            policies = SList(policyNodes),
            conflicts = SList(conflictNodes),
            stats = stats,
            decisions = SList(decisionNodes),
            advisories = SList(advisoryNodes),
        )
    }

    private fun boolNode(b: Boolean): SNode =
        SScalar(ScalarKind.BOOL, b.toString(), semmerge.model.ScalarStyle.PLAIN)

    private fun inputView(state: SessionState, side: InputSide): SNode {
        val raw = state.inputs[side]
        val parsed = state.parsed[side]
        return obj(
            "format" to str(raw?.format ?: "yaml"),
            "text" to str(raw?.text ?: ""),
            "fingerprint" to str(semmerge.iof.Fingerprint.of(parsed)),
            "tree" to sourceTree(parsed, semmerge.model.Path.ROOT),
        )
    }

    private fun conflictView(c: Conflict, state: SessionState, result: MergeResult): SNode {
        val record = state.find(c.key)
        val applied = result.appliedDecisions.contains(c.key)
        val advisory = result.advisoryDecisions.contains(c.key)
        return obj(
            "key" to str(c.key),
            "path" to str(c.path.render()),
            "kind" to str(c.kind.name),
            "message" to str(c.message),
            "index" to (c.index?.let { num(it.toLong()) } ?: SScalar.NULL),
            "duplicateId" to (c.duplicateId?.let { str(it) } ?: SScalar.NULL),
            "status" to str(when {
                applied -> "resolved"
                advisory -> "stale-advice"
                else -> "open"
            }),
            "priorResolution" to (record?.let { str(it.resolution.label()) } ?: SScalar.NULL),
            "mismatchReason" to (
                if (advisory) {
                    val fps = result.fingerprints
                    val reasons = mutableListOf<String>()
                    if (record!!.fingerprints.base != fps.base) reasons += "祖先变化"
                    if (record.fingerprints.branchA != fps.branchA) reasons += "A 变化"
                    if (record.fingerprints.branchB != fps.branchB) reasons += "B 变化"
                    if (record.policyVersion != state.policies.version) reasons += "策略版本变化"
                    str(reasons.joinToString("；"))
                } else SScalar.NULL
            ),
        )
    }

    private fun decisionView(r: DecisionRecord): SNode = obj(
        "conflictKey" to str(r.conflictKey),
        "path" to str(r.path.render()),
        "kind" to str(r.kind.name),
        "resolution" to str(r.resolution.label()),
        "decidedAt" to str(r.decidedAt.toString()),
        "decidedBy" to str(r.decidedBy),
        "policyVersion" to num(r.policyVersion.toLong()),
        "baseSessionVersion" to num(r.baseSessionVersion),
        "note" to str(r.note),
        "fingerprints" to obj(
            "base" to str(r.fingerprints.base),
            "branchA" to str(r.fingerprints.branchA),
            "branchB" to str(r.fingerprints.branchB),
        ),
    )

    private fun countStatus(node: MNode, pred: (MergeStatus) -> Boolean): Int {
        var n = if (pred(node.status)) 1 else 0
        when (node) {
            is MMap -> node.entries.forEach { n += countStatus(it.node, pred) }
            is MList -> {
                node.items.forEach { n += countStatus(it.node, pred) }
                node.hunks.forEach { h -> h.merged.forEach { n += countStatus(it.node, pred) } }
            }
            else -> Unit
        }
        return n
    }

    private fun treeNode(node: MNode): SNode {
        val base = mutableListOf(
            "path" to str(node.path.render()),
            "status" to str(node.status.name),
            "kind" to str(kindName(node)),
            "explanation" to str(node.provenance.explanation),
            "chain" to SNodeList(node.provenance.chain.map(::stepView)),
            "conflict" to (node.conflict?.let { miniConflict(it) } ?: SScalar.NULL),
            "suggestion" to (node.suggestion?.let(::adviceView) ?: SScalar.NULL),
        )
        when (node) {
            is MMap -> {
                base += "children" to SNodeList(node.entries.map { entryView(it) })
                base += "deleted" to boolNode(node.entries.any { it.node is MDeleted })
            }
            is MList -> {
                base += "strategy" to str(node.strategy?.id ?: "unregistered")
                base += "children" to SNodeList(node.items.map { idItemView(it) })
                base += "hunks" to SNodeList(node.hunks.map { hunkView(it) })
                base += "orderConflict" to (node.orderConflict?.let { miniConflict(it) } ?: SScalar.NULL)
            }
            is MValue -> {
                base += "value" to scalarView(node.value)
                base += "fingerprints" to obj(
                    "base" to str(node.baseFp), "branchA" to str(node.aFp), "branchB" to str(node.bFp),
                )
            }
            is MDeleted -> base += "deleted" to boolNode(true)
        }
        return SMap(base)
    }

    private fun kindName(node: MNode): String = when (node) {
        is MMap -> "map"
        is MList -> "list"
        is MDeleted -> "deleted"
        is MValue -> when (node.value) {
            is SMap -> "map"
            is SList -> "list"
            is SScalar -> "scalar"
        }
    }

    private fun entryView(e: MEntry): SNode = obj(
        "key" to str(e.key),
        "node" to treeNode(e.node),
    )

    private fun idItemView(item: MIdItem): SNode = obj(
        "id" to str(item.id),
        "order" to str(item.order.name),
        "node" to treeNode(item.node),
    )

    private fun hunkView(h: semmerge.merge.SeqHunk): SNode = obj(
        "start" to num(h.start.toLong()),
        "auto" to boolNode(h.auto),
        "merged" to SNodeList(h.merged.map { elem ->
            obj("side" to str(elem.side.name), "node" to treeNode(elem.node))
        }),
    )

    private fun miniConflict(c: Conflict): SNode = obj(
        "key" to str(c.key),
        "kind" to str(c.kind.name),
        "message" to str(c.message),
        "index" to (c.index?.let { num(it.toLong()) } ?: SScalar.NULL),
    )

    private fun adviceView(a: Advice): SNode = obj(
        "resolutionLabel" to str(a.resolutionLabel),
        "reason" to str(a.reason),
    )

    private fun stepView(step: ProvStep): SNode = obj(
        "side" to str(step.side.name),
        "action" to str(step.action),
        "fingerprint" to str(step.fingerprint),
        "detail" to (step.detail?.let { str(it) } ?: SScalar.NULL),
    )

    private fun scalarView(node: SNode): SNode = when (node) {
        is SScalar -> obj(
            "type" to str(node.kind.name.lowercase()),
            "text" to str(node.text),
        )
        is SMap -> sourceTree(node, semmerge.model.Path.ROOT)
        is SList -> sourceTree(node, semmerge.model.Path.ROOT)
    }

    private fun sourceTree(node: SNode?, path: semmerge.model.Path): SNode {
        if (node == null) return obj("missing" to boolNode(true))
        return when (node) {
            is SScalar -> obj(
                "path" to str(path.render()),
                "kind" to str("scalar"),
                "scalarType" to str(node.kind.name.lowercase()),
                "value" to str(if (node.kind == ScalarKind.NULL) "null" else node.text),
            )
            is SMap -> obj(
                "path" to str(path.render()),
                "kind" to str("map"),
                "children" to SNodeList(node.entries().map { (k, v) ->
                    obj("key" to str(k), "node" to sourceTree(v, path.child(k)))
                }),
            )
            is SList -> obj(
                "path" to str(path.render()),
                "kind" to str("list"),
                "children" to SNodeList(node.items.mapIndexed { i, v ->
                    obj("key" to num(i.toLong()), "node" to sourceTree(v, path.index(i)))
                }),
            )
        }
    }

    private fun SNodeList(items: List<SNode>): SList = SList(items)
}
