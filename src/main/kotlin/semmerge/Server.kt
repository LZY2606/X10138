package semmerge

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Paths

fun toYamlText(node: Node): String {
    val opts = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        width = 120
    }
    return Yaml(opts).dump(toPlain(node))
}

/** 导出包：导出文本 + 来源链 + 指纹 + 策略版本；再导入后可逐项核对。 */
fun exportBundle(s: SessionState, fmt: String): JsonObject {
    val text = if (fmt == "json") toJsonText(s.result.root) else toYamlText(s.result.root)
    return JsonObject(
        mapOf(
            "format" to JsonPrimitive(fmt),
            "text" to JsonPrimitive(text),
            "fingerprint" to JsonPrimitive(s.result.fingerprint),
            "policyVersion" to JsonPrimitive(s.policies.version),
            "provenance" to JsonArray(provenanceMap(s.result.root).map { (p, refs) ->
                JsonObject(mapOf(
                    "path" to JsonPrimitive(p),
                    "sources" to JsonArray(refs.map {
                        JsonObject(mapOf(
                            "doc" to JsonPrimitive(it.doc),
                            "path" to JsonPrimitive(it.path),
                            "note" to JsonPrimitive(it.note)
                        ))
                    })
                ))
            })
        )
    )
}

/** 再导入校验：重新解析导出文本，指纹与路径集合必须一致。 */
fun verifyImport(bundle: JsonObject): JsonObject {
    val text = bundle["text"]!!.jsonPrimitive.content
    val expectedFp = bundle["fingerprint"]!!.jsonPrimitive.content
    val reparsed = Parser.parse(text, "import")
    val actualFp = fingerprint(reparsed)
    val expectedPaths = (bundle["provenance"] as JsonArray)
        .map { (it as JsonObject)["path"]!!.jsonPrimitive.content }.toSet()
    val actualPaths = provenanceMap(reparsed).map { it.first }.toSet()
    val ok = expectedFp == actualFp && expectedPaths.all { p ->
        p == "/" || actualPaths.contains(p) || pathExists(reparsed, p)
    }
    return JsonObject(mapOf(
        "ok" to JsonPrimitive(ok),
        "fingerprintMatch" to JsonPrimitive(expectedFp == actualFp),
        "expectedFingerprint" to JsonPrimitive(expectedFp),
        "actualFingerprint" to JsonPrimitive(actualFp),
        "pathsChecked" to JsonPrimitive(expectedPaths.size)
    ))
}

private fun pathExists(root: Node, rendered: String): Boolean {
    var cur: Node = root
    for (seg in PolicySet.splitSegments(rendered)) {
        cur = when (cur) {
            is ObjNode -> cur.entries[seg] ?: return false
            is ArrNode -> seg.toIntOrNull()?.let { cur.items.getOrNull(it) } ?: return false
            else -> return false
        }
    }
    return true
}

private fun stateJson(s: SessionState): JsonObject {
    val conflictPaths = s.result.conflicts.map { it.path }.toSet()
    return JsonObject(mapOf(
        "session" to JsonPrimitive(s.id),
        "version" to JsonPrimitive(s.version),
        "policyVersion" to JsonPrimitive(s.policies.version),
        "fingerprint" to JsonPrimitive(s.result.fingerprint),
        "tree" to nodeToJson(s.result.root),
        "conflictPaths" to JsonArray(conflictPaths.map { JsonPrimitive(it) }),
        "auto" to JsonArray(s.result.auto.map {
            JsonObject(mapOf(
                "path" to JsonPrimitive(it.path),
                "reason" to JsonPrimitive(it.reason),
                "sources" to JsonArray(it.sources.map { src ->
                    JsonObject(mapOf(
                        "doc" to JsonPrimitive(src.doc),
                        "path" to JsonPrimitive(src.path),
                        "note" to JsonPrimitive(src.note)
                    ))
                })
            ))
        }),
        "conflicts" to JsonArray(s.result.conflicts.map { c ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(c.id),
                "path" to JsonPrimitive(c.path),
                "reason" to JsonPrimitive(c.reason),
                "base" to nodeToJson(c.base),
                "left" to nodeToJson(c.left),
                "right" to nodeToJson(c.right),
                "suggestion" to (c.suggestion?.let {
                    JsonPrimitive("存在旧裁决 ${it.id}（指纹已不匹配，仅供参考）")
                } ?: JsonPrimitive(""))
            ))
        }),
        "decisions" to JsonArray(s.decisions.map {
            JsonObject(mapOf(
                "id" to JsonPrimitive(it.id),
                "path" to JsonPrimitive(it.path),
                "note" to JsonPrimitive(it.note)
            ))
        })
    ))
}

class MergerApp(val store: SessionStore) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationCall.json(obj: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(obj.toString(), ContentType.Application.Json, status)

    fun module(): Application.() -> Unit = {
        routing {
            get("/") {
                val html = MergerApp::class.java.getResource("/static/index.html")!!.readText()
                call.respondText(html, ContentType.Text.Html)
            }

            post("/api/session") {
                val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                fun doc(name: String, defaultId: String): DocInput {
                    val d = body[name] as JsonObject
                    return DocInput(
                        id = d["id"]?.jsonPrimitive?.contentOrNull ?: defaultId,
                        format = d["format"]?.jsonPrimitive?.contentOrNull ?: "yaml",
                        text = d["text"]!!.jsonPrimitive.content
                    )
                }
                val policies = PolicySet(
                    version = body["policyVersion"]?.jsonPrimitive?.contentOrNull ?: "v1",
                    policies = (body["policies"] as? JsonArray)?.map {
                        val p = it as JsonObject
                        ArrayPolicy(
                            p["path"]!!.jsonPrimitive.content,
                            ArrayStrategy.valueOf(p["strategy"]!!.jsonPrimitive.content),
                            p["idKey"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        )
                    } ?: emptyList()
                )
                try {
                    call.json(stateJson(store.newSession(doc("base", "base"), doc("left", "left"), doc("right", "right"), policies)))
                } catch (e: Exception) {
                    call.json(JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "parse error"))),
                        HttpStatusCode.BadRequest)
                }
            }

            get("/api/state") {
                val s = store.state
                if (s == null) call.json(JsonObject(mapOf("empty" to JsonPrimitive(true))))
                else call.json(stateJson(s))
            }

            post("/api/resolve") {
                val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                val expected = body["expectedVersion"]!!.jsonPrimitive.longOrNull ?: -1L
                val cur = store.state
                if (cur == null) {
                    call.json(JsonObject(mapOf("error" to JsonPrimitive("no session"))), HttpStatusCode.BadRequest)
                    return@post
                }
                val byPath = cur.result.conflicts.associateBy { it.path }
                val resolutions = mutableListOf<Pair<String, Node>>()
                for (el in (body["resolutions"] as JsonArray)) {
                    val r = el as JsonObject
                    val path = r["path"]!!.jsonPrimitive.content
                    val c = byPath[path]
                    if (c == null) {
                        call.json(JsonObject(mapOf("error" to JsonPrimitive("冲突不存在或已被解决: $path"))),
                            HttpStatusCode.Conflict)
                        return@post
                    }
                    val chosen: Node = when (r["choice"]!!.jsonPrimitive.content) {
                        "left" -> c.left
                        "right" -> c.right
                        "base" -> c.base
                        "delete" -> MissingNode
                        "custom" -> try {
                            Parser.parse(r["value"]!!.jsonPrimitive.content, "custom")
                        } catch (e: Exception) {
                            call.json(JsonObject(mapOf("error" to JsonPrimitive("自定义值解析失败: ${e.message}"))),
                                HttpStatusCode.BadRequest)
                            return@post
                        }
                        else -> {
                            call.json(JsonObject(mapOf("error" to JsonPrimitive("unknown choice"))),
                                HttpStatusCode.BadRequest)
                            return@post
                        }
                    }
                    resolutions.add(path to chosen)
                }
                val updated = store.applyResolutions(expected, resolutions)
                if (updated == null) {
                    call.json(JsonObject(mapOf(
                        "error" to JsonPrimitive("版本过期，请刷新后重试"),
                        "currentVersion" to JsonPrimitive(store.state?.version ?: -1)
                    )), HttpStatusCode.Conflict)
                } else {
                    call.json(stateJson(updated))
                }
            }

            get("/api/export") {
                val s = store.state
                if (s == null) {
                    call.respondText("no session", status = HttpStatusCode.BadRequest)
                    return@get
                }
                val fmt = call.request.queryParameters["fmt"] ?: "yaml"
                val text = if (fmt == "json") toJsonText(s.result.root) else toYamlText(s.result.root)
                call.respondText(text, ContentType.Text.Plain)
            }

            get("/api/bundle") {
                val s = store.state
                if (s == null) call.json(JsonObject(mapOf("error" to JsonPrimitive("no session"))),
                    HttpStatusCode.BadRequest)
                else {
                    val fmt = call.request.queryParameters["fmt"] ?: "yaml"
                    call.json(exportBundle(s, fmt))
                }
            }

            post("/api/import") {
                val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                try {
                    call.json(verifyImport(body))
                } catch (e: Exception) {
                    call.json(JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "import failed"))),
                        HttpStatusCode.BadRequest)
                }
            }

            get("/api/decisions") {
                val s = store.state
                call.json(JsonObject(mapOf(
                    "decisions" to JsonArray((s?.decisions ?: emptyList()).map {
                        JsonObject(mapOf(
                            "id" to JsonPrimitive(it.id),
                            "path" to JsonPrimitive(it.path),
                            "baseFp" to JsonPrimitive(it.baseFp),
                            "leftFp" to JsonPrimitive(it.leftFp),
                            "rightFp" to JsonPrimitive(it.rightFp)
                        ))
                    })
                )))
            }
        }
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
    val store = SessionStore(Paths.get("data"))
    val app = MergerApp(store)
    embeddedServer(Netty, port = port, host = host, module = app.module()).start(wait = true)
}
