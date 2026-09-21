package merger

object ApiCodec {

    fun nodeJV(n: MergeNode, anchors: (MergeSide, SNode?) -> List<AnchorRef>): JV.Obj {
        fun valJV(side: MergeSide, v: SNode?): JV.Obj {
            val node = when (v) {
                null, is SSMissing -> JV.Str("<缺失>")
                is SSDelete -> JV.Str("<删除>")
                else -> JV.Str(short(v))
            }
            val present = !(v == null || v is SSMissing || v is SSDelete)
            return JV.Obj(linkedMapOf(
                "present" to JV.Bool(present),
                "kind" to JV.Str(Merger.kind(v)),
                "preview" to node,
                "anchors" to JV.Arr(anchors(side, v).map { JV.Str(it.name + if (it.viaAlias) "(别名)" else "(锚点)") })
            ))
        }

        val children = JV.Arr(n.children.entries.map { (k, child) ->
            JV.Obj(linkedMapOf(
                "key" to JV.Str(k),
                "node" to nodeJV(child, anchors)
            ))
        })
        val prov = JV.Arr(n.provenance.map { p ->
            JV.Obj(linkedMapOf(
                "side" to JV.Str(p.side.name),
                "sideName" to JV.Str(cn(p.side)),
                "path" to JV.Str(p.path),
                "action" to JV.Str(p.action),
                "anchor" to (p.anchor?.let { JV.Str(it) } ?: JV.Null)
            ))
        })
        val conflict = n.conflict?.let { c ->
            JV.Obj(linkedMapOf(
                "kind" to JV.Str(c.kind.name),
                "reason" to JV.Str(c.reason),
                "choices" to JV.Arr(c.choices.map { ch ->
                    JV.Obj(linkedMapOf(
                        "id" to JV.Str(ch.id),
                        "label" to JV.Str(ch.label),
                        "preview" to JV.Str(ch.preview),
                        "side" to (ch.side?.name?.let { JV.Str(it) } ?: JV.Null)
                    ))
                })
            ))
        }
        val map = linkedMapOf<String, JV>(
            "path" to JV.Str(n.path),
            "label" to JV.Str(n.keyLabel),
            "status" to JV.Str(n.status.name),
            "resolved" to JV.Bool(n.resolvedBy != null),
            "resolvedBy" to (n.resolvedBy?.let {
                JV.Obj(linkedMapOf(
                    "decisionId" to JV.Str(it.decisionId),
                    "choiceId" to JV.Str(it.choiceId),
                    "at" to JV.Str(it.at.toString()),
                    "custom" to JV.Bool(it.custom)
                ))
            } ?: JV.Null),
            "autoSummary" to (n.autoSummary?.let { JV.Str(it) } ?: JV.Null),
            "base" to valJV(MergeSide.BASE, n.base),
            "a" to valJV(MergeSide.A, n.a),
            "b" to valJV(MergeSide.B, n.b),
            "result" to (n.result?.let { JV.Str(short(it)) } ?: JV.Null),
            "resultKind" to JV.Str(Merger.kind(n.result)),
            "children" to children,
            "provenance" to prov,
            "conflict" to (conflict ?: JV.Null),
            "advisory" to (n.advisory?.let {
                JV.Obj(linkedMapOf(
                    "oldDecisionId" to JV.Str(it.oldDecisionId),
                    "suggestedChoice" to JV.Str(it.suggestedChoice),
                    "reason" to JV.Str(it.reason)
                ))
            } ?: JV.Null)
        )
        return JV.Obj(map)
    }

    private fun short(v: SNode): String {
        val text = when (v) {
            is SSString -> v.value
            is SSNull -> "null"
            is SSBool -> v.value.toString()
            is SSNumber -> Canonical.normalizeNumber(v.value)
            else -> Emitter.toJson(v, pretty = false).trim()
        }
        return if (text.length > 300) text.take(297) + "..." else text
    }

    fun outcomeJV(o: MergeOutcome, anchors: (MergeSide, SNode?) -> List<AnchorRef>): JV.Obj =
        JV.Obj(linkedMapOf(
            "root" to nodeJV(o.root, anchors),
            "autoCount" to num(o.autoCount),
            "conflictCount" to num(o.conflictCount),
            "resolvedCount" to num(o.resolvedCount),
            "unresolvedCount" to num(o.unresolvedCount),
            "resultFingerprint" to JV.Str(o.resultFingerprint),
            "exported" to JV.Str(o.exported),
            "exportable" to JV.Bool(o.unresolvedCount == 0),
            "advisoryPaths" to JV.Arr(o.advisory.map { JV.Str(it.path) })
        ))

    private fun num(i: Int) = JV.Num(java.math.BigDecimal.valueOf(i.toLong()))
}
