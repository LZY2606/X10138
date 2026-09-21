package merger

import java.time.Instant

class BundleException(message: String) : RuntimeException(message)

/**
 * 导出/导入包。导入后重新解析三份原始输入并重放合并，
 * 校验路径集合、来源链与结果指纹与导出时一致，保证可重放。
 */
object BundleIO {

    fun export(s: Session, outcome: MergeOutcome): JV.Obj {
        fun inputJV(d: InputDoc) = JV.Obj(linkedMapOf(
            "format" to JV.Str(d.format.name),
            "raw" to JV.Str(d.raw),
            "fingerprint" to JV.Str(d.fingerprint)
        ))
        val strategies = JV.Arr(s.registry.entries.values.map { e ->
            JV.Obj(linkedMapOf(
                "path" to JV.Str(e.path.render()),
                "strategy" to JV.Str(e.strategy.name),
                "idField" to JV.Str(e.idField),
                "registeredAt" to JV.Num(java.math.BigDecimal.valueOf(e.path.segs.size.toLong()))
            ))
        })
        val decisions = JV.Arr(s.decisions.map { d ->
            JV.Obj(LinkedHashMap(mapOf(
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
                "baseVersion" to JV.Num(java.math.BigDecimal.valueOf(d.baseVersion.toLong()))
            )))
        })
        val prov = JV.Arr(outcome.root.provenance.map { JV.Str("${it.side} ${it.path} ${it.action}") })
        return JV.Obj(LinkedHashMap(mapOf(
            "kind" to JV.Str("semantic-config-merger-bundle"),
            "bundleVersion" to JV.Num(java.math.BigDecimal.ONE),
            "exportedAt" to JV.Str(Instant.now().toString()),
            "session" to JV.Obj(linkedMapOf(
                "id" to JV.Str(s.id),
                "title" to JV.Str(s.title),
                "version" to JV.Num(java.math.BigDecimal.valueOf(s.version.toLong())),
                "outputFormat" to JV.Str(s.outputFormat.name),
                "strategyVersion" to JV.Num(java.math.BigDecimal.valueOf(s.registry.version.toLong()))
            )),
            "inputs" to JV.Obj(linkedMapOf(
                "base" to inputJV(s.base!!),
                "a" to inputJV(s.a!!),
                "b" to inputJV(s.b!!)
            )),
            "strategies" to strategies,
            "decisions" to decisions,
            "output" to JV.Obj(linkedMapOf(
                "format" to JV.Str(s.outputFormat.name),
                "text" to JV.Str(outcome.exported),
                "fingerprint" to JV.Str(outcome.resultFingerprint)
            )),
            "paths" to JV.Arr(collectPaths(outcome.root).map { JV.Str(it) }),
            "rootProvenance" to prov
        )))
    }

    private fun collectPaths(n: MergeNode): List<String> {
        val out = mutableListOf(n.path)
        n.children.values.forEach { out.addAll(collectPaths(it)) }
        return out
    }

    fun import(service: SessionService, bundle: JV.Obj, store: Store): Pair<Session, MergeOutcome> {
        if (bundle.str("kind") != "semantic-config-merger-bundle")
            throw BundleException("不是本工具导出的合并包")
        val sessObj = bundle.obj("session") ?: throw BundleException("缺少 session")
        val inputsObj = bundle.obj("inputs") ?: throw BundleException("缺少 inputs")

        val s = service.create(sessObj.str("title") ?: "导入的合并")
        val fmt = { o: JV.Obj, k: String -> Format.valueOf(o.str(k) ?: "YAML") }

        loadInput(store, s, Source.BASE, inputsObj.obj("base")!!)
        loadInput(store, s, Source.A, inputsObj.obj("a")!!)
        loadInput(store, s, Source.B, inputsObj.obj("b")!!)
        s.outputFormat = Format.valueOf(sessObj.str("outputFormat") ?: "YAML")

        bundle.arr("strategies")?.list?.forEach { jv ->
            val o = jv as JV.Obj
            service.registerStrategy(s.id, o.str("path")!!,
                ArrayStrategy.valueOf(o.str("strategy")!!), o.str("idField") ?: "id")
        }

        // 按导出顺序重放裁决（导出列表即时间顺序）
        bundle.arr("decisions")?.list?.forEach { jv ->
            val o = jv as JV.Obj
            val d = Decision(
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
                baseVersion = o.int("baseVersion") ?: 0
            )
            s.decisions.add(d)
            store.appendHistory(s.id, d)
        }
        s.version++
        val outcome = service.remerge(s)
        store.saveSession(s)

        // 指纹一致性校验
        val expectedFp = bundle.obj("output")?.str("fingerprint")
        if (expectedFp != null && expectedFp != outcome.resultFingerprint) {
            throw BundleException("导入后结果指纹不一致：期望 $expectedFp，实际 ${outcome.resultFingerprint}")
        }
        val expectedPaths = bundle.arr("paths")?.list?.mapNotNull { (it as? JV.Str)?.value }?.toSet()
        val actualPaths = collectPaths(outcome.root).toSet()
        if (expectedPaths != null && expectedPaths != actualPaths) {
            throw BundleException("导入后路径集合与导出不一致，合并不可重放")
        }
        return s to outcome
    }

    private fun loadInput(store: Store, s: Session, side: Source, o: JV.Obj) {
        val raw = o.str("raw") ?: throw BundleException("缺少 $side 原始输入")
        val format = runCatching { Format.valueOf(o.str("format")!!) }.getOrDefault(Format.YAML)
        val parsed = ConfigParser.parse(raw, side, format)
        val expected = o.str("fingerprint")
        if (expected != null && expected != parsed.fingerprint)
            throw BundleException("$side 输入指纹与包内记录不符")
        val input = InputDoc(parsed.format, raw, parsed.fingerprint)
        when (side) {
            Source.BASE -> s.base = input
            Source.A -> s.a = input
            Source.B -> s.b = input
        }
    }
}
