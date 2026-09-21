package merger

/** 数组合并策略：按路径登记，未登记不得猜。 */
enum class ArrayStrategy { REPLACE, BY_ID, SEQUENCE }

data class ArrayPolicy(val path: String, val strategy: ArrayStrategy, val idKey: String = "id")

data class PolicySet(val version: String, val arrays: Map<String, ArrayPolicy>) {
    fun at(path: List<Seg>): ArrayPolicy? = arrays[pathToString(path)]
}

/** 人工裁决：绑定祖先与两侧内容指纹；任一侧变化后仅作建议。 */
data class Decision(
    val id: String,
    val path: String,
    val baseFp: String,
    val oursFp: String,
    val theirsFp: String,
    val choice: String,            // base | ours | theirs | delete | value
    val value: CNode? = null,      // choice=value 时的手工值
    val createdAt: Long = System.currentTimeMillis()
)

data class Conflict(
    val path: String,
    val reason: String,            // both-modified | delete-vs-modify | type-change | no-array-policy | duplicate-id | both-reordered | both-modified-sequence
    val detail: String,
    val baseFp: String,
    val oursFp: String,
    val theirsFp: String,
    val base: CNode?,
    val ours: CNode?,
    val theirs: CNode?,
    var suggestion: Decision? = null,   // 指纹不再匹配的旧裁决，仅作建议
    var appliedDecisionId: String? = null
)

data class AutoEntry(val path: String, val origins: List<Origin>, val fingerprint: String)

data class MergeResult(
    val output: CNode?,
    val auto: List<AutoEntry>,
    val conflicts: List<Conflict>
)

class Merger(
    private val policies: PolicySet,
    private val decisions: List<Decision> = emptyList()
) {
    private val auto = mutableListOf<AutoEntry>()
    private val conflicts = mutableListOf<Conflict>()
    private val decisionsByPath = decisions.groupBy { it.path }

    fun merge(base: CNode?, ours: CNode?, theirs: CNode?): MergeResult {
        // 策略路径预检：结构违规（重复 id 等）即使被高层捷径吞掉也必须报冲突。
        val violations = findPolicyViolations(base, ours, theirs)
        var out = mergeNode(base, ours, theirs, emptyList())
        for ((p, v) in violations) {
            if (conflicts.any { it.path == p }) continue // 递归中已报过
            val segs = pathFromString(p)
            val ph = conflict(segs, v.first, v.second,
                nodeAt(base, segs), nodeAt(ours, segs), nodeAt(theirs, segs))
            out = splice(out, segs, ph)
        }
        return MergeResult(out, auto.toList(), conflicts.toList())
    }

    /** 校验所有 BY_ID 策略路径上的数组结构，返回 路径 -> (原因, 详情)。 */
    private fun findPolicyViolations(
        base: CNode?, ours: CNode?, theirs: CNode?
    ): Map<String, Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (pol in policies.arrays.values.filter { it.strategy == ArrayStrategy.BY_ID }) {
            val segs = pathFromString(pol.path)
            for ((label, doc) in listOf("base" to base, "ours" to ours, "theirs" to theirs)) {
                val arr = nodeAt(doc, segs) as? CNode.CArray ?: continue
                val seenIds = HashSet<String>()
                for (el in arr.items) {
                    val id = idOf(el, pol.idKey)
                    if (id == null) {
                        out[pol.path] = "missing-id" to "$label 中存在缺少 ${pol.idKey} 的元素"
                        break
                    }
                    if (!seenIds.add(id)) {
                        out[pol.path] = "duplicate-id" to "$label 中 id=$id 重复出现"
                        break
                    }
                }
                if (out.containsKey(pol.path)) break
            }
        }
        return out
    }

    /** 把 path 处的值替换为 value（null 表示删除），返回新树。 */
    private fun splice(root: CNode?, path: List<Seg>, value: CNode?): CNode? {
        if (path.isEmpty()) return value
        return when (val head = path.first()) {
            is Seg.Key -> {
                val obj = root as? CNode.CObject ?: return root
                val entries = LinkedHashMap(obj.entries)
                val child = splice(entries[head.name], path.drop(1), value)
                if (child == null) entries.remove(head.name) else entries[head.name] = child
                obj.copy(entries = entries)
            }
            is Seg.Idx -> {
                val arr = root as? CNode.CArray ?: return root
                if (head.index !in arr.items.indices) return root
                val items = arr.items.toMutableList()
                val child = splice(items[head.index], path.drop(1), value)
                if (child == null) items.removeAt(head.index) else items[head.index] = child
                arr.copy(items = items)
            }
        }
    }

    private fun recordAuto(path: List<Seg>, node: CNode?) {
        auto.add(AutoEntry(pathToString(path), node?.origins ?: listOf(Origin("delete", "removed")), fingerprint(node)))
    }

    private fun conflict(
        path: List<Seg>, reason: String, detail: String,
        base: CNode?, ours: CNode?, theirs: CNode?
    ): CNode? {
        val ps = pathToString(path)
        val c = Conflict(
            path = ps, reason = reason, detail = detail,
            baseFp = fingerprint(base), oursFp = fingerprint(ours), theirsFp = fingerprint(theirs),
            base = base?.deepCopy(), ours = ours?.deepCopy(), theirs = theirs?.deepCopy()
        )
        // 裁决匹配：路径相同且三侧指纹完全一致 → 自动套用；否则仅作建议。
        for (d in decisionsByPath[ps].orEmpty()) {
            if (d.baseFp == c.baseFp && d.oursFp == c.oursFp && d.theirsFp == c.theirsFp) {
                c.appliedDecisionId = d.id
                return applyChoice(d.choice, d.value, c)?.withOrigins(
                    listOf(Origin("base"), Origin("decision:${d.id}", "manual-replay"))
                )
            } else {
                c.suggestion = d
            }
        }
        conflicts.add(c)
        return CNode.CScalar("⟦CONFLICT:$reason⟧", listOf(Origin("conflict", reason)))
    }

    private fun applyChoice(choice: String, value: CNode?, c: Conflict): CNode? = when (choice) {
        "base" -> c.base?.deepCopy()
        "ours" -> c.ours?.deepCopy()
        "theirs" -> c.theirs?.deepCopy()
        "delete" -> null
        "value" -> value?.deepCopy()
        else -> throw IllegalArgumentException("未知裁决选项: $choice")
    }

    private fun mergeNode(base: CNode?, ours: CNode?, theirs: CNode?, path: List<Seg>): CNode? {
        // BY_ID 策略路径：先做结构校验（重复 id / 缺 id），即使某侧未变也不跳过。
        policies.at(path)?.takeIf { it.strategy == ArrayStrategy.BY_ID }?.let { pol ->
            for ((label, arr) in listOf("base" to base, "ours" to ours, "theirs" to theirs)) {
                if (arr !is CNode.CArray) continue
                val seenIds = HashSet<String>()
                for (el in arr.items) {
                    val id = idOf(el, pol.idKey)
                        ?: return conflict(path, "missing-id",
                            "$label 中存在缺少 ${pol.idKey} 的元素", base, ours, theirs)
                    if (!seenIds.add(id)) {
                        return conflict(path, "duplicate-id", "$label 中 id=$id 重复出现", base, ours, theirs)
                    }
                }
            }
        }
        val bf = fingerprint(base); val of = fingerprint(ours); val tf = fingerprint(theirs)
        if (of == tf) { // 两侧一致（含都删除）
            if (of != bf) recordAuto(path, ours)
            return ours?.deepCopy()?.withOrigins(
                if (of == bf) listOf(Origin("base", "unchanged"))
                else listOf(Origin("base"), Origin("ours"), Origin("theirs", "both-same"))
            )
        }
        if (of == bf) { // ours 未动，取 theirs
            recordAuto(path, theirs)
            return theirs?.deepCopy()?.withOrigins(listOf(Origin("base"), Origin("theirs", "modified")))
        }
        if (tf == bf) { // theirs 未动，取 ours
            recordAuto(path, ours)
            return ours?.deepCopy()?.withOrigins(listOf(Origin("base"), Origin("ours", "modified")))
        }
        // 三侧均不同
        if (base == null) { // 两侧各自新增且不同
            return conflict(path, "both-added", "两侧在同一位置新增了不同的值", base, ours, theirs)
        }
        if (ours == null || theirs == null) { // 一侧删除、一侧修改
            return conflict(path, "delete-vs-modify",
                if (ours == null) "ours 删除了该值，theirs 修改了它" else "theirs 删除了该值，ours 修改了它",
                base, ours, theirs)
        }
        if (base is CNode.CObject && ours is CNode.CObject && theirs is CNode.CObject) {
            return mergeObject(base, ours, theirs, path)
        }
        if (base is CNode.CArray && ours is CNode.CArray && theirs is CNode.CArray) {
            return mergeArray(base, ours, theirs, path)
        }
        val reason = if (base::class != ours::class || base::class != theirs::class) "type-change" else "both-modified"
        return conflict(path, reason, "两侧对该值做了不同修改", base, ours, theirs)
    }

    private fun mergeObject(
        base: CNode.CObject, ours: CNode.CObject, theirs: CNode.CObject, path: List<Seg>
    ): CNode {
        val keys = LinkedHashSet<String>()
        keys.addAll(base.entries.keys); keys.addAll(ours.entries.keys); keys.addAll(theirs.entries.keys)
        val out = LinkedHashMap<String, CNode>()
        for (k in keys) {
            val child = mergeNode(base.entries[k], ours.entries[k], theirs.entries[k], path + Seg.Key(k))
            if (child != null) out[k] = child
        }
        return CNode.CObject(out, listOf(Origin("merge", "object")))
    }

    // ---------- 数组策略 ----------

    private fun mergeArray(
        base: CNode.CArray, ours: CNode.CArray, theirs: CNode.CArray, path: List<Seg>
    ): CNode? {
        val policy = policies.at(path)
            ?: return conflict(path, "no-array-policy",
                "路径 ${pathToString(path)} 未登记数组策略，拒绝猜测", base, ours, theirs)
        return when (policy.strategy) {
            ArrayStrategy.REPLACE -> conflict(path, "both-modified-array",
                "REPLACE 策略下两侧都修改了数组，需人工选择", base, ours, theirs)
            ArrayStrategy.BY_ID -> mergeById(base, ours, theirs, path, policy)
            ArrayStrategy.SEQUENCE -> mergeSequence(base, ours, theirs, path)
        }
    }

    private fun idOf(n: CNode, idKey: String): String? =
        (n as? CNode.CObject)?.entries?.get(idKey)?.let { (it as? CNode.CScalar)?.value?.toString() }

    private fun mergeById(
        base: CNode.CArray, ours: CNode.CArray, theirs: CNode.CArray,
        path: List<Seg>, policy: ArrayPolicy
    ): CNode? {
        // 重复 id 检测：任一文档内 id 重复即冲突，不猜。
        for ((label, arr) in listOf("base" to base, "ours" to ours, "theirs" to theirs)) {
            val seen = HashSet<String>()
            for (el in arr.items) {
                val id = idOf(el, policy.idKey)
                    ?: return conflict(path, "missing-id",
                        "$label 中存在缺少 ${policy.idKey} 的元素", base, ours, theirs)
                if (!seen.add(id)) {
                    return conflict(path, "duplicate-id",
                        "$label 中 id=$id 重复出现", base, ours, theirs)
                }
            }
        }
        fun index(arr: CNode.CArray) = arr.items.associateBy { idOf(it, policy.idKey)!! }
        val bIdx = index(base); val oIdx = index(ours); val tIdx = index(theirs)
        val ids = LinkedHashSet<String>()
        ids.addAll(bIdx.keys); ids.addAll(oIdx.keys); ids.addAll(tIdx.keys)

        val merged = LinkedHashMap<String, CNode>()
        for (id in ids) {
            val child = mergeNode(bIdx[id], oIdx[id], tIdx[id], path + Seg.Key("#$id"))
            if (child != null) merged[id] = child
        }
        // 顺序：以 base 顺序为基准，对公共 id 的相对顺序做三方比较；新增 id 追加。
        val order = mergeOrder(
            base.items.map { idOf(it, policy.idKey)!! },
            ours.items.map { idOf(it, policy.idKey)!! },
            theirs.items.map { idOf(it, policy.idKey)!! },
            merged.keys
        ) ?: return conflict(path, "both-reordered",
            "两侧对元素顺序做了不同调整", base, ours, theirs)
        val items = order.mapNotNull { merged[it] }
        return CNode.CArray(items, listOf(Origin("merge", "by-id:${policy.idKey}")))
    }

    /** 公共元素相对顺序的三方合并；两侧顺序冲突时返回 null。 */
    private fun mergeOrder(
        baseIds: List<String>, oursIds: List<String>, theirsIds: List<String>,
        surviving: Set<String>
    ): List<String>? {
        fun filtered(ids: List<String>) = ids.filter { it in surviving }
        val b = filtered(baseIds); val o = filtered(oursIds); val t = filtered(theirsIds)
        val common = b.toSet()
        fun relOrder(ids: List<String>) = ids.filter { it in common }
        val ro = relOrder(o); val rt = relOrder(t); val rb = relOrder(b)
        val backbone = when {
            ro == rt -> ro
            ro == rb -> rt   // ours 未动顺序，取 theirs
            rt == rb -> ro   // theirs 未动顺序，取 ours
            else -> return null
        }
        // 追加两侧新增（不在 base 中的）元素，ours 优先、保持各自相对顺序。
        val additions = (o.filter { it !in baseIds.toSet() } + t.filter { it !in baseIds.toSet() })
            .distinct().filter { it in surviving }
        return backbone + additions
    }

    private fun isPermutation(a: CNode.CArray, b: CNode.CArray): Boolean {
        if (a.items.size != b.items.size) return false
        val fa = a.items.map { fingerprint(it) }.sorted()
        val fb = b.items.map { fingerprint(it) }.sorted()
        return fa == fb
    }

    private fun mergeSequence(
        base: CNode.CArray, ours: CNode.CArray, theirs: CNode.CArray, path: List<Seg>
    ): CNode? {
        val baseFp = fingerprint(base); val oursFp = fingerprint(ours); val theirsFp = fingerprint(theirs)
        // 纯顺序移动：一侧只是重排，另一侧未变 → 采用重排侧。
        val oursPerm = isPermutation(base, ours)
        val theirsPerm = isPermutation(base, theirs)
        if (oursPerm && theirsPerm && oursFp != theirsFp) {
            return conflict(path, "both-reordered", "两侧对序列做了不同的重排", base, ours, theirs)
        }
        if (ours.items.size == theirs.items.size && ours.items.size == base.items.size) {
            // 等长：按下标逐项三方合并（顺序即语义）。
            val items = base.items.indices.mapNotNull { i ->
                mergeNode(base.items[i], ours.items[i], theirs.items[i], path + Seg.Idx(i))
            }
            return CNode.CArray(items, listOf(Origin("merge", "sequence")))
        }
        return conflict(path, "both-modified-sequence",
            "两侧都修改了有序序列（长度变化），需人工裁决", base, ours, theirs)
    }
}
