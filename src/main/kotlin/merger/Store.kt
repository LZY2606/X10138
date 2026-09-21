package merger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** Node 树（含来源链）与 JSON 的双向转换，用于持久化与导出包。 */
object NodeJson {
    val mapper: ObjectMapper = ObjectMapper()

    fun toJson(n: Node?): JsonNode {
        val o = mapper.createObjectNode()
        when (n) {
            null -> o.put("t", "missing")
            is ScalarNode -> {
                o.put("t", "scalar")
                when (val v = n.value) {
                    null -> o.putNull("v")
                    is Boolean -> o.put("v", v)
                    is BigDecimal -> o.put("v", v)
                    is String -> o.put("v", v)
                    else -> o.put("v", v.toString())
                }
            }
            is ArrNode -> {
                o.put("t", "arr")
                val a = o.putArray("items")
                n.items.forEach { a.add(toJson(it)) }
            }
            is ObjNode -> {
                o.put("t", "obj")
                val e = o.putObject("e")
                n.entries.forEach { (k, v) -> e.set<JsonNode>(k, toJson(v)) }
            }
        }
        if (n != null) {
            val arr = o.putArray("o")
            for (origin in n.origins) {
                val oo = arr.addObject()
                oo.put("doc", origin.doc)
                oo.put("path", origin.path)
                oo.put("note", origin.note)
            }
        }
        return o
    }

    fun fromJson(j: JsonNode): Node? {
        val origins = mutableListOf<Origin>()
        j.get("o")?.forEach { oo ->
            origins.add(Origin(oo.get("doc").asText(), oo.get("path").asText(), oo.get("note")?.asText() ?: ""))
        }
        return when (j.get("t")?.asText()) {
            "missing" -> null
            "scalar" -> {
                val v = j.get("v")
                val value: Any? = when {
                    v == null || v is NullNode || v.isNull -> null
                    v.isBoolean -> v.booleanValue()
                    v.isNumber -> v.decimalValue()
                    else -> v.textValue()
                }
                ScalarNode(value, origins)
            }
            "arr" -> ArrNode(j.get("items").map { fromJson(it)!! }, origins)
            "obj" -> {
                val map = LinkedHashMap<String, Node>()
                val it = j.get("e").fields()
                while (it.hasNext()) {
                    val (k, v) = it.next()
                    map[k] = fromJson(v)!!
                }
                ObjNode(map, origins)
            }
            else -> null
        }
    }
}

data class StoredDoc(
    val id: String,
    val name: String,
    val format: String,
    val raw: String,
    val model: Node,
    val createdAt: Long,
)

/** 文件持久化：原始输入、解析模型、策略版本、裁决历史、合并会话全部落盘。 */
class Store(private val dir: Path) {
    val docs = mutableListOf<StoredDoc>()
    var policyVersion: Long = 0
    val policies = LinkedHashMap<String, ArrayPolicy>()
    val resolutions = mutableListOf<Resolution>()
    val merges = LinkedHashMap<String, MergeSession>()

    init {
        Files.createDirectories(dir)
        Files.createDirectories(dir.resolve("merges"))
        Files.createDirectories(dir.resolve("exports"))
    }

    private fun docDir() = Files.createDirectories(dir.resolve("documents"))

    fun load() {
        val mapper = NodeJson.mapper
        val docsDir = dir.resolve("documents")
        if (Files.isDirectory(docsDir)) {
            Files.list(docsDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.sorted().forEach { f ->
                    val j = mapper.readTree(Files.readString(f))
                    docs.add(
                        StoredDoc(
                            j.get("id").asText(), j.get("name").asText(), j.get("format").asText(),
                            j.get("raw").asText(), NodeJson.fromJson(j.get("model"))!!, j.get("createdAt").asLong(),
                        ),
                    )
                }
            }
        }
        val pf = dir.resolve("policies.json")
        if (Files.exists(pf)) {
            val j = mapper.readTree(Files.readString(pf))
            policyVersion = j.get("version").asLong()
            j.get("policies").forEach { p ->
                policies[p.get("path").asText()] = ArrayPolicy(
                    p.get("path").asText(),
                    ArrayStrategy.valueOf(p.get("strategy").asText()),
                    p.get("idKey")?.asText(),
                )
            }
        }
        val rf = dir.resolve("resolutions.json")
        if (Files.exists(rf)) {
            mapper.readTree(Files.readString(rf)).forEach { r ->
                resolutions.add(Resolution(r.get("path").asText(), r.get("fingerprint").asText(), r.get("choice").asText(), r.get("decidedAt").asLong()))
            }
        }
        val mergesDir = dir.resolve("merges")
        if (Files.isDirectory(mergesDir)) {
            Files.list(mergesDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.sorted().forEach { f ->
                    val s = sessionFromJson(mapper.readTree(Files.readString(f)))
                    merges[s.id] = s
                }
            }
        }
    }

    fun saveDoc(doc: StoredDoc) {
        val j = NodeJson.mapper.createObjectNode()
        j.put("id", doc.id); j.put("name", doc.name); j.put("format", doc.format)
        j.put("raw", doc.raw); j.set<JsonNode>("model", NodeJson.toJson(doc.model)); j.put("createdAt", doc.createdAt)
        Files.writeString(docDir().resolve("${doc.id}.json"), NodeJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(j))
    }

    fun savePolicies() {
        val j = NodeJson.mapper.createObjectNode()
        j.put("version", policyVersion)
        val arr = j.putArray("policies")
        policies.values.forEach { p ->
            val o = arr.addObject()
            o.put("path", p.path); o.put("strategy", p.strategy.name)
            if (p.idKey != null) o.put("idKey", p.idKey)
        }
        Files.writeString(dir.resolve("policies.json"), NodeJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(j))
    }

    fun saveResolutions() {
        val arr = NodeJson.mapper.createArrayNode()
        resolutions.forEach { r ->
            val o = arr.addObject()
            o.put("path", r.path); o.put("fingerprint", r.fingerprint); o.put("choice", r.choice); o.put("decidedAt", r.decidedAt)
        }
        Files.writeString(dir.resolve("resolutions.json"), NodeJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr))
    }

    fun saveMerge(s: MergeSession) {
        Files.writeString(dir.resolve("merges").resolve("${s.id}.json"), NodeJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sessionToJson(s)))
    }

    fun saveExport(id: String, bundle: String) {
        Files.writeString(dir.resolve("exports").resolve("$id.json"), bundle)
    }

    private fun sessionToJson(s: MergeSession): JsonNode {
        val j = NodeJson.mapper.createObjectNode()
        j.put("id", s.id); j.put("baseId", s.baseId); j.put("leftId", s.leftId); j.put("rightId", s.rightId)
        j.put("policyVersion", s.policyVersion); j.put("version", s.version); j.put("createdAt", s.createdAt)
        j.set<JsonNode>("result", NodeJson.toJson(s.result))
        val auto = j.putArray("auto")
        s.auto.forEach { a ->
            val o = auto.addObject()
            o.put("path", a.path); o.put("action", a.action); o.put("source", a.source); o.put("detail", a.detail)
        }
        val cs = j.putArray("conflicts")
        s.conflicts.forEach { c -> cs.add(conflictToJson(c)) }
        return j
    }

    private fun conflictToJson(c: Conflict): JsonNode {
        val o = NodeJson.mapper.createObjectNode()
        o.put("id", c.id); o.put("path", c.path); o.put("reason", c.reason); o.put("fingerprint", c.fingerprint)
        o.set<JsonNode>("base", NodeJson.toJson(c.base))
        o.set<JsonNode>("left", NodeJson.toJson(c.left))
        o.set<JsonNode>("right", NodeJson.toJson(c.right))
        o.set<JsonNode>("suggestion", NodeJson.toJson(c.suggestion))
        o.put("suggestionNote", c.suggestionNote)
        return o
    }

    private fun sessionFromJson(j: JsonNode): MergeSession {
        val auto = j.get("auto").map { a ->
            AutoItem(a.get("path").asText(), a.get("action").asText(), a.get("source").asText(), a.get("detail")?.asText() ?: "")
        }
        val conflicts = j.get("conflicts").map { c ->
            Conflict(
                c.get("id").asText(), c.get("path").asText(), c.get("reason").asText(),
                NodeJson.fromJson(c.get("base")), NodeJson.fromJson(c.get("left")), NodeJson.fromJson(c.get("right")),
                c.get("fingerprint").asText(), NodeJson.fromJson(c.get("suggestion")), c.get("suggestionNote")?.asText() ?: "",
            )
        }
        return MergeSession(
            j.get("id").asText(), j.get("baseId").asText(), j.get("leftId").asText(), j.get("rightId").asText(),
            j.get("policyVersion").asLong(), j.get("version").asLong(),
            NodeJson.fromJson(j.get("result")), auto, conflicts, j.get("createdAt").asLong(),
        )
    }

    fun conflictToPublicJson(c: Conflict): JsonNode = conflictToJson(c)
    fun sessionToPublicJson(s: MergeSession): JsonNode = sessionToJson(s)
}
