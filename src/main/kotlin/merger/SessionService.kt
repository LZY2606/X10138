package merger

import java.time.Instant
import java.util.UUID

class ConflictBusyException(message: String) : RuntimeException(message)
class BadInputException(message: String) : RuntimeException(message)

class SessionService(val store: Store) {

    private val cache = HashMap<String, Session>()

    fun create(title: String = "未命名合并"): Session {
        val s = Session(
            id = UUID.randomUUID().toString().take(8),
            createdAt = Instant.now(),
            title = title,
            base = null, a = null, b = null,
            registry = StrategyRegistry.EMPTY,
            outputFormat = Format.YAML,
            version = 0
        )
        store.saveSession(s)
        cache[s.id] = s
        return s
    }

    fun list(): List<Session> = store.listSessions()
    fun get(id: String): Session =
        cache[id] ?: store.loadSession(id)?.also { cache[id] = it }
        ?: throw BadInputException("会话不存在: $id")

    fun setInput(id: String, side: Source, raw: String, format: Format?): Session {
        val s = get(id)
        val doc = ConfigParser.parse(raw, side, format)
        val input = InputDoc(doc.format, raw, doc.fingerprint)
        when (side) {
            Source.BASE -> s.base = input
            Source.A -> s.a = input
            Source.B -> s.b = input
        }
        s.version++
        s.lastOutcome = null
        store.saveSession(s)
        return s
    }

    fun setOutputFormat(id: String, format: Format): Session {
        val s = get(id)
        s.outputFormat = format
        store.saveSession(s)
        return s
    }

    fun registerStrategy(id: String, rawPath: String, strategy: ArrayStrategy, idField: String): Session {
        val s = get(id)
        val path = try { Path.parse(rawPath) } catch (e: Exception) {
            throw BadInputException("策略路径无效: ${e.message}")
        }
        s.registry = s.registry.with(path, strategy, idField.ifBlank { "id" })
        s.version++
        s.lastOutcome = null
        store.saveSession(s)
        return s
    }

    fun removeStrategy(id: String, rawPath: String): Session {
        val s = get(id)
        val p = Path.parse(rawPath).render()
        s.registry = StrategyRegistry(s.registry.entries - p, s.registry.version + 1)
        s.version++
        store.saveSession(s)
        return s
    }

    fun isReady(s: Session): Boolean = s.base != null && s.a != null && s.b != null

    fun remerge(s: Session): MergeOutcome {
        if (!isReady(s)) throw BadInputException("请先提供共同祖先与两个分支三份输入")
        val input = MergeInput(
            base = toParsed(s.base!!, Source.BASE),
            a = toParsed(s.a!!, Source.A),
            b = toParsed(s.b!!, Source.B),
            registry = s.registry,
            decisions = s.decisions,
            outputFormat = s.outputFormat,
            sessionVersion = s.version
        )
        val outcome = Merger.merge(input)
        s.lastOutcome = outcome
        store.saveSession(s)
        return outcome
    }

    private fun toParsed(d: InputDoc, source: Source): ParsedDoc =
        ConfigParser.parse(d.raw, source, d.format)

    /**
     * 乐观批量提交：
     *  - baseVersion 过期 -> 整批拒绝（409），页面需重新合并后重试；
     *  - 任意目标项已被他人解决 -> 整批拒绝，报告每个被占用项；
     *  - 全部通过才原子写入，保证后到者不能覆盖先解决者。
     */
    fun submitDecisions(id: String, req: SubmitRequest): DecisionResult {
        val s = get(id)
        if (req.baseVersion != s.version) {
            throw ConflictBusyException("会话版本过期：页面基于 v${req.baseVersion}，当前为 v${s.version}，请刷新合并结果后重试。")
        }
        val outcome = s.lastOutcome ?: remerge(s)
        val rejected = mutableListOf<RejectedItem>()
        for (item in req.items) {
            val node = outcome.conflictIndex[item.path]
            if (node == null) {
                rejected.add(RejectedItem(item.path, "该路径当前已不是冲突（可能已被自动合并或结构变化）", null))
            } else if (node.resolvedBy != null) {
                rejected.add(RejectedItem(item.path, "已被其他人/更早的提交解决", node.resolvedBy!!.decisionId))
            }
        }
        if (rejected.isNotEmpty()) {
            throw ConflictBusyException("部分冲突项已变化，整批未写入。")
        }
        val accepted = mutableListOf<String>()
        val now = Instant.now()
        for (item in req.items) {
            val node = outcome.conflictIndex.getValue(item.path)
            validateChoice(node, item)
            val d = Decision(
                id = "dec-" + UUID.randomUUID().toString().take(10),
                path = item.path,
                choiceId = item.choiceId,
                customText = item.customText,
                customFormat = item.customFormat ?: Format.YAML,
                baseFingerprint = s.base!!.fingerprint,
                aFingerprint = s.a!!.fingerprint,
                bFingerprint = s.b!!.fingerprint,
                conflictKind = node.conflict!!.kind,
                createdAt = now,
                author = req.author.ifBlank { "local" },
                baseVersion = s.version
            )
            s.decisions.add(d)
            store.appendHistory(s.id, d)
            accepted.add(d.id)
        }
        s.version++
        remerge(s)
        store.saveSession(s)
        return DecisionResult(accepted, emptyList(), s.version)
    }

    private fun validateChoice(node: MergeNode, item: SubmitItem) {
        val ids = node.conflict!!.choices.map { it.id }
        if (item.choiceId == "CUSTOM") {
            if (item.customText.isNullOrBlank()) throw BadInputException("自定义裁决缺少内容")
            try { ConfigParser.parse(item.customText, Source.A, item.customFormat ?: Format.YAML) }
            catch (e: Exception) { throw BadInputException("自定义裁决文本无法解析: ${e.message}") }
        } else if (item.choiceId !in ids) {
            throw BadInputException("路径 ${item.path} 不支持选择 ${item.choiceId}，可选: $ids")
        }
    }

    fun history(id: String): List<Decision> = get(id).decisions.sortedByDescending { it.createdAt }

    fun clearAdvisoryReplay(id: String): Session = get(id) // 占位：弱绑定裁决从不自动套用
}
