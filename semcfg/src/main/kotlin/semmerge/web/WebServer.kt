package semmerge.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import semmerge.json.JsonParser
import semmerge.json.JsonWriter
import semmerge.merge.BundleService
import semmerge.merge.CustomValues
import semmerge.merge.ListStrategy
import semmerge.merge.Merger
import semmerge.merge.OutputBuilder
import semmerge.merge.Resolution
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SNode
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.session.InputSide
import semmerge.session.JsonCodec.asList
import semmerge.session.JsonCodec.asMap
import semmerge.session.JsonCodec.asString
import semmerge.session.JsonCodec.list
import semmerge.session.JsonCodec.num
import semmerge.session.JsonCodec.obj
import semmerge.session.JsonCodec.str
import semmerge.session.OptimisticLockException
import semmerge.session.PathRenderer
import semmerge.session.RawInput
import semmerge.session.ResolutionRequest
import semmerge.session.SessionService
import semmerge.session.SessionStore
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class WebServer(
    host: String,
    port: Int,
    private val dataDir: Path,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val service = SessionService(SessionStore(dataDir))

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
    }

    fun start() {
        server.start()
    }

    fun stop() = server.stop(0)
    fun address(): InetSocketAddress = server.address

    private fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (e: Exception) {
            val status = when (e) {
                is OptimisticLockException -> 409
                is IllegalArgumentException -> 400
                else -> 500
            }
            val body = obj(
                "error" to str(e.message ?: "internal error"),
                "currentVersion" to num(service.state.version),
            ).let { JsonWriter.write(it) }
            writeJson(exchange, status, body)
        }
    }

    private fun route(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        when {
            method == "GET" && path == "/" -> static(exchange, "index.html", "text/html; charset=utf-8")
            method == "GET" && path == "/app.js" -> static(exchange, "app.js", "application/javascript; charset=utf-8")
            method == "GET" && path == "/styles.css" -> static(exchange, "styles.css", "text/css; charset=utf-8")
            method == "GET" && path == "/api/state" -> getState(exchange)
            method == "POST" && path == "/api/inputs" -> updateInput(exchange)
            method == "POST" && path == "/api/policies" -> addPolicy(exchange)
            method == "POST" && path == "/api/policies/remove" -> removePolicy(exchange)
            method == "POST" && path == "/api/resolve" -> resolve(exchange)
            method == "POST" && path == "/api/reset" -> reset(exchange)
            method == "POST" && path == "/api/export" -> export(exchange)
            method == "POST" && path == "/api/import" -> importBundle(exchange)
            method == "GET" && path == "/api/history" -> history(exchange)
            method == "POST" && path == "/api/remerge" -> remerge(exchange)
            else -> writeJson(exchange, 404, JsonWriter.write(obj("error" to str("not found"))))
        }
    }

    private fun static(exchange: HttpExchange, name: String, contentType: String) {
        val bytes = WebServer::class.java.classLoader.getResourceAsStream("web/$name")?.readBytes()
            ?: Files.readString(Path.of("src/main/resources/web/$name")).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readBody(exchange: HttpExchange): Map<String, SNode> {
        val text = exchange.requestBody.reader(StandardCharsets.UTF_8).readText()
        if (text.isBlank()) return emptyMap()
        return JsonParser.parse(text).asMap()
    }

    private fun writeJson(exchange: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun getState(exchange: HttpExchange) {
        val result = service.recompute()
        val view = ViewStateBuilder.build(service.state, result)
        writeJson(exchange, 200, stateEnvelope(view))
    }

    private fun stateEnvelope(view: ViewState): String {
        val root = obj(
            "version" to num(view.sessionVersion),
            "policyVersion" to num(view.policyVersion.toLong()),
            "inputs" to view.inputs,
            "policies" to view.policies,
            "merged" to view.merged,
            "conflicts" to view.conflicts,
            "stats" to view.stats,
            "decisions" to view.decisions,
            "advisories" to view.advisories,
        )
        return JsonWriter.write(root)
    }

    private fun updateInput(exchange: HttpExchange) {
        val body = readBody(exchange)
        val version = body["version"]!!.asString().toLong()
        val side = InputSide.entries.first { it.key == body.str("side") }
        val format = body.strOr("format", "yaml")
        val text = body.str("text")
        // Validate parse up-front.
        CustomValues.parse(text, format)
        val (state, result) = service.updateInput(side, RawInput(text, format), version)
        writeJson(exchange, 200, stateEnvelope(ViewStateBuilder.build(state, result)))
    }

    private fun Map<String, SNode>.strOr(key: String, default: String): String {
        val v = this[key] ?: return default
        val s = v as? SScalar ?: return default
        return if (s.kind == ScalarKind.NULL) default else s.text
    }

    private fun addPolicy(exchange: HttpExchange) {
        val body = readBody(exchange)
        val version = body["version"]!!.asString().toLong()
        val path = PathRenderer.parse(body.str("path"))
        val strategy = ListStrategy.entries.first { it.id == body.str("strategy") }
        val idField = body.strOr("idField", "id")
        val (state, result) = service.setPolicy(path, strategy, idField, version)
        writeJson(exchange, 200, stateEnvelope(ViewStateBuilder.build(state, result)))
    }

    private fun removePolicy(exchange: HttpExchange) {
        val body = readBody(exchange)
        val version = body["version"]!!.asString().toLong()
        val path = PathRenderer.parse(body.str("path"))
        val (state, result) = service.removePolicy(path, version)
        writeJson(exchange, 200, stateEnvelope(ViewStateBuilder.build(state, result)))
    }

    private fun resolve(exchange: HttpExchange) {
        val body = readBody(exchange)
        val version = body["version"]!!.asString().toLong()
        val items = body["items"]!!.asList().map { n ->
            val m = n.asMap()
            ResolutionRequest(m.str("conflictKey"), decodeResolution(m["resolution"]!!),
                (m["note"] as? SScalar)?.text ?: "")
        }
        val commit = service.resolve(items, version)
        writeJson(exchange, 200, JsonWriter.write(obj(
            "state" to JsonParser.parse(stateEnvelope(ViewStateBuilder.build(commit.session, commit.merge))),
            "accepted" to SList(commit.acceptedKeys.map { str(it) }),
            "rejectedStale" to SList(commit.rejectedStaleKeys.map { str(it) }),
        )))
    }

    private fun decodeResolution(node: SNode): Resolution {
        val m = node.asMap()
        return when (val type = m.str("type")) {
            "takeA" -> Resolution.TakeA
            "takeB" -> Resolution.TakeB
            "keepBase" -> Resolution.KeepBase
            "delete" -> Resolution.Delete
            "setNull" -> Resolution.SetNull
            "custom" -> Resolution.Custom(m.str("text"), m.strOr("format", "yaml"))
            "customOrder" -> Resolution.CustomOrder(m["ids"]!!.asList().map { it.asString() })
            else -> throw IllegalArgumentException("unknown resolution $type")
        }
    }

    private fun reset(exchange: HttpExchange) {
        val result = service.reset()
        writeJson(exchange, 200, stateEnvelope(ViewStateBuilder.build(service.state, result)))
    }

    private fun remerge(exchange: HttpExchange) {
        val result = service.recompute()
        writeJson(exchange, 200, stateEnvelope(ViewStateBuilder.build(service.state, result)))
    }

    private fun export(exchange: HttpExchange) {
        val body = readBody(exchange)
        val format = body.strOr("format", "yaml")
        val result = service.recompute()
        val bundle = BundleService.export(service.state, result, format)
        val bundleJson = bundle.toJson()
        Files.createDirectories(dataDir.resolve("outputs"))
        val outFile = dataDir.resolve("outputs").resolve("export-${System.currentTimeMillis()}.json")
        Files.writeString(outFile, JsonWriter.write(bundleJson), StandardCharsets.UTF_8)
        Files.writeString(
            dataDir.resolve("outputs").resolve("latest-output.$format"),
            bundle.outputText, StandardCharsets.UTF_8,
        )
        writeJson(exchange, 200, JsonWriter.write(bundleJson))
    }

    private fun importBundle(exchange: HttpExchange) {
        val body = readBody(exchange)
        val bundleNode = body["bundle"] ?: throw IllegalArgumentException("missing bundle")
        val (state, result) = BundleService.importBundle(bundleNode)
        val replaced = service.replaceState(state)
        val verify = BundleService.verifyRoundTrip(
            BundleService.export(state, replaced,
                body.strOr("format", "yaml")),
            state, result,
        )
        writeJson(exchange, 200, JsonWriter.write(obj(
            "state" to JsonParser.parse(stateEnvelope(ViewStateBuilder.build(state, replaced))),
            "verification" to obj(
                "ok" to bool(verify.isEmpty()),
                "errors" to SList(verify.map { str(it) }),
            ),
        )))
    }

    private fun history(exchange: HttpExchange) {
        val all = service.state.decisions.values.flatten()
            .sortedByDescending { it.decidedAt }
            .map { r ->
                obj(
                    "conflictKey" to str(r.conflictKey),
                    "path" to str(r.path.render()),
                    "resolution" to str(r.resolution.label()),
                    "decidedAt" to str(r.decidedAt.toString()),
                    "decidedBy" to str(r.decidedBy),
                    "policyVersion" to num(r.policyVersion.toLong()),
                    "baseSessionVersion" to num(r.baseSessionVersion),
                )
            }
        writeJson(exchange, 200, JsonWriter.write(SList(all)))
    }

    private fun bool(b: Boolean): SNode =
        SScalar(ScalarKind.BOOL, b.toString(), semmerge.model.ScalarStyle.PLAIN)
}
