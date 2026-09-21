package semmerge

data class AutoChange(val path: String, val description: String, val prov: List<Prov>)

data class Conflict(
    val id: String,
    val path: String,
    val reason: String,
    val base: Node?,
    val ours: Node?,
    val theirs: Node?
) {
    val baseFp: String? get() = base?.let { fingerprint(it) }
    val oursFp: String? get() = ours?.let { fingerprint(it) }
    val theirsFp: String? get() = theirs?.let { fingerprint(it) }
}

data class Decision(
    val path: String,
    val baseFp: String?,
    val oursFp: String?,
    val theirsFp: String?,
    val choice: String,           // ours | theirs | base | custom
    val customText: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun matches(c: Conflict): Boolean =
        baseFp == c.baseFp && oursFp == c.oursFp && theirsFp == c.theirsFp
}

data class Suggestion(val path: String, val decision: Decision, val note: String)

data class MergeResult(
    val merged: Node?,
    val autoMerged: List<AutoChange>,
    val conflicts: List<Conflict>,
    val applied: List<Decision>,
    val suggestions: List<Suggestion>
)

class MergeEngine(
    private val policies: PolicySet,
    private val decisions: Map<String, List<Decision>> = emptyMap() // key = path
) {
    private val auto = mutableListOf<AutoChange>()
    private val conflicts = mutableListOf<Conflict>()
    private val applied = mutableListOf<Decision>()
    private val suggestions = mutableListOf<Suggestion>()

    fun merge(base: Node?, ours: Node?, theirs: Node?): MergeResult {
        val root = mergeNode(base, ours, theirs, "$")
        return MergeResult(root, auto, conflicts, applied, suggestions)
    }

    /** 三向合并。b/o/t 为 null 表示该侧缺失（删除或从未存在），与 NullNode（显式 null）严格区分。 */
    private fun mergeNode(b: Node?, o: Node?, t: Node?, path: String): Node? {
        if (structEq(o, t)) {
            if (!structEq(b, o)) recordAuto(path, "两侧一致变更", o)
            return o?.let { deepCopy(it) }
        }
        if (structEq(b, o)) {
            recordAuto(path, if (t == null) "theirs 删除" else "theirs 修改", t)
            return t?.let { deepCopy(it) }
        }
        if (structEq(b, t)) {
            recordAuto(path, if (o == null) "ours 删除" else "ours 修改", o)
            return o?.let { deepCopy(it) }
        }
        // 双侧不同变更
        if (o is Node.Obj && t is Node.Obj && (b == null || b is Node.Obj)) {
            return mergeObj(b as? Node.Obj, o, t, path)
        }
        if (o is Node.Arr && t is Node.Arr && (b == null || b is Node.Arr)) {
            return mergeArr(b as? Node.Arr, o, t, path)
        }
        return conflict(b, o, t, path, reasonFor(b, o, t))
    }

    private fun reasonFor(b: Node?, o: Node?, t: Node?): String {
        fun desc(n: Node?) = when (n) {
            null -> "缺失(删除)"
            is Node.NullNode -> "null"
            is Node.Obj -> "对象"
            is Node.Arr -> "数组"
            is Node.Scalar -> "标量(${n.value})"
        }
        return "不可自动合并：base=${desc(b)}，ours=${desc(o)}，theirs=${desc(t)}"
    }

    private fun mergeObj(b: Node.Obj?, o: Node.Obj, t: Node.Obj, path: String): Node {
        val keys = LinkedHashSet<String>()
        b?.entries?.keys?.let(keys::addAll)
        keys.addAll(o.entries.keys)
        keys.addAll(t.entries.keys)
        val out = LinkedHashMap<String, Node>()
        for (k in keys) {
            val child = mergeNode(b?.entries?.get(k), o.entries[k], t.entries[k], childPath(path, k))
            if (child != null) out[k] = child
        }
        return Node.Obj(out, listOf(Prov("merged", path)))
    }

    private fun mergeArr(b: Node.Arr?, o: Node.Arr, t: Node.Arr, path: String): Node? {
        val rule = policies.ruleFor(path)
            ?: return conflict(b, o, t, path, "数组路径未登记合并策略，按约定不得猜测")
        return when (rule.policy) {
            ArrayPolicy.REPLACE ->
                conflict(b, o, t, path, "数组策略为 REPLACE，双侧均修改，需人工裁决")
            ArrayPolicy.MERGE_BY_ID -> mergeById(b, o, t, path, rule.idKey ?: "id")
            ArrayPolicy.ORDERED_SEQUENCE -> mergeSequence(b, o, t, path)
        }
    }

    private fun idOf(item: Node, idKey: String): String? {
        if (item !is Node.Obj) return null
        val v = item.entries[idKey] as? Node.Scalar ?: return null
        return v.value
    }

    private class ArrIndexError(msg: String) : Exception(msg)

    private fun mergeById(b: Node.Arr?, o: Node.Arr, t: Node.Arr, path: String, idKey: String): Node? {
        fun index(arr: Node.Arr?, side: String): Pair<LinkedHashMap<String, Node>, List<String>> {
            val map = LinkedHashMap<String, Node>()
            if (arr == null) return map to emptyList()
            for ((i, item) in arr.items.withIndex()) {
                val id = idOf(item, idKey)
                    ?: throw ArrIndexError("数组元素缺少稳定 id '$idKey'（$side 侧 [$i]）")
                if (map.containsKey(id))
                    throw ArrIndexError("数组元素重复 id '$id'（$side 侧 [$i]）")
                map[id] = item
            }
            return map to map.keys.toList()
        }
        val bIdx: Pair<LinkedHashMap<String, Node>, List<String>>
        val oIdx: Pair<LinkedHashMap<String, Node>, List<String>>
        val tIdx: Pair<LinkedHashMap<String, Node>, List<String>>
        try {
            bIdx = index(b, "base"); oIdx = index(o, "ours"); tIdx = index(t, "theirs")
        } catch (e: ArrIndexError) {
            return conflict(b, o, t, path, e.message!!)
        }

        val allIds = LinkedHashSet<String>()
        allIds.addAll(bIdx.first.keys); allIds.addAll(oIdx.first.keys); allIds.addAll(tIdx.first.keys)
        val mergedItems = LinkedHashMap<String, Node>()
        for (id in allIds) {
            val itemPath = "$path[$idKey=$id]"
            val m = mergeNode(bIdx.first[id], oIdx.first[id], tIdx.first[id], itemPath)
            if (m != null) mergedItems[id] = m
        }
        // 顺序：base 顺序为参照，仅一侧重排则跟随该侧，双侧不同重排则冲突
        val finalIds = mergedItems.keys.toList()
        fun filtered(order: List<String>) = order.filter { finalIds.contains(it) }
        val bSeq = filtered(bIdx.second)
        val oSeq = filtered(oIdx.second)
        val tSeq = filtered(tIdx.second)
        val order: List<String> = when {
            oSeq == tSeq -> oSeq
            bSeq == oSeq -> tSeq
            bSeq == tSeq -> oSeq
            else -> return conflict(b, o, t, path, "数组元素顺序双侧不同重排")
        }
        val prov = listOf(Prov("merged", path))
        return Node.Arr(order.map { mergedItems[it]!! }, prov)
    }

    /** 有序序列的三向合并：以 base 为参照做锚点（LCS）对齐，逐段合并。 */
    private fun mergeSequence(b: Node.Arr?, o: Node.Arr, t: Node.Arr, path: String): Node? {
        val bl = b?.items ?: emptyList()
        val anchorsO = lcsAnchors(bl, o.items)
        val anchorsT = lcsAnchors(bl, t.items)
        // 共同锚点：在 base 中同时被两侧保留的元素
        val common = anchorsO.keys.intersect(anchorsT.keys).sorted()
        val result = mutableListOf<Node>()
        var bi = 0; var oi = 0; var ti = 0
        fun emitSegment(bEnd: Int, oEnd: Int, tEnd: Int, segPath: String): Boolean {
            val bSeg = bl.subList(bi, bEnd)
            val oSeg = o.items.subList(oi, oEnd)
            val tSeg = t.items.subList(ti, tEnd)
            when {
                structEq(Node.Arr(oSeg), Node.Arr(tSeg)) -> result.addAll(oSeg.map { deepCopy(it) })
                structEq(Node.Arr(bSeg), Node.Arr(oSeg)) -> result.addAll(tSeg.map { deepCopy(it) })
                structEq(Node.Arr(bSeg), Node.Arr(tSeg)) -> result.addAll(oSeg.map { deepCopy(it) })
                else -> {
                    conflicts += Conflict(
                        conflictId(segPath), segPath,
                        "有序序列同一区段双侧不同修改",
                        Node.Arr(bSeg.toList()), Node.Arr(oSeg.toList()), Node.Arr(tSeg.toList())
                    )
                    result.addAll(bSeg.map { deepCopy(it) }) // 未决冲突以 base 段占位
                    return false
                }
            }
            return true
        }
        for (a in common + listOf(bl.size)) {
            val oEnd = if (a < bl.size) anchorsO[a]!! else o.items.size
            val tEnd = if (a < bl.size) anchorsT[a]!! else t.items.size
            emitSegment(a, oEnd, tEnd, "$path[$bi..$a]")
            if (a < bl.size) result.add(deepCopy(bl[a]))
            bi = a + 1; oi = oEnd + 1; ti = tEnd + 1
        }
        return Node.Arr(result, listOf(Prov("merged", path)))
    }

    /** 返回 base 下标 -> other 下标 的 LCS 对应表。 */
    private fun lcsAnchors(base: List<Node>, other: List<Node>): Map<Int, Int> {
        val n = base.size; val m = other.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (structEq(base[i], other[j])) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val map = LinkedHashMap<Int, Int>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                structEq(base[i], other[j]) -> { map[i] = j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return map
    }

    private fun conflict(b: Node?, o: Node?, t: Node?, path: String, reason: String): Node? {
        val c = Conflict(conflictId(path), path, reason, b, o, t)
        // 人工裁决：指纹匹配才自动套用，否则仅作为建议
        val ds = decisions[path]
        if (ds != null) {
            val exact = ds.lastOrNull { it.matches(c) }
            if (exact != null) {
                applied += exact
                return resolveWith(exact, c)
            }
            val stale = ds.lastOrNull()
            if (stale != null) {
                suggestions += Suggestion(path, stale, "内容指纹已变化，旧裁决仅作建议，不自动套用")
            }
        }
        conflicts += c
        return b?.let { deepCopy(it) } // 未决冲突在结果树中暂以 base 占位
    }

    private fun resolveWith(d: Decision, c: Conflict): Node? {
        val chosen: Node? = when (d.choice) {
            "ours" -> c.ours
            "theirs" -> c.theirs
            "base" -> c.base
            "custom" -> d.customText?.let { Parser.parse(it, "custom") }
            else -> c.base
        }
        return chosen?.let { deepCopy(it).withProv(it.prov + Prov("decision:${d.choice}", c.path)) }
    }

    private fun recordAuto(path: String, desc: String, result: Node?) {
        auto += AutoChange(path, desc, result?.prov ?: emptyList())
    }

    companion object {
        fun conflictId(path: String): String =
            fingerprint(Node.Scalar(path, Node.Kind.STRING)).substring(0, 16)
    }
}
