package merger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.exists

/**
 * 文件持久化。所有原始输入、解析指纹、策略版本、裁决历史、最近输出都落盘。
 * data/<sessionId>/
 *   session.json   —— 完整会话（含输入、策略、裁决列表与最近结果指纹）
 *   history.log    —— 追加式裁决历史（审计，不重写）
 */
class Store(private val root: Path) {

    init { Files.createDirectories(root) }

    private fun dir(id: String): Path = root.resolve(id).also { Files.createDirectories(it) }

    fun saveSession(s: Session) {
        val d = dir(s.id)
        val jv = SessionCodec.encode(s)
        val tmp = d.resolve("session.json.tmp")
        Files.writeString(tmp, JsonCodec.stringify(jv))
        Files.move(tmp, d.resolve("session.json"),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun loadSession(id: String): Session? {
        val f = root.resolve(id).resolve("session.json")
        if (!f.exists()) return null
        return SessionCodec.decode(JsonCodec.parse(Files.readString(f)))
    }

    fun listSessions(): List<Session> {
        if (!Files.exists(root)) return emptyList()
        val out = mutableListOf<Session>()
        Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) && it.resolve("session.json").exists() }.forEach { d ->
                loadSession(d.fileName.toString())?.let { out.add(it) }
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun appendHistory(sessionId: String, d: Decision) {
        val f = dir(sessionId).resolve("history.log")
        val line = JsonCodec.stringify(
            SessionCodec.decisionJV(d), pretty = false
        ).trim()
        Files.writeString(f, line + "\n",
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
}
