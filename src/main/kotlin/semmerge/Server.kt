package semmerge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class Server(private val store: Store, host: String, private val port: Int) {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val http: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        http.createContext("/") { ex -> route(ex) }
        http.executor = Executors.newFixedThreadPool(8)
    }

    fun start() { http.start() }

    private fun route(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val m = ex.requestMethod
            when {
                path == "/" && m == "GET" -> sendHtml(ex, INDEX_HTML)
                path == "/api/inputs" && m == "GET" -> sendJson(ex, store.inputs.values.map {
                    mapOf("id" to it.id, "name" to it.name, "format" to it.format, "fingerprint" to it.fingerprint)
                })
                path == "/api/inputs" && m == "POST" -> {
                    val body = readJson(ex)
                    val doc = store.addInput(
                        body["name"] as String,
                        body["text"] as String,
                        body["format"] as? String
                    )
                    sendJson(ex, mapOf("id" to doc.id, "fingerprint" to doc.fingerprint))
                }
                path == "/api/policies" && m == "GET" -> sendJson(ex, mapOf(
                    "version" to store.policies.version,
                    "rules" to store.policies.rules.map { (p, r) ->
                        mapOf("path" to p, "policy" to r.policy.name, "idKey" to r.idKey)
                    }
                ))
                path == "/api/policies" && m == "PUT" -> {
                    val body = readJson(ex)
                    val rules = (body["rules"] as List<Map<String, Any?>>).associate {
                        (it["path"] as String) to ArrayRule(
                            ArrayPolicy.valueOf(it["policy"] as String),
                            it["idKey"] as? String
                        )
                    }
                    store.updatePolicies(PolicySet(store.policies.version + 1, rules))
                    sendJson(ex, mapOf("ok" to true, "version" to store.policies.version))
                }
                path == "/api/merge" && m == "POST" -> {
                    val body = readJson(ex)
                    val s = store.createSession(
                        body["baseId"] as String, body["oursId"] as String, body["theirsId"] as String
                    )
                    sendJson(ex, sessionView(s))
                }
                path.matches(Regex("/api/sessions/[^/]+")) && m == "GET" -> {
                    val s = store.sessions[path.substringAfterLast("/")]
                        ?: return sendError(ex, 404, "会话不存在")
                    sendJson(ex, sessionView(s))
                }
                path.matches(Regex("/api/sessions/[^/]+/resolutions")) && m == "POST" -> {
                    val id = path.split("/")[3]
                    val body = readJson(ex)
                    val expected = (body["expectedVersion"] as Number).toInt()
                    val res = (body["resolutions"] as List<Map<String, Any?>>).map {
                        Resolution(
                            it["conflictId"] as String,
                            it["path"] as? String ?: "",
                            it["choice"] as String,
                            it["customText"] as? String
                        )
                    }
                    try {
                        val result = store.submitResolutions(id, expected, res)
                        sendJson(ex, mergeView(result, store.sessions[id]!!.version))
                    } catch (e: ConflictStaleException) {
                        sendError(ex, 409, "版本已过期（当前 ${e.currentVersion}），请刷新后重试")
                    }
                }
                path.matches(Regex("/api/sessions/[^/]+/export")) && m == "GET" -> {
                    val id = path.split("/")[3]
                    val format = ex.requestURI.query?.substringAfter("format=")?.substringBefore("&") ?: "yaml"
                    try {
                        val (text, fp) = store.exportSession(id, format)
                        sendJson(ex, mapOf("text" to text, "fingerprint" to fp, "format" to format))
                    } catch (e: IllegalArgumentException) {
                        sendError(ex, 400, e.message ?: "导出失败")
                    }
                }
                path == "/api/import" && m == "POST" -> {
                    val body = readJson(ex)
                    @Suppress("UNCHECKED_CAST")
                    sendJson(ex, store.importExport(body["manifest"] as Map<String, Any?>))
                }
                path == "/api/decisions" && m == "GET" -> sendJson(ex, store.decisionHistory)
                else -> sendError(ex, 404, "未找到: $path")
            }
        } catch (e: Exception) {
            sendError(ex, 500, e.message ?: e.javaClass.simpleName)
        } finally {
            ex.close()
        }
    }

    private fun sessionView(s: MergeSession): Map<String, Any?> {
        val result = store.runMerge(s)
        return mapOf(
            "session" to mapOf(
                "id" to s.id, "version" to s.version, "policyVersion" to s.policyVersion,
                "baseId" to s.baseId, "oursId" to s.oursId, "theirsId" to s.theirsId
            ),
            "merge" to mergeView(result, s.version)
        )
    }

    private fun mergeView(r: MergeResult, version: Int): Map<String, Any?> = mapOf(
        "version" to version,
        "merged" to nodeToJson(r.merged),
        "autoMerged" to r.autoMerged.map {
            mapOf("path" to it.path, "description" to it.description, "prov" to provJson(it.prov))
        },
        "conflicts" to r.conflicts.map { conflictView(it) },
        "applied" to r.applied.map { mapOf("path" to it.path, "choice" to it.choice) },
        "suggestions" to r.suggestions.map {
            mapOf("path" to it.path, "note" to it.note, "choice" to it.decision.choice)
        }
    )

    private fun conflictView(c: Conflict) = mapOf(
        "id" to c.id, "path" to c.path, "reason" to c.reason,
        "baseFp" to c.baseFp, "oursFp" to c.oursFp, "theirsFp" to c.theirsFp,
        "base" to nodeToJson(c.base), "ours" to nodeToJson(c.ours), "theirs" to nodeToJson(c.theirs)
    )

    private fun readJson(ex: HttpExchange): Map<String, Any?> {
        val text = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(text, Map::class.java) as Map<String, Any?>
    }

    private fun sendJson(ex: HttpExchange, any: Any, code: Int = 200) {
        val bytes = mapper.writeValueAsBytes(any)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun sendHtml(ex: HttpExchange, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun sendError(ex: HttpExchange, code: Int, msg: String) {
        sendJson(ex, mapOf("error" to msg), code)
    }

    companion object {
        val INDEX_HTML: String by lazy {
            val res = Server::class.java.getResource("/web/index.html")
            res?.readText() ?: File("src/main/resources/web/index.html").readText()
        }
    }
}
