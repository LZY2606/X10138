package semmerge

import java.util.UUID

/** 人工裁决：绑定祖先与两侧内容指纹；任何一边变化后只能作为建议。 */
data class Decision(
    val id: String,
    val path: String,
    val baseFp: String,
    val leftFp: String,
    val rightFp: String,
    val chosen: Node,
    val note: String = "",
    val ts: Long = System.currentTimeMillis()
) {
    fun matches(b: Node, l: Node, r: Node): Boolean =
        baseFp == fingerprint(b) && leftFp == fingerprint(l) && rightFp == fingerprint(r)
}

data class AutoEntry(val path: String, val reason: String, val sources: List<SourceRef>)

data class Conflict(
    val id: String,
    val path: String,
    val reason: String,
    val base: Node,
    val left: Node,
    val right: Node,
    val suggestion: Decision? = null
)

data class MergeResult(
    val root: Node,
    val auto: List<AutoEntry>,
    val conflicts: List<Conflict>,
    val fingerprint: String
)

class Merger(
    private val policies: PolicySet,
    private val decisions: List<Decision> = emptyList()
) {
    private val auto = mutableListOf<AutoEntry>()
    private val conflicts = mutableListOf<Conflict>()

    fun merge(base: Node, left: Node, right: Node): MergeResult {
        auto.clear(); conflicts.clear()
        val root = m(emptyList(), base, left, right)
        return MergeResult(root, auto.toList(), conflicts.toList(), fingerprint(root))
    }

    private fun adopt(node: Node, path: Path, reason: String, extraSrc: SourceRef? = null): Node {
        if (node !== MissingNode) {
            val src = node.provenance + listOfNotNull(extraSrc)
            auto.add(AutoEntry(renderPath(path), reason, src))
        }
        return node
    }

    private fun conflict(path: Path, reason: String, b: Node, l: Node, r: Node): Node {
        val rendered = renderPath(path)
        val stale = decisions.lastOrNull { it.path == rendered }
        conflicts.add(
            Conflict(
                id = "c-" + fingerprint(ScalarNode(rendered)).take(12),
                path = rendered, reason = reason, base = b, left = l, right = r,
                suggestion = stale
            )
        )
        // 占位值：优先祖先，其次左侧；最终以裁决后的重新合并为准。
        return when {
            b !== MissingNode -> b
            l !== MissingNode -> l
            else -> r
        }
    }

    private fun m(path: Path, b: Node, l: Node, r: Node): Node {
        if (structEq(l, r)) return if (l === MissingNode) MissingNode
            else adopt(l, path, "两侧一致")
        if (structEq(b, l)) return adopt(r, path, "仅右侧变更")
        if (structEq(b, r)) return adopt(l, path, "仅左侧变更")

        // 三边都不同：先看是否有指纹完全匹配的裁决可重放
        val rendered = renderPath(path)
        val hit = decisions.lastOrNull { it.path == rendered && it.matches(b, l, r) }
        if (hit != null) {
            return adopt(
                hit.chosen.withProvenance(
                    hit.chosen.provenance + SourceRef("decision", rendered, "重放裁决 ${hit.id}")
                ),
                path, "应用裁决 ${hit.id}", SourceRef("decision", rendered, hit.id)
            )
        }

        if (l is ObjNode && r is ObjNode && (b is ObjNode || b === MissingNode)) {
            return mergeObjects(path, b, l, r)
        }
        if (l is ArrNode && r is ArrNode && (b is ArrNode || b === MissingNode)) {
            return mergeArrays(path, b, l, r)
        }
        val reason = when {
            l === MissingNode -> "左侧删除，右侧修改"
            r === MissingNode -> "右侧删除，左侧修改"
            l is NullNode || r is NullNode -> "一侧设为 null，另一侧改为其他值"
            else -> "两侧改为不兼容的值"
        }
        return conflict(path, reason, b, l, r)
    }

    private fun mergeObjects(path: Path, b: Node, l: ObjNode, r: ObjNode): Node {
        val bE = (b as? ObjNode)?.entries ?: LinkedHashMap()
        val keys = LinkedHashSet<String>()
        keys.addAll(bE.keys); keys.addAll(l.entries.keys); keys.addAll(r.entries.keys)
        val out = LinkedHashMap<String, Node>()
        for (k in keys) {
            val child = m(
                path + PathSeg.Key(k),
                bE[k] ?: MissingNode,
                l.entries[k] ?: MissingNode,
                r.entries[k] ?: MissingNode
            )
            if (child !== MissingNode) out[k] = child
        }
        val prov = (b.provenance + l.provenance + r.provenance).distinct()
        return ObjNode(out, prov)
    }

    private fun mergeArrays(path: Path, b: Node, l: ArrNode, r: ArrNode): Node {
        val policy = policies.policyFor(path)
            ?: return conflict(path, "数组路径未登记合并策略，拒绝猜测", b, l, r)
        val bArr = (b as? ArrNode) ?: ArrNode(emptyList())
        return when (policy.strategy) {
            ArrayStrategy.REPLACE ->
                conflict(path, "策略为整体替换，但两侧都做了不同修改", b, l, r)
            ArrayStrategy.MERGE_BY_ID -> mergeById(path, bArr, l, r, policy)
            ArrayStrategy.ORDERED -> mergeOrdered(path, bArr, l, r)
        }
    }

    // ---------- 按稳定 id 合并 ----------

    private fun idOf(n: Node, idKey: String): String? =
        ((n as? ObjNode)?.entries?.get(idKey) as? ScalarNode)?.value?.toString()

    private fun mergeById(path: Path, b: ArrNode, l: ArrNode, r: ArrNode, policy: ArrayPolicy): Node {
        val idKey = policy.idKey ?: return conflict(path, "MERGE_BY_ID 策略缺少 idKey", b, l, r)
        val rendered = renderPath(path)

        fun index(arr: ArrNode, side: String): LinkedHashMap<String, Node>? {
            val map = LinkedHashMap<String, Node>()
            for (item in arr.items) {
                val id = idOf(item, idKey)
                    ?: return null.also {
                        conflicts.add(Conflict("c-dup-$rendered-$side", rendered,
                            "$side 侧元素缺少 id 字段 '$idKey'", b, l, r))
                    }
                if (map.put(id, item) != null) {
                    conflicts.add(Conflict("c-dup-$rendered-$side", rendered,
                        "$side 侧存在重复 id '$id'", b, l, r))
                    return null
                }
            }
            return map
        }

        val bM = index(b, "祖先") ?: return b
        val lM = index(l, "左侧") ?: return b
        val rM = index(r, "右侧") ?: return b

        val allIds = LinkedHashSet<String>()
        allIds.addAll(bM.keys); allIds.addAll(lM.keys); allIds.addAll(rM.keys)

        val merged = LinkedHashMap<String, Node>()
        for (id in allIds) {
            val child = m(
                path + PathSeg.Key("[$id]"),
                bM[id] ?: MissingNode, lM[id] ?: MissingNode, rM[id] ?: MissingNode
            )
            if (child !== MissingNode) merged[id] = child
        }

        // 顺序：未主动重排的一侧跟随另一侧；新增元素按各自侧顺序追加。
        val surviving = merged.keys
        val bSeq = bM.keys.filter { it in surviving }
        val lSeq = lM.keys.filter { it in surviving }
        val rSeq = rM.keys.filter { it in surviving }
        val primary = when {
            lSeq == bSeq -> rSeq
            rSeq == bSeq -> lSeq
            lSeq == rSeq -> lSeq
            else -> {
                conflicts.add(Conflict("c-order-$rendered", rendered,
                    "两侧对元素做了不同的重排", b, l, r))
                lSeq
            }
        }
        val orderedIds = primary + surviving.filter { it !in primary }
        val prov = (b.provenance + l.provenance + r.provenance).distinct()
        return ArrNode(orderedIds.map { merged.getValue(it) }, prov)
    }

    // ---------- 有序序列三方合并 ----------

    private data class Hunk(val start: Int, val end: Int, val replacement: List<Node>)

    /** 基于 LCS 的 diff：把 base 变为 modified 所需的最小替换块。 */
    private fun diff(base: List<Node>, modified: List<Node>): List<Hunk> {
        val n = base.size; val mLen = modified.size
        val dp = Array(n + 1) { IntArray(mLen + 1) }
        for (i in n - 1 downTo 0)
            for (j in mLen - 1 downTo 0)
                dp[i][j] = if (structEq(base[i], modified[j])) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
        val hunks = mutableListOf<Hunk>()
        var i = 0; var j = 0
        var start = -1
        val repl = mutableListOf<Node>()
        fun flush(end: Int) {
            if (start >= 0) { hunks.add(Hunk(start, end, repl.toList())); start = -1; repl.clear() }
        }
        while (i < n && j < mLen) {
            if (structEq(base[i], modified[j])) { flush(i); i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { if (start < 0) start = i; i++ }
            else { if (start < 0) start = i; repl.add(modified[j]); j++ }
        }
        while (i < n) { if (start < 0) start = i; i++ }
        while (j < mLen) { if (start < 0) start = n; repl.add(modified[j]); j++ }
        flush(n)
        return hunks
    }

    private fun mergeOrdered(path: Path, b: ArrNode, l: ArrNode, r: ArrNode): Node {
        val lh = diff(b.items, l.items)
        val rh = diff(b.items, r.items)
        val all = (lh.map { it to "left" } + rh.map { it to "right" })
            .sortedWith(compareBy({ it.first.start }, { it.first.end }))
        val accepted = mutableListOf<Hunk>()
        var i = 0
        while (i < all.size) {
            var j = i + 1
            var end = all[i].first.end
            while (j < all.size && all[j].first.start < end) {
                end = maxOf(end, all[j].first.end); j++
            }
            val group = all.subList(i, j).map { it.first }
            if (group.size == 1) {
                accepted.add(group[0])
            } else {
                val first = group[0]
                val same = group.all { it.start == first.start && it.end == first.end &&
                    it.replacement.size == first.replacement.size &&
                    it.replacement.zip(first.replacement).all { (a, c) -> structEq(a, c) } }
                if (same) accepted.add(first)
                else return conflict(path, "两侧修改了有序序列的同一区段", b, l, r)
            }
            i = j
        }
        val out = mutableListOf<Node>()
        var cursor = 0
        for (h in accepted.sortedBy { it.start }) {
            for (k in cursor until h.start) out.add(b.items[k])
            out.addAll(h.replacement)
            cursor = h.end
        }
        for (k in cursor until b.items.size) out.add(b.items[k])
        auto.add(AutoEntry(renderPath(path), "有序序列合并（${accepted.size} 个变更块）",
            (l.provenance + r.provenance).distinct()))
        val prov = (b.provenance + l.provenance + r.provenance).distinct()
        return ArrNode(out, prov)
    }
}

fun newId(prefix: String): String = "$prefix-" + UUID.randomUUID().toString().take(8)
