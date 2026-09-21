package merger

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

val json: ObjectMapper = jacksonObjectMapper()

fun nodeView(n: Node?, path: String, key: String?): Map<String, Any?> {
    if (n == null) return mapOf("type" to "absent", "key" to key, "path" to path)
    val view = mutableMapOf<String, Any?>(
        "type" to n.typeName, "key" to key, "path" to path,
        "source" to n.source?.toString(),
        "sourceChain" to n.sourceChain.map { it.toString() }
    )
    when (n) {
        is ScalarNode -> view["value"] = n.value
        is NullNode -> view["value"] = "null"
        is MapNode -> view["children"] = n.entries.map { (k, v) -> nodeView(v, childPath(path, k), k) }
        is SeqNode -> view["children"] = n.items.mapIndexed { i, v -> nodeView(v, "$path/$i", "[$i]") }
    }
    return view
}

fun conflictView(c: Conflict): Map<String, Any?> = mapOf(
    "id" to c.id, "path" to c.path, "reason" to c.reason, "status" to c.status,
    "resolution" to c.resolution,
    "baseFp" to c.baseFp, "oursFp" to c.oursFp, "theirsFp" to c.theirsFp,
    "base" to c.base?.let { nodeToPlain(it) },
    "ours" to c.ours?.let { nodeToPlain(it) },
    "theirs" to c.theirs?.let { nodeToPlain(it) }
)

fun sessionView(s: MergeSession): Map<String, Any?> = mapOf(
    "id" to s.id,
    "version" to s.version,
    "baseId" to s.record.baseId, "oursId" to s.record.oursId, "theirsId" to s.record.theirsId,
    "policyVersion" to s.record.policyVersion,
    "items" to s.items.map { mapOf("path" to it.path, "action" to it.action, "sourceChain" to it.sourceChain.map { r -> r.toString() }) },
    "conflicts" to s.conflicts.map { conflictView(it) },
    "suggestions" to s.suggestions,
    "trees" to mapOf(
        "base" to nodeView(s.inputTrees.first, "", null),
        "ours" to nodeView(s.inputTrees.second, "", null),
        "theirs" to nodeView(s.inputTrees.third, "", null),
        "result" to nodeView(s.resultTree, "", null)
    )
)

class Api(private val service: MergeService) {
    fun handle(ex: HttpExchange) {
        try {
            val result = route(ex)
            respond(ex, 200, result)
        } catch (e: VersionConflictException) {
            respond(ex, 409, mapOf("error" to "version-conflict", "currentVersion" to e.currentVersion))
        } catch (e: NotFoundException) {
            respond(ex, 404, mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            respond(ex, 400, mapOf("error" to e.message))
        } catch (e: Exception) {
            respond(ex, 400, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun body(ex: HttpExchange): Map<String, Any?> {
        val text = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        return json.readValue(text)
    }

    private fun route(ex: HttpExchange): Any? {
        val method = ex.requestMethod
        val parts = ex.requestURI.path.removePrefix("/api/").split("/").filter { it.isNotEmpty() }
        val b = lazy { body(ex) }
        return when {
            parts == listOf("state") && method == "GET" -> mapOf(
                "documents" to service.documents.map { mapOf("id" to it.id, "name" to it.name, "format" to it.format, "text" to it.text) },
                "policies" to service.policySet.policies,
                "policyVersion" to service.policySet.version,
                "decisions" to service.decisions,
                "merges" to service.listMerges().map { mapOf("id" to it.id, "version" to it.version, "baseId" to it.baseId, "oursId" to it.oursId, "theirsId" to it.theirsId) },
                "exports" to service.exports
            )
            parts == listOf("documents") && method == "POST" ->
                service.addDocument(b.value["name"] as String, b.value["format"] as String, b.value["text"] as String)
            parts.size == 2 && parts[0] == "documents" && method == "DELETE" ->
                service.deleteDocument(parts[1]).let { mapOf("ok" to true) }
            parts == listOf("policies") && method == "PUT" -> {
                val raw = b.value["policies"] as Map<String, Map<String, Any?>>
                service.putPolicies(raw.mapValues { (_, v) ->
                    ArrayPolicy(ArrayStrategy.valueOf((v["strategy"] as String).uppercase()), (v["idKey"] as String?) ?: "id")
                })
            }
            parts == listOf("merges") && method == "POST" ->
                sessionView(service.createMerge(b.value["baseId"] as String, b.value["oursId"] as String, b.value["theirsId"] as String))
            parts.size == 2 && parts[0] == "merges" && method == "GET" -> sessionView(service.getMerge(parts[1]))
            parts.size == 3 && parts[0] == "merges" && parts[2] == "resolve" && method == "POST" -> {
                val resolutions = (b.value["resolutions"] as List<Map<String, Any?>>).map {
                    AppliedResolution(it["conflictId"] as String, it["path"] as String, it["resolution"] as String, it["value"] as String?)
                }
                sessionView(service.resolve(parts[1], (b.value["expectedVersion"] as Number).toInt(), resolutions))
            }
            parts.size == 3 && parts[0] == "merges" && parts[2] == "export" && method == "POST" ->
                service.export(parts[1], b.value["format"] as String)
            parts == listOf("bundle") && method == "GET" -> service.exportBundle()
            parts == listOf("bundle") && method == "POST" ->
                service.importBundle(json.readValue(json.writeValueAsString(b.value), Bundle::class.java)).let { mapOf("ok" to true) }
            parts == listOf("demo") && method == "POST" -> loadDemo()
            else -> throw NotFoundException("no route ${ex.requestURI.path}")
        }
    }

    private fun loadDemo(): Map<String, Any?> {
        val base = service.addDocument("base.yaml", "yaml", """
server:
  host: 0.0.0.0
  port: 8080
features:
  - id: logging
    level: info
  - id: cache
    ttl: 60
limits:
  cpu: 2
""".trimIndent())
        val ours = service.addDocument("env.yaml", "yaml", """
server:
  host: 127.0.0.1
  port: 8080
features:
  - id: logging
    level: debug
  - id: cache
    ttl: 60
limits: null
""".trimIndent())
        val theirs = service.addDocument("hotfix.yaml", "yaml", """
server:
  host: 0.0.0.0
  port: 9090
features:
  - id: logging
    level: warn
  - id: cache
    ttl: 120
""".trimIndent())
        service.putPolicies(mapOf("/features" to ArrayPolicy(ArrayStrategy.BY_ID, "id")))
        val merge = service.createMerge(base.id, ours.id, theirs.id)
        return mapOf("baseId" to base.id, "oursId" to ours.id, "theirsId" to theirs.id, "mergeId" to merge.id)
    }

    private fun respond(ex: HttpExchange, code: Int, payload: Any?) {
        val bytes = json.writeValueAsBytes(payload)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5221
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
        }
        i++
    }
    val service = MergeService(Store(java.io.File("data")))
    val api = Api(service)
    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext("/api") { ex -> api.handle(ex) }
    server.createContext("/") { ex ->
        val bytes = object {}.javaClass.getResourceAsStream("/static/index.html")!!.readBytes()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
    server.start()
    println("语义配置合并器 listening on http://$host:$port")
}
