package merger

import java.math.BigDecimal
import java.time.Instant

object SessionCodec {

    fun encode(s: Session): JV.Obj = JV.Obj(LinkedHashMap(mapOf(
        "id" to JV.Str(s.id),
        "createdAt" to JV.Str(s.createdAt.toString()),
        "title" to JV.Str(s.title),
        "base" to inputJV(s.base),
        "a" to inputJV(s.a),
        "b" to inputJV(s.b),
        "outputFormat" to JV.Str(s.outputFormat.name),
        "version" to JV.Num(BigDecimal.valueOf(s.version.toLong())),
        "registry" to JV.Obj(linkedMapOf(
            "version" to JV.Num(BigDecimal.valueOf(s.registry.version.toLong())),
            "entries" to JV.Arr(s.registry.entries.values.map { e ->
                JV.Obj(linkedMapOf(
                    "path" to JV.Str(e.path.render()),
                    "strategy" to JV.Str(e.strategy.name),
                    "idField" to JV.Str(e.idField)
                ))
            })
        )),
        "decisions" to JV.Arr(s.decisions.map { decisionJV(it) }),
        "lastResultFingerprint" to (s.lastOutcome?.resultFingerprint?.let { JV.Str(it) } ?: JV.Null)
    )))

    fun inputJV(d: InputDoc?): JV = if (d == null) JV.Null else JV.Obj(linkedMapOf(
        "format" to JV.Str(d.format.name),
        "raw" to JV.Str(d.raw),
        "fingerprint" to JV.Str(d.fingerprint)
    ))

    private fun inputFrom(jv: JV?): InputDoc? {
        val o = jv as? JV.Obj ?: return null
        val raw = o.str("raw") ?: return null
        val format = runCatching { Format.valueOf(o.str("format") ?: "YAML") }.getOrDefault(Format.YAML)
        return InputDoc(format, raw, o.str("fingerprint") ?: ConfigParser.parse(raw, Source.BASE, format).fingerprint)
    }

    fun decisionJV(d: Decision): JV.Obj = JV.Obj(LinkedHashMap(mapOf(
        "id" to JV.Str(d.id),
        "path" to JV.Str(d.path),
        "choiceId" to JV.Str(d.choiceId),
        "customText" to (d.customText?.let { JV.Str(it) } ?: JV.Null),
        "customFormat" to JV.Str(d.customFormat.name),
        "baseFingerprint" to JV.Str(d.baseFingerprint),
        "aFingerprint" to JV.Str(d.aFingerprint),
        "bFingerprint" to JV.Str(d.bFingerprint),
        "conflictKind" to JV.Str(d.conflictKind.name),
        "createdAt" to JV.Str(d.createdAt.toString()),
        "author" to JV.Str(d.author),
        "baseVersion" to JV.Num(BigDecimal.valueOf(d.baseVersion.toLong())),
        "detail" to (d.detail?.let { JV.Str(it) } ?: JV.Null)
    )))

    private fun decisionFrom(jv: JV): Decision {
        val o = jv as JV.Obj
        return Decision(
            id = o.str("id")!!,
            path = o.str("path")!!,
            choiceId = o.str("choiceId")!!,
            customText = o.str("customText"),
            customFormat = runCatching { Format.valueOf(o.str("customFormat") ?: "YAML") }.getOrDefault(Format.YAML),
            baseFingerprint = o.str("baseFingerprint")!!,
            aFingerprint = o.str("aFingerprint")!!,
            bFingerprint = o.str("bFingerprint")!!,
            conflictKind = ConflictKind.valueOf(o.str("conflictKind")!!),
            createdAt = Instant.parse(o.str("createdAt")),
            author = o.str("author") ?: "local",
            baseVersion = o.int("baseVersion") ?: 0,
            detail = o.str("detail")
        )
    }

    fun decode(jv: JV): Session {
        val o = jv as JV.Obj
        val reg = o.obj("registry")
        val entries = LinkedHashMap<String, StrategyEntry>()
        reg?.arr("entries")?.list?.forEach { e ->
            val eo = e as JV.Obj
            val p = Path.parse(eo.str("path")!!)
            entries[p.render()] = StrategyEntry(p,
                ArrayStrategy.valueOf(eo.str("strategy")!!), eo.str("idField") ?: "id")
        }
        val registry = StrategyRegistry(entries, reg?.int("version") ?: 0)
        val decisions = o.arr("decisions")?.list?.map { decisionFrom(it) }?.toMutableList() ?: mutableListOf()
        return Session(
            id = o.str("id")!!,
            createdAt = Instant.parse(o.str("createdAt")),
            title = o.str("title") ?: "未命名合并",
            base = inputFrom(o["base"]),
            a = inputFrom(o["a"]),
            b = inputFrom(o["b"]),
            registry = registry,
            outputFormat = runCatching { Format.valueOf(o.str("outputFormat") ?: "YAML") }.getOrDefault(Format.YAML),
            version = o.int("version") ?: 0,
            decisions = decisions
        )
    }
}
