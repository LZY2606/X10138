package merger

/**
 * 供 Web 前端使用的只读 JSON 视图。
 */
object ApiViews {
    fun sessionView(snap: SessionSnapshot, includeRaw: Boolean = true): JValue {
        val doc = snap.document
        return JValues.obj(
            "id" to JValues.str(snap.id),
            "revision" to JValues.num(snap.revision),
            "policyVersion" to JValues.num(doc.policyVersion),
            "fingerprints" to JValues.obj(
                "base" to JValues.str(doc.fingerprints.first),
                "a" to JValues.str(doc.fingerprints.second),
                "b" to JValues.str(doc.fingerprints.third),
            ),
            "policies" to policiesToJson(snap.policies),
            "result" to resultView(doc.result),
            "conflicts" to JValues.arr(doc.conflicts.map { conflictView(it) }),
            "conflictCount" to JValues.num(doc.conflicts.size),
            "resolvedCount" to JValues.num(doc.conflicts.count { it.resolved }),
            "decisions" to JValues.arr(snap.decisions.map { decisionToJson(it) }),
            "inputs" to if (includeRaw) JValues.obj(
                "base" to inputView(snap.baseInput),
                "a" to inputView(snap.aInput),
                "b" to inputView(snap.bInput),
            ) else null,
        )
    }

    fun sessionSources(doc: MergeDocument): JValue = resultView(doc.result)

    private fun inputView(d: MergeSession.InputDoc) = JValues.obj(
        "fileName" to JValues.str(d.fileName),
        "format" to JValues.str(d.format.name),
        "text" to JValues.str(d.text),
    )

    private fun resultView(node: RNode): JValue = when (node) {
        is RNode.RScalar -> JValues.obj(
            "kind" to JValues.str("scalar"),
            "path" to JValues.str(node.path.toString()),
            "value" to NodeJson.scalarJson(node.value),
            "sources" to JValues.arr(node.sources.map { sourceView(it) }),
        )
        is RNode.RDelete -> JValues.obj(
            "kind" to JValues.str("delete"),
            "path" to JValues.str(node.path.toString()),
            "sources" to JValues.arr(node.sources.map { sourceView(it) }),
        )
        is RNode.RObj -> JValues.obj(
            "kind" to JValues.str("obj"),
            "path" to JValues.str(node.path.toString()),
            "children" to JValue.JObj(LinkedHashMap(node.children.mapValues { resultView(it.value) })),
            "sources" to JValues.arr(node.sources.map { sourceView(it) }),
        )
        is RNode.RArr -> JValues.obj(
            "kind" to JValues.str("arr"),
            "path" to JValues.str(node.path.toString()),
            "policy" to node.policy?.let { JValues.str(it.code) },
            "orderConflictId" to node.orderConflictId?.let { JValues.str(it) },
            "items" to JValues.arr(node.items.map { resultView(it) }),
            "sources" to JValues.arr(node.sources.map { sourceView(it) }),
        )
        is RNode.RConflictRef -> JValues.obj(
            "kind" to JValues.str("conflict"),
            "path" to JValues.str(node.path.toString()),
            "conflictId" to JValues.str(node.conflictId),
            "sources" to JValues.arr(node.sources.map { sourceView(it) }),
        )
    }

    private fun sourceView(s: SourceRecord): JValue = JValues.obj(
        "side" to JValues.str(s.side.name),
        "sideLabel" to JValues.str(s.side.label),
        "present" to JValue.JBool(s.present),
        "equal" to JValue.JBool(s.equal),
        "origin" to s.origin?.let { NodeJson.originJson(it) },
    )

    fun conflictView(c: Conflict): JValue {
        return JValues.obj(
            "id" to JValues.str(c.id),
            "type" to JValues.str(c.type.name),
            "path" to JValues.str(c.path.toString()),
            "message" to JValues.str(c.message),
            "resolved" to JValue.JBool(c.resolved),
            "resolution" to c.resolution?.let { resolutionView(it) },
            "suggestion" to c.suggested?.let { decisionToJson(it) },
            "base" to c.base?.let { NodeJson.encode(it) },
            "a" to c.a?.let { NodeJson.encode(it) },
            "b" to c.b?.let { NodeJson.encode(it) },
            "detail" to detailView(c.detail),
        )
    }

    private fun detailView(d: ConflictDetail): JValue = when (d) {
        ConflictDetail.None -> JValue.JNull
        is ConflictDetail.SeqHunk -> JValues.obj(
            "kind" to JValues.str("seqHunk"),
            "base" to JValues.arr(d.base.map { NodeJson.encode(it) }),
            "a" to JValues.arr(d.a.map { NodeJson.encode(it) }),
            "b" to JValues.arr(d.b.map { NodeJson.encode(it) }),
        )
        is ConflictDetail.Order -> JValues.obj(
            "kind" to JValues.str("order"),
            "base" to JValues.arr(d.baseOrder.map { JValues.str(it) }),
            "a" to JValues.arr(d.aOrder.map { JValues.str(it) }),
            "b" to JValues.arr(d.bOrder.map { JValues.str(it) }),
        )
        is ConflictDetail.DupId -> JValues.obj(
            "kind" to JValues.str("dupId"),
            "side" to JValues.str(d.side.name),
            "id" to JValues.str(d.id),
            "count" to JValues.num(d.count),
        )
        is ConflictDetail.MissingPolicy -> JValues.obj(
            "kind" to JValues.str("missingPolicy"),
            "pathKey" to JValues.str(d.pathKey),
        )
    }

    private fun resolutionView(r: Resolution): JValue = when (r) {
        is Resolution.Take -> JValues.obj("kind" to JValues.str("take"), "side" to JValues.str(r.side.name))
        is Resolution.SetValue -> JValues.obj("kind" to JValues.str("set"), "node" to NodeJson.encode(r.value))
        Resolution.Delete -> JValues.obj("kind" to JValues.str("delete"))
        is Resolution.TakeHunk -> JValues.obj("kind" to JValues.str("takeHunk"), "side" to JValues.str(r.side.name))
        is Resolution.CustomHunk -> JValues.obj(
            "kind" to JValues.str("customHunk"),
            "nodes" to JValues.arr(r.nodes.map { NodeJson.encode(it) }),
        )
        is Resolution.TakeOrder -> JValues.obj("kind" to JValues.str("takeOrder"), "side" to JValues.str(r.side.name))
        is Resolution.CustomOrder -> JValues.obj(
            "kind" to JValues.str("customOrder"),
            "ids" to JValues.arr(r.ids.map { JValues.str(it) }),
        )
        is Resolution.ChoosePolicy -> JValues.obj(
            "kind" to JValues.str("choosePolicy"),
            "policy" to JValues.str(r.policy.code),
            "idKey" to r.idKey?.let { JValues.str(it) },
        )
    }
}
