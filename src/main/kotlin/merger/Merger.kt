package merger

import java.time.Instant

data class MergeInput(
    val base: ParsedDoc,
    val a: ParsedDoc,
    val b: ParsedDoc,
    val registry: StrategyRegistry,
    val decisions: List<Decision>,
    val outputFormat: Format,
    val sessionVersion: Int
)

data class MergeOutcome(
    val root: MergeNode,
    val autoCount: Int,
    val conflictCount: Int,
    val resolvedCount: Int,
    val unresolvedCount: Int,
    val advisory: List<MergeNode>,
    val conflictIndex: Map<String, MergeNode>,
    val materialized: SNode,
    val resultFingerprint: String,
    val exported: String
)

object Merger {

    fun merge(input: MergeInput, at: Instant = Instant.now()): MergeOutcome {
        val ctx = Ctx(input, at)
        val root = mergeNode(Path.ROOT, "$", input.base.root, input.a.root, input.b.root, ctx)
        val flat = mutableListOf<MergeNode>()
        collect(root, flat)
        val conflicts = flat.filter { it.conflict != null }
        val unresolved = conflicts.filter { it.resolvedBy == null }
        val resolved = conflicts.filter { it.resolvedBy != null }
        val advisories = flat.filter { it.advisory != null && it.resolvedBy == null }
        val materialized = Materializer.materialize(root) ?: SMap()
        val exported = render(materialized, input.outputFormat)
        return MergeOutcome(
            root = root,
            autoCount = flat.count { it.status == MergeStatus.AUTO },
            conflictCount = conflicts.size,
            resolvedCount = resolved.size,
            unresolvedCount = unresolved.size,
            advisory = advisories,
            conflictIndex = conflicts.associateBy { it.path },
            materialized = materialized,
            resultFingerprint = Canonical.fingerprint(materialized, includeTombstones = false),
            exported = exported
        )
    }

    private fun collect(n: MergeNode, out: MutableList<MergeNode>) {
        out.add(n)
        n.children.values.forEach { collect(it, out) }
    }

    private fun render(node: SNode, format: Format): String = when (format) {
        Format.JSON -> Emitter.toJson(node)
        Format.YAML -> Emitter.toYaml(node)
    }

    // ---------- 语义相等 ----------

    fun semEq(x: SNode?, y: SNode?): Boolean {
        if (x is SSNumber && y is SSNumber) {
            return Canonical.normalizeNumber(x.value) == Canonical.normalizeNumber(y.value)
        }
        if (x is SMap && y is SMap) {
            if (x.entries.size != y.entries.size) return false
            return x.entries.all { (k, v) -> y.entries.containsKey(k) && semEq(v, y.entries[k]) }
        }
        if (x is SSeq && y is SSeq) {
            if (x.items.size != y.items.size) return false
            return x.items.zip(y.items).all { (p, q) -> semEq(p, q) }
        }
        return x == y
    }

    fun kind(n: SNode?): String = when (n) {
        null, is SSMissing -> "缺失"
        is SSDelete -> "删除"
        is SSNull -> "null"
        is SSString -> "字符串"
        is SSBool -> "布尔"
        is SSNumber -> "数字"
        is SSeq -> "数组"
        is SMap -> "对象"
    }
}
