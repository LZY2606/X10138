package merger

import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object Routes {

    fun route(service: SessionService, method: String, path: String, query: String?, body: JV.Obj): JV {
        val parts = path.removePrefix("/api/").trim('/').split('/').filter { it.isNotEmpty() }
        val params = parseQuery(query)

        return when {
            method == "POST" && parts == listOf("sessions") -> {
                val s = service.create(body.str("title") ?: "未命名合并")
                sessionSummary(s)
            }
            method == "GET" && parts == listOf("sessions") ->
                JV.Arr(service.list().map { sessionSummary(it) })

            method == "GET" && parts.size == 2 && parts[0] == "sessions" -> {
                encodeSession(service.get(parts[1]))
            }
            method == "POST" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "inputs" -> {
                val side = Source.valueOf(params["side"]?.uppercase()
                    ?: throw BadInputException("缺少 side 查询参数"))
                val s = service.setInput(parts[1], side,
                    body.str("raw") ?: throw BadInputException("缺少 raw"),
                    body.str("format")?.let { Format.valueOf(it) })
                encodeSession(s)
            }
            method == "POST" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "strategies" -> {
                val s = service.registerStrategy(parts[1],
                    body.str("path") ?: throw BadInputException("缺少 path"),
                    ArrayStrategy.valueOf(body.str("strategy")
                        ?: throw BadInputException("缺少 strategy")),
                    body.str("idField") ?: "id")
                encodeSession(s)
            }
            method == "DELETE" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "strategies" -> {
                val s = service.removeStrategy(parts[1], params["path"] ?: throw BadInputException("缺少 path"))
                encodeSession(s)
            }
            method == "POST" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "output-format" -> {
                val s = service.setOutputFormat(parts[1], Format.valueOf(body.str("format") ?: "YAML"))
                encodeSession(s)
            }
            method == "POST" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "merge" -> {
                val s = service.get(parts[1]); val o = service.remerge(s); encodeOutcome(s, o)
            }
            method == "POST" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "decisions" -> {
                val items = (body.arr("items") ?: throw BadInputException("缺少 items")).list.map { jv ->
                    val o = jv as JV.Obj
                    SubmitItem(
                        path = o.str("path")!!,
                        choiceId = o.str("choiceId")!!,
                        customText = o.str("customText"),
                        customFormat = o.str("customFormat")?.let { Format.valueOf(it) }
                    )
                }
                val req = SubmitRequest(
                    baseVersion = body.int("baseVersion") ?: throw BadInputException("缺少 baseVersion"),
                    author = body.str("author") ?: "local",
                    items = items
                )
                val r = service.submitDecisions(parts[1], req)
                val s = service.get(parts[1])
                JV.Obj(linkedMapOf(
                    "accepted" to JV.Arr(r.accepted.map { JV.Str(it) }),
                    "newVersion" to JV.Num(BigDecimal.valueOf(r.newVersion.toLong())),
                    "outcome" to encodeOutcome(s, s.lastOutcome!!)
                ))
            }
            method == "GET" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "history" ->
                JV.Arr(service.history(parts[1]).map { SessionCodec.decisionJV(it) })

            method == "GET" && parts.size == 3 && parts[0] == "sessions" && parts[2] == "export" -> {
                val s = service.get(parts[1])
                val o = service.remerge(s)
                if (o.unresolvedCount > 0) throw BadInputException("仍有 ${o.unresolvedCount} 项未解决，完成裁决后才能导出")
                BundleIO.export(s, o)
            }
            method == "POST" && parts == listOf("import") -> {
                val bundle = body
                val (s, o) = BundleIO.import(service, bundle, service.store)
                encodeOutcome(s, o)
            }
            else -> throw BadInputException("未知接口: $method $path")
        }
    }

    fun sessionSummary(s: Session): JV.Obj = JV.Obj(linkedMapOf(
        "id" to JV.Str(s.id),
        "title" to JV.Str(s.title),
        "version" to JV.Num(BigDecimal.valueOf(s.version.toLong())),
        "createdAt" to JV.Str(s.createdAt.toString()),
        "ready" to JV.Bool(s.base != null && s.a != null && s.b != null),
        "hasBase" to JV.Bool(s.base != null),
        "hasA" to JV.Bool(s.a != null),
        "hasB" to JV.Bool(s.b != null)
    ))

    fun encodeSession(s: Session): JV.Obj {
        val base = sessionSummary(s).map
        base["outputFormat"] = JV.Str(s.outputFormat.name)
        base["strategies"] = JV.Arr(s.registry.entries.values.map { e ->
            JV.Obj(linkedMapOf(
                "path" to JV.Str(e.path.render()),
                "strategy" to JV.Str(e.strategy.name),
                "idField" to JV.Str(e.idField)
            ))
        })
        s.base?.let { base["baseFp"] = JV.Str(it.fingerprint) }
        s.a?.let { base["aFp"] = JV.Str(it.fingerprint) }
        s.b?.let { base["bFp"] = JV.Str(it.fingerprint) }
        return JV.Obj(base)
    }

    fun encodeOutcome(s: Session, o: MergeOutcome): JV.Obj {
        val base = s.base!!; val a = s.a!!; val b = s.b!!
        val parsed = mapOf(
            MergeSide.BASE to ConfigParser.parse(base.raw, Source.BASE, base.format),
            MergeSide.A to ConfigParser.parse(a.raw, Source.A, a.format),
            MergeSide.B to ConfigParser.parse(b.raw, Source.B, b.format)
        )
        val anchors = { side: MergeSide, node: SNode? -> parsed.getValue(side).metaOf(node) }
        return JV.Obj(linkedMapOf(
            "session" to encodeSession(s),
            "merge" to ApiCodec.outcomeJV(o, anchors)
        ))
    }

    private fun parseQuery(q: String?): Map<String, String> {
        if (q.isNullOrBlank()) return emptyMap()
        return q.split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") }
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
    }
}
