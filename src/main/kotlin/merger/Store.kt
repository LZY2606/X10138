package merger

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path as FsPath
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

/**
 * 文件持久化（原子写）。每个会话一个目录：
 *   inputs/base.{yaml|json}, inputs/a.*, inputs/b.*
 *   parsed/base.json, parsed/a.json, parsed/b.json   —— 解析模型快照
 *   policies.json                                    —— 策略版本
 *   decisions/dec-*.json                             —— 裁决历史（逐条追加）
 *   outputs/result-<rev>.{yaml|json}                 —— 每次输出快照
 *   state.json                                       —— 当前会话状态
 */
class Store(private val root: FsPath) {
    private val sessions = ConcurrentHashMap<String, MergeSession>()
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        Files.createDirectories(root)
    }

    @Synchronized
    fun listSessions(): List<String> {
        if (!Files.exists(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { Files.exists(it.resolve("state.json")) }
                .map { it.fileName.toString() }
                .sorted().toList()
        }
    }

    fun session(id: String): MergeSession? {
        sessions[id]?.let { return it }
        val lock = locks.computeIfAbsent(id) { Any() }
        synchronized(lock) {
            sessions[id]?.let { return it }
            val loaded = load(id) ?: return null
            sessions[id] = loaded
            return loaded
        }
    }

    fun create(id: String, base: MergeSession.InputDoc, a: MergeSession.InputDoc, b: MergeSession.InputDoc): MergeSession {
        val session = MergeSession(id, base, a, b, PolicyRegistry.EMPTY)
        sessions[id] = session
        save(session)
        return session
    }

    fun save(session: MergeSession) {
        val dir = root.resolve(session.id)
        Files.createDirectories(dir.resolve("inputs"))
        Files.createDirectories(dir.resolve("parsed"))
        Files.createDirectories(dir.resolve("decisions"))
        Files.createDirectories(dir.resolve("outputs"))
        val snap = session.snapshot()

        writeInput(dir.resolve("inputs").resolve("base"), snap.baseInput)
        writeInput(dir.resolve("inputs").resolve("a"), snap.aInput)
        writeInput(dir.resolve("inputs").resolve("b"), snap.bInput)

        val fps = snap.document.fingerprints
        atomicWrite(dir.resolve("parsed").resolve("base.json"), JsonCodec.encode(NodeJson.encode(snap.baseInput.parse())))
        atomicWrite(dir.resolve("parsed").resolve("a.json"), JsonCodec.encode(NodeJson.encode(snap.aInput.parse())))
        atomicWrite(dir.resolve("parsed").resolve("b.json"), JsonCodec.encode(NodeJson.encode(snap.bInput.parse())))

        val policyJson = JValues.obj(
            "version" to JValues.num(snap.policies.version),
            "arrayPolicies" to JValue.JObj(LinkedHashMap(snap.policies.arrayPolicies.mapValues { JValues.str(it.value.code) })),
            "idField" to JValue.JObj(LinkedHashMap(snap.policies.idField.mapValues { JValues.str(it.value) })),
        )
        atomicWrite(dir.resolve("policies.json"), JsonCodec.encode(policyJson))

        // 裁决历史逐条持久化（只写新增，保证可追溯）
        val existing = dir.resolve("decisions").toFile().listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        for (d in snap.decisions) {
            if (d.id !in existing) {
                atomicWrite(dir.resolve("decisions").resolve("${d.id}.json"), JsonCodec.encode(decisionToJson(d)))
            }
        }

        val state = JValues.obj(
            "id" to JValues.str(snap.id),
            "revision" to JValues.num(snap.revision),
            "savedAt" to JValues.str(Instant.now().toString()),
            "fingerprints" to JValues.obj(
                "base" to JValues.str(fps.first),
                "a" to JValues.str(fps.second),
                "b" to JValues.str(fps.third),
            ),
        )
        atomicWrite(dir.resolve("state.json"), JsonCodec.encode(state))

        // 输出快照：最新结果 YAML + JSON
        val unresolved = snap.document.conflicts.any { !it.resolved }
        if (!unresolved) {
            val resultNode = ResultExport.toNode(snap.document)
            atomicWrite(dir.resolve("outputs").resolve("result-${snap.revision}.yaml"), YamlWriter.write(resultNode))
            atomicWrite(dir.resolve("outputs").resolve("result-${snap.revision}.json"), JsonWriter.write(resultNode))
        }
    }

    private fun load(id: String): MergeSession? {
        val dir = root.resolve(id)
        val stateFile = dir.resolve("state.json")
        if (!stateFile.exists()) return null
        val policiesFile = dir.resolve("policies.json")
        val policies = if (policiesFile.exists()) {
            val pj = JsonCodec.decode(Files.readString(policiesFile))
            policiesFromJson(pj)
        } else PolicyRegistry.EMPTY

        val base = readInput(dir.resolve("inputs"), "base")
        val a = readInput(dir.resolve("inputs"), "a")
        val b = readInput(dir.resolve("inputs"), "b")
        val session = MergeSession(id, base, a, b, policies)
        val decDir = dir.resolve("decisions")
        if (Files.exists(decDir)) {
            Files.list(decDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.sorted().forEach { f ->
                    val d = decisionFromJson(JsonCodec.decode(Files.readString(f)))
                    session.injectLoaded(d)
                }
            }
        }
        session.recomputeAfterLoad()
        return session
    }

    private fun writeInput(targetNoExt: FsPath, doc: MergeSession.InputDoc) {
        val ext = when (doc.format) { ConfigFormat.YAML -> "yaml"; ConfigFormat.JSON -> "json" }
        atomicWrite(targetNoExt.resolveSibling(targetNoExt.fileName.toString() + ".$ext"), doc.text.let {
            if (it.endsWith("\n")) it else it + "\n"
        })
    }

    private fun readInput(dir: FsPath, name: String): MergeSession.InputDoc {
        val yaml = dir.resolve("$name.yaml")
        val json = dir.resolve("$name.json")
        return when {
            Files.exists(yaml) -> MergeSession.InputDoc("$name.yaml", ConfigFormat.YAML, Files.readString(yaml))
            Files.exists(json) -> MergeSession.InputDoc("$name.json", ConfigFormat.JSON, Files.readString(json))
            else -> error("缺少输入: $name")
        }
    }

    private fun atomicWrite(path: FsPath, text: String) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(".${path.fileName}.tmp-${ProcessHandle.current().pid()}")
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
