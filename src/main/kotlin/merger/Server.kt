package merger

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class WebApp(private val service: MergeService, private val host: String, private val port: Int) {

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/") { ex ->
            try {
                route(ex)
            } catch (e: OptimisticLockException) {
                sendJson(ex, 409, mapOf("error" to "version-conflict", "currentVersion" to e.currentVersion))
            } catch (e: Exception) {
                sendJson(ex, 400, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            } finally {
                ex.close()
            }
        }
        server.start()
        return server
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            method == "GET" && path == "/" -> sendHtml(ex)
            method == "GET" && path == "/api/state" -> sendJson(ex, 200, state())
            method == "POST" && path == "/api/documents" -> {
                val body = bodyJson(ex)
                sendJson(ex, 200, service.addDocument(body.get("name").asText(), body.get("text").asText()))
            }
            method == "POST" && path == "/api/policies" -> {
                val body = bodyJson(ex)
                sendJson(ex, 200, service.setPolicy(body.get("path").asText(), body.get("strategy").asText(), body.get("idKey")?.asText()))
            }
            method == "POST" && path == "/api/policies/delete" -> {
                val body = bodyJson(ex)
                sendJson(ex, 200, service.removePolicy(body.get("path").asText()))
            }
            method == "POST" && path == "/api/merges" -> {
                val body = bodyJson(ex)
                val s = service.createMerge(body.get("baseId").asText(), body.get("leftId").asText(), body.get("rightId").asText())
                sendJson(ex, 200, mapOf("id" to s.id, "version" to s.version))
            }
            method == "GET" && path == "/api/merges/detail" -> {
                val s = service.getMerge(query(ex)["id"] ?: throw IllegalArgumentException("缺少 id"))
                sendJson(ex, 200, NodeJson.mapper.readTree(service.sessionJson(s).toString()))
            }
            method == "POST" && path == "/api/merges/resolve" -> {
                val body = bodyJson(ex)
                val decisions = body.get("decisions").map { Decision(it.get("conflictId").asText(), it.get("choice").asText()) }
                val s = service.resolve(body.get("id").asText(), body.get("expectedVersion").asLong(), decisions)
                sendJson(ex, 200, mapOf("id" to s.id, "version" to s.version, "conflicts" to s.conflicts.size))
            }
            method == "GET" && path == "/api/merges/export" -> {
                val q = query(ex)
                sendText(ex, service.exportConfig(q["id"] ?: "", q["format"] ?: "yaml"), "text/plain; charset=utf-8")
            }
            method == "GET" && path == "/api/merges/bundle" -> {
                sendText(ex, service.exportBundle(query(ex)["id"] ?: ""), "application/json; charset=utf-8")
            }
            method == "POST" && path == "/api/import" -> {
                val body = bodyJson(ex)
                sendJson(ex, 200, service.importBundle(body.get("text").asText()))
            }
            else -> sendJson(ex, 404, mapOf("error" to "not found: $method $path"))
        }
    }

    private fun state(): Map<String, Any?> = mapOf(
        "documents" to service.listDocuments(),
        "policies" to service.policyState(),
        "merges" to service.listMerges(),
        "resolutions" to service.resolutions(),
    )

    private fun bodyJson(ex: HttpExchange): JsonNode =
        NodeJson.mapper.readTree(ex.requestBody.readBytes().toString(Charsets.UTF_8))

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
            val i = it.indexOf('=')
            java.net.URLDecoder.decode(it.substring(0, i), Charsets.UTF_8) to
                java.net.URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
        }

    private fun sendHtml(ex: HttpExchange) {
        val bytes = javaClass.getResourceAsStream("/index.html")!!.readBytes()
        ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun sendText(ex: HttpExchange, text: String, contentType: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun sendJson(ex: HttpExchange, status: Int, body: Any) {
        val bytes = when (body) {
            is String -> body.toByteArray(Charsets.UTF_8)
            else -> NodeJson.mapper.writeValueAsBytes(body)
        }
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
