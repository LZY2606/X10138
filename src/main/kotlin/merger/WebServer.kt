package merger

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class WebServer(private val service: SessionService, private val webRoot: Path?, port: Int, host: String) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { exchange ->
            try { handle(exchange) } catch (e: Exception) {
                error(exchange, e)
            }
        }
    }

    fun start() {
        server.start()
        val addr = server.address
        println("语义配置合并器 已启动: http://${addr.hostString.ifEmpty { "127.0.0.1" }}:${addr.port}")
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        if (path.startsWith("/api/")) return api(ex, path)
        serveStatic(ex, path)
    }

    private fun serveStatic(ex: HttpExchange, path: String) {
        val resource = if (path == "/" || path == "/index.html") "/web/index.html"
        else "/web" + path
        val bytes = javaClass.getResourceAsStream(resource)?.use { it.readAllBytes() }
        if (bytes == null) {
            send(ex, 404, "text/plain; charset=utf-8", "未找到 $path".toByteArray())
            return
        }
        val mime = when {
            resource.endsWith(".html") -> "text/html; charset=utf-8"
            resource.endsWith(".css") -> "text/css; charset=utf-8"
            resource.endsWith(".js") -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
        send(ex, 200, mime, bytes)
    }

    private fun api(ex: HttpExchange, path: String) {
        val body = if (ex.requestMethod == "POST" || ex.requestMethod == "PUT")
            String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)
        else ""
        val json = if (body.isBlank()) JV.Obj() else
            try { JsonCodec.parse(body) as JV.Obj } catch (e: Exception) {
                throw BadInputException("请求体不是合法 JSON: ${e.message}")
            }
        val out = Routes.route(service, ex.requestMethod, path, ex.requestURI.query, json)
        val bytes = JsonCodec.stringify(out).toByteArray(StandardCharsets.UTF_8)
        send(ex, 200, "application/json; charset=utf-8", bytes)
    }

    private fun send(ex: HttpExchange, code: Int, mime: String, bytes: ByteArray) {
        ex.responseHeaders.set("Content-Type", mime)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun error(ex: HttpExchange, e: Exception) {
        val code = when (e) {
            is ConflictBusyException -> 409
            is BadInputException, is ParseException, is JParseException, is BundleException -> 400
            else -> 500
        }
        val payload = JV.Obj(linkedMapOf(
            "error" to JV.Bool(true),
            "code" to JV.Num(java.math.BigDecimal.valueOf(code.toLong())),
            "message" to JV.Str(e.message ?: e.javaClass.simpleName)
        ))
        if (e is ConflictBusyException) payload.map["busy"] = JV.Bool(true)
        send(ex, code, "application/json; charset=utf-8",
            JsonCodec.stringify(payload).toByteArray(StandardCharsets.UTF_8))
        if (code == 500) e.printStackTrace()
    }
}
