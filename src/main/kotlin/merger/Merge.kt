package merger

/** 被自动合并的条目。 */
data class AutoItem(val path: String, val action: String, val source: String, val detail: String = "")

/** 真正的冲突：三方内容指纹 + 可选的（过期）裁决建议。 */
data class Conflict(
    val id: String,
    val path: String,
    val reason: String,
    val base: Node?,
    val left: Node?,
    val right: Node?,
    val fingerprint: String,
    val suggestion: Node? = null,
    val suggestionNote: String = "",
)

/** 人工裁决：绑定祖先与两侧内容指纹；任何一边变化后指纹失配，旧裁决只能作为建议。 */
data class Resolution(
    val path: String,
    val fingerprint: String,
    val choice: String, // left | right | base | delete
    val decidedAt: Long = System.currentTimeMillis(),
)

data class MergeOutcome(
    val result: Node?,
    val auto: List<AutoItem>,
    val conflicts: List<Conflict>,
)

class Merger(
    private val policies: Map<String, ArrayPolicy>,
    private val resolutions: List<Resolution>,
) {
    private val auto = mutableListOf<AutoItem>()
    private val conflicts = mutableListOf<Conflict>()

    fun merge(base: Node?, left: Node?, right: Node?): MergeOutcome {
        val result = mergeAt("$", base, left, right)
        return MergeOutcome(result, auto.toList(), conflicts.toList())
    }

    private fun tag(node: Node?, path: String, note: String): Node? =
        node?.withExtraOrigin(Origin("merge", path, note))

    private fun actionOf(old: Node?, new: Node?): String = when {
        old == null && new != null -> "add"
        old != null && new == null -> "delete"
        else -> "modify"
    }

    private fun mergeAt(path: String, base: Node?, left: Node?, right: Node?): Node? {
        val cBase = Canon.of(base)
        val cLeft = Canon.of(left)
        val cRight = Canon.of(right)
        if (cLeft == cBase && cRight == cBase) return tag(base, path, "unchanged")
        if (cLeft == cBase) {
            auto += AutoItem(path, actionOf(base, right), "right")
            return tag(right, path, "from-right")
        }
        if (cRight == cBase) {
            auto += AutoItem(path, actionOf(base, left), "left")
            return tag(left, path, "from-left")
        }
        if (cLeft == cRight) {
            auto += AutoItem(path, actionOf(base, left), "both")
            return tag(left, path, "both-same")
        }
        // 双方都改了且不一样：对象按键名递归，数组按登记的策略处理，否则冲突。
        if (left is ObjNode && right is ObjNode && (base == null || base is ObjNode)) {
            val b = (base as? ObjNode)?.entries ?: LinkedHashMap()
            val out = LinkedHashMap<String, Node>()
            val keys = LinkedHashSet<String>()
            keys.addAll(b.keys); keys.addAll(left.entries.keys); keys.addAll(right.entries.keys)
            for (k in keys) {
                val child = mergeAt(Parse.childPath(path, k), b[k], left.entries[k], right.entries[k])
                if (child != null) out[k] = child
            }
            return ObjNode(out, mergeOrigins(path, base, left, right))
        }
        if (left is ArrNode && right is ArrNode && (base == null || base is ArrNode)) {
            val b = base as? ArrNode ?: ArrNode(emptyList())
            return mergeArray(path, b, left, right)
        }
        val reason = when {
            base == null -> "both-added-different"
            left == null || right == null -> "delete-vs-modify"
            else -> "both-modified"
        }
        return conflict(path, reason, base, left, right)
    }

    private fun mergeArray(path: String, base: ArrNode, left: ArrNode, right: ArrNode): Node? {
        val policy = policies[path] ?: return conflict(path, "no-array-policy", base, left, right)
        return when (policy.strategy) {
            ArrayStrategy.REPLACE -> conflict(path, "array-replace-both-modified", base, left, right)
            ArrayStrategy.ORDERED -> mergeOrdered(path, base, left, right)
            ArrayStrategy.MERGE_BY_ID -> mergeById(path, base, left, right, policy.idKey ?: "id")
        }
    }

    private fun mergeOrdered(path: String, base: ArrNode, left: ArrNode, right: ArrNode): Node? {
        if (left.items.size == base.items.size && right.items.size == base.items.size) {
            val items = base.items.indices.map { i ->
                mergeAt("$path[$i]", base.items[i], left.items[i], right.items[i]) ?: ScalarNode(null)
            }
            return ArrNode(items, mergeOrigins(path, base, left, right))
        }
        if (left.items.size == base.items.size) {
            auto += AutoItem(path, "modify", "right", "ordered-length")
            return tag(right, path, "from-right")
        }
        if (right.items.size == base.items.size) {
            auto += AutoItem(path, "modify", "left", "ordered-length")
            return tag(left, path, "from-left")
        }
        return conflict(path, "array-length-both-changed", base, left, right)
    }

    private fun idList(arr: ArrNode, idKey: String): List<String>? {
        val ids = arr.items.map { item ->
            val id = ((item as? ObjNode)?.entries?.get(idKey) as? ScalarNode)?.value
            id?.toString() ?: return null
        }
        return if (ids.size == ids.toSet().size) ids else null
    }

    private fun mergeById(path: String, base: ArrNode, left: ArrNode, right: ArrNode, idKey: String): Node? {
        val bIds = idList(base, idKey)
        val lIds = idList(left, idKey)
        val rIds = idList(right, idKey)
        if (bIds == null || lIds == null || rIds == null) {
            return conflict(path, "duplicate-or-missing-id", base, left, right)
        }
        val bMap = bIds.zip(base.items).toMap()
        val lMap = lIds.zip(left.items).toMap()
        val rMap = rIds.zip(right.items).toMap()
        val allIds = LinkedHashSet<String>()
        allIds.addAll(bIds); allIds.addAll(lIds); allIds.addAll(rIds)
        val merged = HashMap<String, Node?>()
        for (id in allIds) {
            merged[id] = mergeAt("$path[#$id]", bMap[id], lMap[id], rMap[id])
        }
        // 结果顺序：一侧未动则跟随另一侧；都动了则以祖先顺序为主，新增项按左、右顺序追加。
        val order = when {
            lIds == bIds -> rIds
            rIds == bIds -> lIds
            else -> bIds.filter { it in lMap || it in rMap } +
                lIds.filter { it !in bMap } +
                rIds.filter { it !in bMap && it !in lMap }
        }
        val items = order.filter { merged[it] != null }.map { merged[it]!! }
        return ArrNode(items, mergeOrigins(path, base, left, right))
    }

    private fun conflict(path: String, reason: String, base: Node?, left: Node?, right: Node?): Node? {
        val fp = Canon.sha256(listOf(path, Canon.of(base), Canon.of(left), Canon.of(right)).joinToString("\n"))
        val res = resolutions.find { it.path == path }
        if (res != null && res.fingerprint == fp) {
            auto += AutoItem(path, "resolution-replay", "decision", res.choice)
            return tag(applyChoice(res.choice, base, left, right), path, "resolved:${res.choice}")
        }
        val suggestion = if (res != null) applyChoice(res.choice, base, left, right) else null
        val note = if (res != null) "stale-resolution:${res.choice}" else ""
        conflicts += Conflict("c-" + fp.take(12), path, reason, base, left, right, fp, suggestion, note)
        return tag(base ?: left ?: right, path, "conflict-placeholder")
    }

    private fun applyChoice(choice: String, base: Node?, left: Node?, right: Node?): Node? = when (choice) {
        "left" -> left
        "right" -> right
        "base" -> base
        "delete" -> null
        else -> right
    }

    private fun mergeOrigins(path: String, vararg nodes: Node?): List<Origin> {
        val seen = LinkedHashSet<Origin>()
        for (n in nodes) if (n != null) seen.addAll(n.origins)
        seen.add(Origin("merge", path, "merged"))
        return seen.toList()
    }
}
