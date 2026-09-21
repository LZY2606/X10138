package merger

/** Node（解析模型）<-> JValue，用于解析模型持久化与 API。 */
object NodeJson {
    fun encode(node: Node): JValue = when (node) {
        is Node.Scalar -> JValues.obj(
            "kind" to JValues.str("scalar"),
            "value" to scalarJson(node.value),
            "origin" to originJson(node.origin),
        )
        is Node.Obj -> JValues.obj(
            "kind" to JValues.str("obj"),
            "children" to JValue.JObj(LinkedHashMap(node.children.mapValues { encode(it.value) })),
            "origin" to originJson(node.origin),
        )
        is Node.Arr -> JValues.obj(
            "kind" to JValues.str("arr"),
            "items" to JValues.arr(node.items.map { encode(it) }),
            "origin" to originJson(node.origin),
        )
    }

    fun decode(v: JValue): Node {
        val o = v.asObj()
        return when (o["kind"]!!.asStr()) {
            "scalar" -> Node.Scalar(scalarFromJson(o["value"]!!), originFromJson(o["origin"]))
            "obj" -> Node.Obj(
                LinkedHashMap(o["children"]!!.asObj().map.mapValues { decode(it.value) }),
                originFromJson(o["origin"]),
            )
            "arr" -> Node.Arr(o["items"]!!.asArr().items.map { decode(it) }.toMutableList(), originFromJson(o["origin"]))
            else -> error("未知节点类型")
        }
    }

    fun scalarJson(v: ScalarValue): JValue = when (v) {
        is ScalarValue.StrVal -> JValues.obj("type" to JValues.str("string"), "v" to JValues.str(v.value))
        is ScalarValue.BoolVal -> JValues.obj("type" to JValues.str("bool"), "v" to JValue.JBool(v.value))
        is ScalarValue.NumVal -> JValues.obj("type" to JValues.str("number"), "v" to JValues.str(JsonWriter.normalizeNumber(v)))
        ScalarValue.NullVal -> JValues.obj("type" to JValues.str("null"))
    }

    fun scalarFromJson(v: JValue): ScalarValue {
        val o = v.asObj()
        return when (o["type"]!!.asStr()) {
            "string" -> ScalarValue.StrVal(o["v"]!!.asStr())
            "bool" -> ScalarValue.BoolVal(o["v"]!!.asBool())
            "number" -> ScalarValue.NumVal(java.math.BigDecimal(o["v"]!!.asStr()))
            "null" -> ScalarValue.NullVal
            else -> error("未知标量类型")
        }
    }

    fun originJson(o: Origin): JValue = JValues.obj(
        "side" to JValues.str(o.side.name),
        "file" to JValues.str(o.file),
        "line" to JValues.num(o.line),
        "column" to JValues.num(o.column),
    )

    fun originFromJson(v: JValue?): Origin {
        val o = v?.objOrNull() ?: return Origin.SYNTHETIC
        return Origin(
            Side.parse(o["side"]?.asStr() ?: "BASE") ?: Side.BASE,
            o["file"]?.asStr() ?: "<未知>",
            (o["line"] as? JValue.JNum)?.value?.toIntOrNull() ?: 0,
            (o["column"] as? JValue.JNum)?.value?.toIntOrNull() ?: 0,
        )
    }
}

fun nodeToJson(node: Node): JValue = NodeJson.encode(node)
fun nodeFromJson(v: JValue): Node = NodeJson.decode(v)

fun originJson(o: Origin): JValue = NodeJson.originJson(o)

fun policiesToJson(p: PolicyRegistry): JValue = JValues.obj(
    "version" to JValues.num(p.version),
    "arrayPolicies" to JValue.JObj(LinkedHashMap(p.arrayPolicies.mapValues { JValues.str(it.value.code) })),
    "idField" to JValue.JObj(LinkedHashMap(p.idField.mapValues { JValues.str(it.value) })),
)

fun policiesFromJson(v: JValue): PolicyRegistry {
    val o = v.asObj()
    val version = (o["version"] as? JValue.JNum)?.value?.toIntOrNull() ?: 1
    val arrayPolicies = o["arrayPolicies"]?.asObj()?.map?.mapValues {
        ArrayPolicy.of(it.value.asStr()) ?: error("未知策略")
    }.orEmpty().toMap(LinkedHashMap())
    val idField = o["idField"]?.asObj()?.map?.mapValues { it.value.asStr() }.orEmpty().toMap(LinkedHashMap())
    return PolicyRegistry(version, arrayPolicies, idField)
}

fun decisionToJson(d: StoredDecision): JValue {
    val (type, payload) = when (val p = d.resolution) {
        is DecisionPayload.TakeSide -> "take" to JValues.obj("side" to JValues.str(p.side))
        is DecisionPayload.SetJson -> "set" to JValues.obj("text" to JValues.str(p.text), "format" to JValues.str(p.format.name))
        DecisionPayload.Delete -> "delete" to JValue.JObj()
        is DecisionPayload.TakeHunk -> "takeHunk" to JValues.obj("side" to JValues.str(p.side))
        is DecisionPayload.CustomHunk -> "customHunk" to JValues.obj("text" to JValues.str(p.text), "format" to JValues.str(p.format.name))
        is DecisionPayload.TakeOrder -> "takeOrder" to JValues.obj("side" to JValues.str(p.side))
        is DecisionPayload.CustomOrder -> "customOrder" to JValues.obj("ids" to JValues.arr(p.ids.map { JValues.str(it) }))
        is DecisionPayload.ChoosePolicy -> "choosePolicy" to JValues.obj(
            "policy" to JValues.str(p.policy),
            "idKey" to p.idKey?.let { JValues.str(it) },
        )
    }
    return JValues.obj(
        "id" to JValues.str(d.id),
        "conflictType" to JValues.str(d.conflictType.name),
        "path" to JValues.str(d.path),
        "baseFingerprint" to JValues.str(d.baseFingerprint),
        "aFingerprint" to JValues.str(d.aFingerprint),
        "bFingerprint" to JValues.str(d.bFingerprint),
        "policyVersion" to JValues.num(d.policyVersion),
        "resolutionType" to JValues.str(type),
        "resolution" to payload,
        "reason" to JValues.str(d.reason),
        "createdAt" to JValues.str(d.createdAt),
    )
}

fun decisionFromJson(v: JValue): StoredDecision {
    val o = v.asObj()
    val payload = when (val t = o["resolutionType"]!!.asStr()) {
        "take" -> DecisionPayload.TakeSide(o["resolution"]!!.asObj()["side"]!!.asStr())
        "set" -> {
            val r = o["resolution"]!!.asObj()
            DecisionPayload.SetJson(r["text"]!!.asStr(), ConfigFormat.valueOf(r["format"]!!.asStr()))
        }
        "delete" -> DecisionPayload.Delete
        "takeHunk" -> DecisionPayload.TakeHunk(o["resolution"]!!.asObj()["side"]!!.asStr())
        "customHunk" -> {
            val r = o["resolution"]!!.asObj()
            DecisionPayload.CustomHunk(r["text"]!!.asStr(), ConfigFormat.valueOf(r["format"]!!.asStr()))
        }
        "takeOrder" -> DecisionPayload.TakeOrder(o["resolution"]!!.asObj()["side"]!!.asStr())
        "customOrder" -> DecisionPayload.CustomOrder(o["resolution"]!!.asObj()["ids"]!!.asArr().items.map { it.asStr() })
        "choosePolicy" -> {
            val r = o["resolution"]!!.asObj()
            DecisionPayload.ChoosePolicy(r["policy"]!!.asStr(), (r["idKey"] as? JValue.JStr)?.value)
        }
        else -> error("未知裁决类型: $t")
    }
    return StoredDecision(
        id = o["id"]!!.asStr(),
        conflictType = ConflictType.valueOf(o["conflictType"]!!.asStr()),
        path = o["path"]!!.asStr(),
        baseFingerprint = o["baseFingerprint"]!!.asStr(),
        aFingerprint = o["aFingerprint"]!!.asStr(),
        bFingerprint = o["bFingerprint"]!!.asStr(),
        policyVersion = (o["policyVersion"] as JValue.JNum).value.toInt(),
        resolution = payload,
        reason = o["reason"]?.strOr("") ?: "",
        createdAt = o["createdAt"]!!.asStr(),
    )
}
