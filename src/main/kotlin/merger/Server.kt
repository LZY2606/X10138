package merger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors

class MergeServer(private val store: Store, host: String, port: Int) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val f = JsonNodeFactory.instance

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { ex -> route(ex) }
    }

    fun start() {
        server.start()
        println("语义配置合并器已启动: http://${server.address.hostString}:${server.address.port}")
    }

    fun stop() = server.stop(0)

    private fun route(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val m = ex.requestMethod
            when {
                path == "/" && m == "GET" -> serveStatic(ex, "static/index.html", "text/html; charset=utf-8")
                path == "/api/sample" && m == "GET" -> sendJson(ex, 200, samplePayload())
                path == "/api/sessions" && m == "POST" -> handleCreate(ex)
                path == "/api/sessions" && m == "GET" -> sendJson(ex, 200, listSessions())
                path.matches(Regex("/api/sessions/[\\w-]+")) && m == "GET" ->
                    sendJson(ex, 200, sessionState(path.substringAfterLast('/')) ?: return notFound(ex))
                path.matches(Regex("/api/sessions/[\\w-]+/tree")) && m == "GET" ->
                    sendJson(ex, 200, tree(path.split('/')[3]) ?: return notFound(ex))
                path.matches(Regex("/api/sessions/[\\w-]+/node")) && m == "GET" ->
                    sendJson(ex, 200, nodeDetail(ex) ?: return notFound(ex))
                path.matches(Regex("/api/sessions/[\\w-]+/resolve")) && m == "POST" ->
                    handleResolve(ex, path.split('/')[3])
                path.matches(Regex("/api/sessions/[\\w-]+/export")) && m == "GET" ->
                    handleExport(ex, path.split('/')[3])
                path == "/api/import" && m == "POST" -> handleImport(ex)
                else -> notFound(ex)
            }
        } catch (e: ParseException) {
            sendJson(ex, 400, err("parse-error", e.message ?: ""))
        } catch (e: Exception) {
            sendJson(ex, 500, err("internal", e.message ?: e.javaClass.simpleName))
        } finally {
            ex.close()
        }
    }

    private fun query(ex: HttpExchange, key: String): String? =
        ex.requestURI.query?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == key }?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

    private fun readBody(ex: HttpExchange): JsonNode =
        jsonMapper.readTree(ex.requestBody.readBytes())

    private fun handleCreate(ex: HttpExchange) {
        val j = readBody(ex)
        val format = Format.valueOf(j.get("format")?.asText() ?: "YAML")
        val arrays = mutableMapOf<String, ArrayPolicy>()
        j.get("policies")?.get("arrays")?.forEach { a ->
            val p = ArrayPolicy(
                a.get("path").asText(),
                ArrayStrategy.valueOf(a.get("strategy").asText().uppercase()),
                a.get("idKey")?.asText() ?: "id"
            )
            arrays[p.path] = p
        }
        val policies = PolicySet(j.get("policies")?.get("version")?.asText() ?: "v1", arrays)
        val s = store.createSession(
            j.get("name")?.asText() ?: "未命名会话",
            SessionInput(format, j.get("base").asText(), j.get("ours").asText(), j.get("theirs").asText()),
            policies
        )
        sendJson(ex, 200, sessionState(s.id)!!)
    }

    private fun handleResolve(ex: HttpExchange, id: String) {
        val j = readBody(ex)
        val expected = j.get("expectedVersion")?.asLong() ?: -1L
        val resolutions = j.get("resolutions")?.map { r ->
            Resolution(
                r.get("path").asText(),
                r.get("choice").asText(),
                r.get("value")?.takeUnless { it.isNull }?.asText()
                    ?.let { parse(it, Format.valueOf(r.get("valueFormat")?.asText() ?: "YAML"), "manual") }
            )
        } ?: emptyList()
        val s = store.resolve(id, expected, resolutions)
        if (s == null) {
            sendJson(ex, 409, err("version-conflict",
                "版本已过期：他人已提交新的裁决，请刷新后重试"))
        } else {
            sendJson(ex, 200, sessionState(s.id)!!)
        }
    }

    private fun handleExport(ex: HttpExchange, id: String) {
        val s = store.get(id) ?: return notFound(ex)
        val fmt = Format.valueOf((query(ex, "fmt") ?: "yaml").uppercase())
        val b = store.export(s, fmt)
        val j = f.objectNode()
            .put("sessionId", b.sessionId).put("format", b.format.name)
            .put("policyVersion", b.policyVersion).put("text", b.text)
            .put("resultFingerprint", b.resultFingerprint)
        j.set<JsonNode>("manifest", f.arrayNode().apply {
            b.manifest.forEach { e ->
                add(f.objectNode().put("path", e.path).put("fingerprint", e.fingerprint)
                    .set<JsonNode>("origins", CNodeCodec.originsToWire(e.origins)))
            }
        })
        sendJson(ex, 200, j)
    }

    private fun handleImport(ex: HttpExchange) {
        val j = readBody(ex)
        val manifest = j.get("manifest").map { e ->
            ManifestEntry(
                e.get("path").asText(), e.get("fingerprint").asText(),
                CNodeCodec.originsFromWire(e.get("origins"))
            )
        }
        val bundle = ExportBundle(
            j.get("sessionId")?.asText() ?: "",
            Format.valueOf(j.get("format").asText()),
            j.get("policyVersion")?.asText() ?: "",
            j.get("text").asText(), manifest,
            j.get("resultFingerprint").asText()
        )
        sendJson(ex, 200, jsonMapper.valueToTree(store.verifyImport(bundle)))
    }

    // ---------- 视图模型 ----------

    private fun sessionState(id: String): JsonNode? {
        val s = store.get(id) ?: return null
        val r = s.result
        val j = f.objectNode()
            .put("id", s.id).put("name", s.name).put("version", s.version)
            .put("format", s.input.format.name)
            .put("policyVersion", s.policySet.version)
            .put("autoCount", r?.auto?.size ?: 0)
            .put("conflictCount", r?.conflicts?.size ?: 0)
        j.set<JsonNode>("conflicts", f.arrayNode().apply {
            r?.conflicts?.forEach { c -> add(conflictJson(c)) }
        })
        j.set<JsonNode>("auto", f.arrayNode().apply {
            r?.auto?.forEach { a ->
                add(f.objectNode().put("path", a.path).put("fingerprint", a.fingerprint)
                    .set<JsonNode>("origins", CNodeCodec.originsToWire(a.origins)))
            }
        })
        return j
    }

    private fun conflictJson(c: Conflict) = f.objectNode()
        .put("path", c.path).put("reason", c.reason).put("detail", c.detail)
        .put("baseFp", c.baseFp).put("oursFp", c.oursFp).put("theirsFp", c.theirsFp)
        .put("base", c.base?.let { serialize(it, Format.YAML) } ?: "<缺失>")
        .put("ours", c.ours?.let { serialize(it, Format.YAML) } ?: "<缺失>")
        .put("theirs", c.theirs?.let { serialize(it, Format.YAML) } ?: "<缺失>")
        .put("suggestion", c.suggestion?.let { "历史裁决 ${it.id}（指纹已变化，仅作建议）：选择 ${it.choice}" })

    private fun listSessions(): JsonNode = f.arrayNode().apply {
        store.list().forEach { s ->
            add(f.objectNode().put("id", s.id).put("name", s.name)
                .put("version", s.version)
                .put("conflictCount", s.result?.conflicts?.size ?: 0))
        }
    }

    private fun tree(id: String): JsonNode? {
        val s = store.get(id) ?: return null
        val conflictPaths = s.result?.conflicts?.map { it.path }?.toSet() ?: emptySet()
        val autoPaths = s.result?.auto?.associate { it.path to it.origins } ?: emptyMap()
        fun build(n: CNode, path: List<Seg>): JsonNode {
            val ps = pathToString(path)
            val j = f.objectNode()
            j.put("path", ps)
            j.put("status", when {
                conflictPaths.contains(ps) -> "conflict"
                autoPaths.containsKey(ps) -> "auto"
                else -> "clean"
            })
            j.set<JsonNode>("origins", CNodeCodec.originsToWire(n.origins))
            when (n) {
                is CNode.CObject -> {
                    j.put("type", "object")
                    j.set<JsonNode>("children", f.objectNode().apply {
                        n.entries.forEach { (k, v) -> set<JsonNode>(k, build(v, path + Seg.Key(k))) }
                    })
                }
                is CNode.CArray -> {
                    j.put("type", "array")
                    j.set<JsonNode>("children", f.arrayNode().apply {
                        n.items.forEachIndexed { i, v -> add(build(v, path + Seg.Idx(i))) }
                    })
                }
                is CNode.CNull -> j.put("type", "null")
                is CNode.CScalar -> { j.put("type", "scalar"); j.put("value", n.value.toString()) }
            }
            return j
        }
        return s.result?.output?.let { build(it, emptyList()) } ?: f.nullNode()
    }

    private fun nodeDetail(ex: HttpExchange): JsonNode? {
        val id = ex.requestURI.path.split('/')[3]
        val s = store.get(id) ?: return null
        val ps = query(ex, "path") ?: "/"
        val node = nodeAt(s.result?.output, pathFromString(ps))
        val conflict = s.result?.conflicts?.firstOrNull { it.path == ps }
        val j = f.objectNode()
        j.put("path", ps)
        j.put("fingerprint", fingerprint(node))
        j.set<JsonNode>("origins", CNodeCodec.originsToWire(node?.origins ?: emptyList()))
        j.put("value", node?.let { serialize(it, Format.YAML) } ?: "<缺失>")
        conflict?.let { j.set<JsonNode>("conflict", conflictJson(it)) }
        return j
    }

    // ---------- 工具 ----------

    private fun serveStatic(ex: HttpExchange, resource: String, contentType: String) {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.readBytes()
        if (bytes == null) { notFound(ex); return }
        ex.responseHeaders.set("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun sendJson(ex: HttpExchange, code: Int, body: JsonNode) {
        val bytes = jsonMapper.writeValueAsBytes(body)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun notFound(ex: HttpExchange) = sendJson(ex, 404, err("not-found", "资源不存在"))

    private fun err(code: String, msg: String) =
        f.objectNode().put("error", code).put("message", msg)

    private fun samplePayload(): JsonNode {
        val base = """
server:
  host: 0.0.0.0
  port: 8080
features:
  - id: auth
    enabled: true
  - id: billing
    enabled: false
defaults: &defaults
  retries: 3
  timeout: 30
primary:
  <<: *defaults
  name: main
note: null
legacy: keep-me
""".trimIndent()
        val ours = """
server:
  host: 10.0.0.1
  port: 9090
features:
  - id: billing
    enabled: true
  - id: auth
    enabled: true
defaults: &defaults
  retries: 3
  timeout: 60
primary:
  <<: *defaults
  name: main
legacy: keep-me
""".trimIndent()
        val theirs = """
server:
  host: 127.0.0.1
  port: 8080
features:
  - id: auth
    enabled: true
    level: strict
  - id: billing
    enabled: false
defaults: &defaults
  retries: 5
  timeout: 45
primary:
  <<: *defaults
  name: main
note: null
legacy: keep-me
""".trimIndent()
        val j = f.objectNode()
            .put("name", "演示：服务配置合并")
            .put("format", "YAML")
            .put("base", base).put("ours", ours).put("theirs", theirs)
        j.putObject("policies").put("version", "v1")
            .set<JsonNode>("arrays", f.arrayNode().apply {
                add(f.objectNode().put("path", "/features").put("strategy", "BY_ID").put("idKey", "id"))
            })
        return j
    }
}
