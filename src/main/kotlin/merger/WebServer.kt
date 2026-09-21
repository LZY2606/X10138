package merger

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class WebServer(
    private val host: String,
    private val port: Int,
    private val store: Store,
    private val webRoot: Path? = null,
) {
    private lateinit var server: HttpServer

    fun start() {
        server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/", ::handleRoot)
        server.executor = Executors.newFixedThreadPool(8)
        server.start()
        println("语义配置合并器已启动: http://$host:${server.address.port}")
    }

    fun stop() = server.stop(0)

    private fun handleRoot(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: BadRequest) {
            sendJson(ex, 400, JValues.obj("error" to JValues.str(e.message ?: "请求错误")))
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(ex, 500, JValues.obj("error" to JValues.str(e.message ?: "服务器内部错误")))
        } finally {
            ex.close()
        }
    }

    private class BadRequest(msg: String) : RuntimeException(msg)

    private fun route(ex: HttpExchange) {
        val method = ex.requestMethod
        val path = ex.requestURI.path
        when {
            method == "GET" && path == "/" -> serveStatic(ex, "index.html", "text/html; charset=utf-8")
            method == "GET" && path.startsWith("/static/") -> serveAsset(ex, path.removePrefix("/static/"))
            method == "GET" && path == "/api/sessions" -> listSessions(ex)
            method == "POST" && path == "/api/sessions" -> createSession(ex)
            method == "GET" && path.startsWith("/api/sessions/") -> getSession(ex, path)
            method == "PUT" && path.contains("/inputs") -> updateInputs(ex, path)
            method == "PUT" && path.contains("/policies") -> updatePolicies(ex, path)
            method == "POST" && path.endsWith("/resolve") -> resolve(ex, path)
            method == "GET" && path.endsWith("/export") -> exportBundle(ex, path)
            method == "POST" && path == "/api/import" -> importBundle(ex)
            else -> sendJson(ex, 404, JValues.obj("error" to JValues.str("未找到: $method $path")))
        }
    }

    private fun requireSession(path: String): MergeSession {
        val id = path.split('/').drop(3).firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw BadRequest("缺少会话 id")
        return store.session(id) ?: throw BadRequest("会话不存在: $id")
    }

    private fun listSessions(ex: HttpExchange) {
        val ids = store.listSessions()
        sendJson(ex, 200, JValues.obj("sessions" to JValues.arr(ids.map { JValues.str(it) })))
    }

    private fun createSession(ex: HttpExchange) {
        val body = readJson(ex)
        val id = (body["id"] as? JValue.JStr)?.value ?: ("s-" + System.currentTimeMillis().toString(36))
        val base = inputDoc(body, "base")
        val a = inputDoc(body, "a")
        val b = inputDoc(body, "b")
        val session = store.create(id, base, a, b)
        store.save(session)
        sendJson(ex, 201, ApiViews.sessionView(session.snapshot()))
    }

    private fun inputDoc(body: JValue, key: String): MergeSession.InputDoc {
        val o = body.asObj()[key]?.asObj() ?: throw BadRequest("缺少输入 $key")
        val text = o["text"]?.asStr() ?: throw BadRequest("缺少 $key.text")
        val format = (o["format"] as? JValue.JStr)?.value?.let { ConfigFormat.valueOf(it) }
            ?: ConfigIO.detectFormat(text, (o["fileName"] as? JValue.JStr)?.value)
        val fileName = o["fileName"]?.asStr() ?: "$key.${if (format == ConfigFormat.YAML) "yaml" else "json"}"
        // 校验解析，早失败
        runCatching { ConfigIO.parse(text, format, fileName) }.onFailure { throw BadRequest(it.message ?: "解析失败") }
        return MergeSession.InputDoc(fileName, format, text)
    }

    private fun getSession(ex: HttpExchange, path: String) {
        if (path.endsWith("/export")) return
        val session = requireSession(path)
        sendJson(ex, 200, ApiViews.sessionView(session.snapshot()))
    }

    private fun updateInputs(ex: HttpExchange, path: String) {
        val session = requireSession(path)
        val body = readJson(ex)
        val base = body.asObj()["base"]?.let { inputDoc(body, "base") }
        val a = body.asObj()["a"]?.let { inputDoc(body, "a") }
        val b = body.asObj()["b"]?.let { inputDoc(body, "b") }
        session.updateInputs(base, a, b)
        store.save(session)
        sendJson(ex, 200, ApiViews.sessionView(session.snapshot()))
    }

    private fun updatePolicies(ex: HttpExchange, path: String) {
        val session = requireSession(path)
        val body = readJson(ex)
        val policies = policiesFromJson(body)
        session.updatePolicies(policies)
        store.save(session)
        sendJson(ex, 200, ApiViews.sessionView(session.snapshot()))
    }

    private fun resolve(ex: HttpExchange, path: String) {
        val session = requireSession(path)
        val body = readJson(ex).asObj()
        val expected = (body["expectedRevision"] as? JValue.JNum)?.value?.toLong()
            ?: throw BadRequest("缺少 expectedRevision")
        val items = body["items"]?.asArr()?.items.orEmpty().map { jv ->
            val o = jv.asObj()
            ResolutionRequest(
                conflictId = o["conflictId"]?.asStr() ?: throw BadRequest("缺少 conflictId"),
                resolution = parseResolution(o["resolution"] ?: throw BadRequest("缺少 resolution")),
                reason = o["reason"]?.strOr("") ?: "",
            )
        }
        when (val result = session.resolve(expected, items)) {
            is MergeSession.ResolutionResult.Ok -> {
                store.save(session)
                sendJson(ex, 200, ApiViews.sessionView(session.snapshot()))
            }
            is MergeSession.ResolutionResult.Conflict -> sendJson(
                ex, 409,
                JValues.obj(
                    "error" to JValues.str("版本已过期或冲突已被其他人解决，请刷新后重试"),
                    "code" to JValues.str("revision_conflict"),
                    "currentRevision" to JValues.num(result.currentRevision),
                    "alreadyResolved" to JValues.arr(result.alreadyResolved.map { JValues.str(it) }),
                ),
            )
            is MergeSession.ResolutionResult.NotFound -> sendJson(
                ex, 404,
                JValues.obj("error" to JValues.str("冲突不存在: ${result.conflictId}")),
            )
        }
    }

    private fun parseResolution(v: JValue): Resolution {
        val o = v.asObj()
        return when (val kind = o["kind"]!!.asStr()) {
            "take" -> Resolution.Take(Side.valueOf(o["side"]!!.asStr()))
            "set" -> Resolution.SetValue(NodeJson.decode(o["node"]!!))
            "delete" -> Resolution.Delete
            "takeHunk" -> Resolution.TakeHunk(Side.valueOf(o["side"]!!.asStr()))
            "customHunk" -> Resolution.CustomHunk(o["nodes"]!!.asArr().items.map { NodeJson.decode(it) })
            "takeOrder" -> Resolution.TakeOrder(Side.valueOf(o["side"]!!.asStr()))
            "customOrder" -> Resolution.CustomOrder(o["ids"]!!.asArr().items.map { it.asStr() })
            "choosePolicy" -> Resolution.ChoosePolicy(
                ArrayPolicy.of(o["policy"]!!.asStr())!!,
                (o["idKey"] as? JValue.JStr)?.value,
            )
            else -> throw BadRequest("未知裁决类型: $kind")
        }
    }

    private fun exportBundle(ex: HttpExchange, path: String) {
        val session = requireSession(path)
        val format = ex.requestURI.query?.split("&")?.mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv[0] == "format") kv.getOrNull(1) else null
        }?.firstOrNull()?.let { runCatching { ConfigFormat.valueOf(it.uppercase()) }.getOrNull() }
            ?: ConfigFormat.YAML
        val snap = session.snapshot()
        val doc = snap.document
        val unresolved = doc.conflicts.filter { !it.resolved }
        if (unresolved.isNotEmpty()) {
            sendJson(ex, 409, JValues.obj(
                "error" to JValues.str("仍有 ${unresolved.size} 个冲突未解决，无法导出"),
                "unresolved" to JValues.arr(unresolved.map { JValues.str(it.id) }),
            ))
            return
        }
        val output = ResultExport.render(doc, format)
        val resultFp = ResultExport.resultFingerprint(doc)
        val bundle = JValues.obj(
            "bundleVersion" to JValues.num(1),
            "exportedAt" to JValues.str(java.time.Instant.now().toString()),
            "sessionId" to JValues.str(snap.id),
            "format" to JValues.str(format.name),
            "resultFingerprint" to JValues.str(resultFp),
            "inputs" to JValues.obj(
                "base" to bundleInput(snap.baseInput, doc.fingerprints.first),
                "a" to bundleInput(snap.aInput, doc.fingerprints.second),
                "b" to bundleInput(snap.bInput, doc.fingerprints.third),
            ),
            "policies" to policiesToJson(snap.policies),
            "decisions" to JValues.arr(snap.decisions.map { decisionToJson(it) }),
            "output" to JValues.obj(
                "format" to JValues.str(format.name),
                "text" to JValues.str(output),
            ),
            "sources" to JValues.str(JsonCodec.encode(sourceDump(doc))),
        )
        sendJson(ex, 200, bundle)
    }

    private fun sourceDump(doc: MergeDocument): JValue =
        ApiViews.sessionSources(doc)

    private fun bundleInput(d: MergeSession.InputDoc, fingerprint: String): JValue = JValues.obj(
        "fileName" to JValues.str(d.fileName),
        "format" to JValues.str(d.format.name),
        "text" to JValues.str(d.text),
        "fingerprint" to JValues.str(fingerprint),
    )

    /**
     * 导入导出包：重新解析输入，回放指纹匹配的裁决，验证路径/来源链/结果指纹一致。
     */
    private fun importBundle(ex: HttpExchange) {
        val body = readJson(ex).asObj()
        val bundleVersion = (body["bundleVersion"] as? JValue.JNum)?.value?.toInt() ?: 1
        require(bundleVersion == 1) { "不支持的导出包版本" }
        val sessionId = (body["sessionId"] as? JValue.JStr)?.value
            ?: ("imp-" + System.currentTimeMillis().toString(36))
        val inputs = body["inputs"]!!.asObj()
        fun part(key: String): MergeSession.InputDoc {
            val p = inputs[key]!!.asObj()
            return MergeSession.InputDoc(
                p["fileName"]!!.asStr(),
                ConfigFormat.valueOf(p["format"]!!.asStr()),
                p["text"]!!.asStr(),
            )
        }
        // 指纹校验：重新解析得到的指纹必须与包中记录一致
        val expected = listOf("base", "a", "b").map { key ->
            val p = inputs[key]!!.asObj()
            val doc = part(key)
            val actual = Fingerprints.of(doc.parse())
            val recorded = p["fingerprint"]!!.asStr()
            if (actual != recorded) throw BadRequest("$key 指纹与导出包不一致（内容已被改动）")
            recorded
        }
        val session = store.create(sessionId, part("base"), part("a"), part("b"))
        body["policies"]?.let { session.updatePolicies(policiesFromJson(it)) }
        body["decisions"]?.asArr()?.items?.forEach { session.injectLoaded(decisionFromJson(it)) }
        session.recomputeAfterLoad()
        store.save(session)

        val snap = session.snapshot()
        val doc = snap.document
        val output = body["output"]!!.asObj()
        val format = ConfigFormat.valueOf(output["format"]!!.asStr())
        val unresolved = doc.conflicts.filter { !it.resolved }
        val reRendered = if (unresolved.isEmpty()) ResultExport.render(doc, format) else null
        val resultFpMatches = if (unresolved.isEmpty()) {
            ResultExport.resultFingerprint(doc) == body["resultFingerprint"]!!.asStr()
        } else false
        val outputMatches = reRendered != null && reRendered == output["text"]!!.asStr()

        sendJson(ex, 200, JValues.obj(
            "sessionId" to JValues.str(sessionId),
            "inputFingerprintsMatch" to JValue.JBool(true),
            "resultFingerprint" to if (unresolved.isEmpty()) JValues.str(ResultExport.resultFingerprint(doc)) else JValue.JNull,
            "resultFingerprintMatches" to JValue.JBool(resultFpMatches),
            "outputTextMatches" to JValue.JBool(outputMatches),
            "unresolvedCount" to JValues.num(unresolved.size),
            "session" to ApiViews.sessionView(snap),
        ))
    }

    private fun readJson(ex: HttpExchange): JValue {
        val text = String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)
        if (text.isBlank()) return JValue.JObj()
        return runCatching { JsonCodec.decode(text) }.getOrElse { throw BadRequest("非法 JSON: ${it.message}") }
    }

    private fun sendJson(ex: HttpExchange, status: Int, value: JValue) {
        val bytes = JsonCodec.encode(value).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveStatic(ex: HttpExchange, name: String, contentType: String) {
        val bytes = ResourceLoader.webResource(name)
            ?: return sendJson(ex, 404, JValues.obj("error" to JValues.str("资源不存在: $name")))
        ex.responseHeaders.set("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveAsset(ex: HttpExchange, name: String) {
        val safe = name.substringBefore("?").replace("..", "")
        val type = when {
            safe.endsWith(".css") -> "text/css; charset=utf-8"
            safe.endsWith(".js") -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
        serveStatic(ex, safe, type)
    }
}

object ResourceLoader {
    fun webResource(name: String): ByteArray? {
        val stream = javaClass.classLoader.getResourceAsStream("web/$name") ?: return null
        return stream.use { it.readAllBytes() }
    }
}
