package semmerge.merge

import semmerge.iof.Fingerprint
import semmerge.iof.TripleFingerprint
import semmerge.model.Path
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.equalScalar
import semmerge.model.structuralEqual

data class MergeResult(
    val tree: MNode,
    val conflicts: List<Conflict>,
    val appliedDecisions: List<String>,
    val advisoryDecisions: List<String>,
    val fingerprints: TripleFingerprint,
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

private data class Presence(val node: SNode?, val deleted: Boolean) {
    companion object {
        val MISSING = Presence(null, false)
        fun of(node: SNode?): Presence = if (node == null) MISSING else Presence(node, false)
    }
}

class Merger(
    private val base: SNode?,
    private val branchA: SNode?,
    private val branchB: SNode?,
    internal val policy: PolicyRegistry,
    internal val decisions: DecisionSource,
) {
    private val fpBase = Fingerprint.of(base)
    private val fpA = Fingerprint.of(branchA)
    private val fpB = Fingerprint.of(branchB)
    private val triple = TripleFingerprint(fpBase, fpA, fpB)

    internal val conflicts = mutableListOf<Conflict>()
    internal val applied = mutableListOf<String>()
    internal val advisory = mutableListOf<String>()

    fun merge(): MergeResult {
        val tree = mergeNode(Path.ROOT, base, branchA, branchB)
        return MergeResult(tree, conflicts.toList(), applied.toList(), advisory.toList(), triple)
    }

    internal fun conflictKey(path: Path, kind: ConflictKind, suffix: String? = null): String {
        val k = path.render() + "#" + kind.name + (suffix?.let { ":$it" } ?: "")
        return k
    }

    internal fun recordConflict(c: Conflict) {
        conflicts += c
    }

    internal fun decide(c: Conflict): DecisionMatch? {
        val m = decisions.match(decisions.find(c.key)) ?: return null
        if (m.applicable) applied += c.key else advisory += c.key
        return m
    }

    internal fun prov(explanation: String, vararg steps: ProvStep): Provenance =
        Provenance(steps.toList(), explanation)

    internal fun step(side: Side, node: SNode?, action: String? = null, detail: String? = null): ProvStep {
        val act = action ?: when {
            node == null -> "missing"
            node is SScalar && node.kind == semmerge.model.ScalarKind.NULL -> "null"
            else -> "value"
        }
        return ProvStep(side, act, Fingerprint.of(node), detail)
    }

    internal fun mergeNode(path: Path, b: SNode?, a: SNode?, c: SNode?): MNode {
        val sameBtoA = structuralEqual(b, a)
        val sameBtoC = structuralEqual(b, c)
        return when {
            b is SMap && (a == null || a is SMap) && (c == null || c is SMap) ->
                mergeMap(path, b, a as SMap?, c as SMap?)
            b is SList && (a == null || a is SList) && (c == null || c is SList) ->
                mergeList(path, b as SList, a as SList?, c as SList?)
            // A side newly structures a missing/other value.
            a is SMap && c is SMap && b !is SMap ->
                mergeMap(path, b as? SMap, a, c as? SMap)
            a is SList && c is SList && b !is SList ->
                mergeList(path, b as? SList ?: SList(), a, c as? SList)
            a is SMap || c is SMap ->
                mergeMap(path, b as? SMap, a as? SMap, c as? SMap)
            a is SList || c is SList ->
                mergeList(path, b as? SList ?: SList(), a as? SList, c as? SList)
            else -> mergeScalar(path, b, a, c, sameBtoA, sameBtoC)
        }
    }

    private fun mergeScalar(
        path: Path,
        b: SNode?,
        a: SNode?,
        c: SNode?,
        sameBtoA: Boolean,
        sameBtoC: Boolean,
    ): MNode {
        val aEqC = structuralEqual(a, c)
        val bFp = Fingerprint.of(b)
        val aFp = Fingerprint.of(a)
        val cFp = Fingerprint.of(c)

        if (sameBtoA && sameBtoC) {
            return valueNode(path, b, bFp, aFp, cFp, MergeStatus.UNCHANGED,
                prov("三方一致", step(Side.BASE, b), step(Side.BRANCH_A, a), step(Side.BRANCH_B, c)))
        }
        if (sameBtoA && !sameBtoC) {
            return valueNode(path, c, bFp, aFp, cFp, MergeStatus.AUTO_B,
                prov("分支 B 的单边修改；分支 A 未改动该路径", step(Side.BASE, b), step(Side.BRANCH_A, a), step(Side.BRANCH_B, c)))
        }
        if (!sameBtoA && sameBtoC) {
            return valueNode(path, a, bFp, aFp, cFp, MergeStatus.AUTO_A,
                prov("分支 A 的单边修改；分支 B 未改动该路径", step(Side.BASE, b), step(Side.BRANCH_A, a), step(Side.BRANCH_B, c)))
        }
        if (aEqC) {
            return valueNode(path, a, bFp, aFp, cFp, MergeStatus.AUTO_BOTH,
                prov("两个分支做出了相同的修改", step(Side.BASE, b), step(Side.BRANCH_A, a), step(Side.BRANCH_B, c)))
        }

        val kind = if (b != null && (a == null) != (c == null)) {
            ConflictKind.DELETE_EDIT
        } else if (a == null && c == null) {
            ConflictKind.DELETE_DELETE
        } else if (a != null && c != null && (a::class != c::class || (a is SScalar && c is SScalar && a.kind != c.kind))) {
            ConflictKind.TYPE
        } else ConflictKind.VALUE

        if (kind == ConflictKind.DELETE_DELETE) {
            return deletedNode(path, bFp, aFp, cFp, MergeStatus.AUTO_BOTH,
                "两个分支都删除了该字段", bothDelete = true)
        }

        val msg = conflictMessage(path, b, a, c, kind)
        val conflict = Conflict(conflictKey(path, kind), path, kind, msg)
        recordConflict(conflict)
        val match = decide(conflict)
        return if (match?.applicable == true) {
            applyResolution(path, match.record.resolution, b, a, c, bFp, aFp, cFp, conflict, null)
        } else {
            val suggested = match?.let { suggestionOf(it) }
            val (default, status) = if (a == null) {
                null to MergeStatus.CONFLICT
            } else {
                a to MergeStatus.CONFLICT
            }
            val explanation = "冲突：${msg}" + (match?.mismatchReason?.let { "；旧裁决仅作建议（$it）" } ?: "")
            if (a == null && c == null) error("unreachable")
            if (a == null) {
                MDeleted(path, MergeStatus.CONFLICT,
                    prov(explanation, step(Side.BASE, b), deleteStep(Side.BRANCH_A), step(Side.BRANCH_B, c)),
                    conflict, suggested)
            } else {
                valueNode(path, default!!, bFp, aFp, cFp, status,
                    prov(explanation, step(Side.BASE, b), step(Side.BRANCH_A, a), step(Side.BRANCH_B, c)),
                    conflict, suggested)
            }
        }
    }

    private fun deletedNode(path: Path, bFp: String, aFp: String, cFp: String,
                            status: MergeStatus, explanation: String, bothDelete: Boolean): MDeleted {
        val steps = listOf(
            ProvStep(Side.BASE, "value", bFp),
            ProvStep(Side.BRANCH_A, "delete", Fingerprint.deleted()),
            ProvStep(Side.BRANCH_B, if (bothDelete) "delete" else "value",
                if (bothDelete) Fingerprint.deleted() else cFp),
        )
        return MDeleted(path, status, Provenance(steps, explanation))
    }

    private fun deleteStep(side: Side) = ProvStep(side, "delete", Fingerprint.deleted())

    private fun conflictMessage(path: Path, b: SNode?, a: SNode?, c: SNode?, kind: ConflictKind): String = when (kind) {
        ConflictKind.DELETE_EDIT ->
            "一侧删除（${if (a == null) "A" else "B"}），另一侧修改了该值"
        ConflictKind.TYPE ->
            "两个分支把该值改成了不同类型（${typeName(a)} vs ${typeName(c)}）"
        else ->
            "两个分支对同一值做了不同修改（${describe(a)} vs ${describe(c)}）"
    }

    private fun typeName(n: SNode?): String = when (n) {
        null -> "缺失"
        is SScalar -> when (n.kind) {
            semmerge.model.ScalarKind.NULL -> "null"
            semmerge.model.ScalarKind.BOOL -> "布尔"
            semmerge.model.ScalarKind.INT -> "整数"
            semmerge.model.ScalarKind.FLOAT -> "小数"
            semmerge.model.ScalarKind.STRING -> "字符串"
        }
        is SMap -> "对象"
        is SList -> "数组"
    }

    private fun describe(n: SNode?): String = when (n) {
        null -> "<删除>"
        is SScalar -> if (n.kind == semmerge.model.ScalarKind.NULL) "null" else n.text.take(40)
        is SMap -> "{…${n.size} 项}"
        is SList -> "[…${n.items.size} 项]"
    }

    internal fun suggestionOf(match: DecisionMatch): Advice = Advice(
        resolutionLabel = match.record.resolution.label(),
        reason = match.mismatchReason ?: "输入已变化",
        oldFingerprints = match.record.fingerprints.toMap(),
    )

    private fun valueNode(
        path: Path, value: SNode?, bFp: String, aFp: String, cFp: String,
        status: MergeStatus, provenance: Provenance,
        conflict: Conflict? = null, suggestion: Advice? = null,
    ): MNode = if (value == null) {
        MDeleted(path, if (status == MergeStatus.UNCHANGED) MergeStatus.UNCHANGED else status,
            provenance, conflict, suggestion)
    } else {
        MValue(path, value, bFp, aFp, cFp, status, provenance, conflict, suggestion)
    }

    internal fun applyResolution(
        path: Path,
        resolution: Resolution,
        b: SNode?,
        a: SNode?,
        c: SNode?,
        bFp: String, aFp: String, cFp: String,
        conflict: Conflict,
        suggestion: Advice?,
    ): MNode {
        val steps = listOf(step(Side.BASE, b), step(Side.BRANCH_A, a), step(Side.BRANCH_B, c))
        return when (resolution) {
            Resolution.TakeA -> chosenValue(path, a, steps, conflict, "采纳分支 A", aFp = aFp, bFp = bFp, cFp = cFp)
            Resolution.TakeB -> chosenValue(path, c, steps, conflict, "采纳分支 B", aFp = aFp, bFp = bFp, cFp = cFp)
            Resolution.KeepBase -> chosenValue(path, b, steps, conflict, "保留祖先版本", aFp = aFp, bFp = bFp, cFp = cFp)
            Resolution.Delete -> MDeleted(path, MergeStatus.RESOLVED,
                Provenance(steps + ProvStep(Side.DECISION, "delete", Fingerprint.deleted(), "人工裁决：删除"),
                    "人工裁决：删除"), conflict)
            Resolution.SetNull -> MValue(path, SScalar.NULL, bFp, aFp, cFp, MergeStatus.RESOLVED,
                Provenance(steps + ProvStep(Side.DECISION, "null", Fingerprint.of(SScalar.NULL), "人工裁决：显式 null"),
                    "人工裁决：设为显式 null（与删除不同）"), conflict)
            is Resolution.Custom -> {
                val parsed = CustomValues.parse(resolution.text, resolution.format)
                MValue(path, parsed, bFp, aFp, cFp, MergeStatus.RESOLVED,
                    Provenance(steps + ProvStep(Side.DECISION, "value", Fingerprint.of(parsed),
                        "人工裁决：自定义 ${resolution.format} 值"), "人工裁决：自定义值"), conflict)
            }
            is Resolution.CustomOrder -> error("CustomOrder applies only to ORDER conflicts: ${path.render()}")
        }
    }

    private fun chosenValue(
        path: Path, value: SNode?, steps: List<ProvStep>, conflict: Conflict,
        label: String, aFp: String, bFp: String, cFp: String,
    ): MNode {
        val extra = ProvStep(Side.DECISION, if (value == null) "delete" else "value",
            value?.let { Fingerprint.of(it) } ?: Fingerprint.deleted(), label)
        val p = Provenance(steps + extra, label)
        return if (value == null) MDeleted(path, MergeStatus.RESOLVED, p, conflict)
        else MValue(path, value, bFp, aFp, cFp, MergeStatus.RESOLVED, p, conflict)
    }
}
