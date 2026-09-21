package merger

/** Path-registered array strategies. Unregistered paths must never be guessed. */
data class PolicyRegistry(val version: Int, val byPath: Map<Path, ArrayPolicy>) {
    fun policyFor(path: Path): ArrayPolicy? = byPath[path]
    companion object { val EMPTY = PolicyRegistry(0, emptyMap()) }
}

data class MergeInput(
    val base: PNode,
    val ours: PNode,
    val theirs: PNode,
    val policies: PolicyRegistry,
)

object Merger {

    fun merge(input: MergeInput): MergeNode =
        node(input.base, input.ours, input.theirs, Path.ROOT, input.policies, 0)

    private fun sameValue(a: PNode?, b: PNode?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.fingerprint() == b.fingerprint()
    }

    private fun presence(node: PNode?): Boolean = node != null

    private fun node(base: PNode?, ours: PNode?, theirs: PNode?, path: Path,
                    policies: PolicyRegistry, order: Int): MergeNode {
        val eqBO = sameValue(base, ours)
        val eqBT = sameValue(base, theirs)
        if (eqBO && eqBT) return unchanged(base!!, path, policies, order)

        // At least one side changed.
        if (ours is PMap || theirs is PMap || base is PMap) {
            return mergeMap(asMap(base), asMap(ours), asMap(theirs), path, policies)
        }
        if (ours is PSeq || theirs is PSeq || base is PSeq) {
            return mergeSeq(asSeq(base), asSeq(ours), asSeq(theirs), path, policies, order)
        }
        return mergeScalar(asScalar(base), asScalar(ours), asScalar(theirs), path)
    }

    private fun asMap(n: PNode?): PMap? = n as? PMap
    private fun asSeq(n: PNode?): PSeq? = n as? PSeq
    private fun asScalar(n: PNode?): PScalar? = n as? PScalar

    private fun unchanged(node: PNode, path: Path, policies: PolicyRegistry, order: Int): MergeNode = when (node) {
        is PScalar -> MergeScalar(path, MergeStatus.UNCHANGED, ChangeKind.NONE, node, node, node, node.withPath(path, order))
        is PMap -> {
            val kids = node.entries.map { e ->
                e.key to unchanged(e.value, path + e.key, policies, e.order)
            }
            MergeMap(path, MergeStatus.UNCHANGED, ChangeKind.NONE, kids,
                node.withPath(path, order))
        }
        is PSeq -> {
            val items = node.items.mapIndexed { i, it -> it.withPath(path + ("[$i]"), i) }
            MergeSeq(path, MergeStatus.UNCHANGED, ChangeKind.NONE,
                policies.policyFor(path), node, node, node, items,
                resolved = PSeq(items, SourceInfo(Side.BASE, path, order)))
        }
    }

    private fun mergeSeq(base: PSeq?, ours: PSeq?, theirs: PSeq?, path: Path,
                        policies: PolicyRegistry, order: Int): MergeSeq {
        val policy = policies.policyFor(path)
        // If all three are equal there is nothing to merge.
        if (sameValue(base, ours) && sameValue(base, theirs) && base != null) {
            return unchanged(base, path, policies, order) as MergeSeq
        }
        return when (policy) {
            null -> MergeSeq(path, MergeStatus.NEEDS_POLICY, ChangeKind.BOTH_MOD,
                null, base, ours, theirs, emptyList())
            ArrayPolicy.REPLACE -> {
                val eqBO = sameValue(base, ours)
                val eqBT = sameValue(base, theirs)
                val sameOT = sameValue(ours, theirs)
                val chosen = when {
                    sameOT -> ours
                    !eqBO -> ours
                    else -> theirs
                }
                if (chosen == null) {
                    MergeSeq(path, MergeStatus.AUTO, ChangeKind.OURS_DEL, policy,
                        base, ours, theirs, emptyList(),
                        resolved = PSeq(emptyList(), SourceInfo(Side.BASE, path, 0)))
                } else cleanSeq(path, ArrayPolicy.REPLACE, base, ours, theirs, chosen)
            }
            ArrayPolicy.ID_MERGE -> SeqMerger.idMerge(base, ours, theirs, path, policies)
            ArrayPolicy.ORDERED -> SeqMerger.ordered(base, ours, theirs, path, policies)
        }
    }

    private fun cleanSeq(path: Path, policy: ArrayPolicy, base: PSeq?, ours: PSeq?,
                         theirs: PSeq?, chosen: PSeq): MergeSeq {
        val items = chosen.items.mapIndexed { i, it -> it.withPath(path + ("[" + i + "]"), i) }
        return MergeSeq(path, MergeStatus.AUTO, ChangeKind.BOTH_MOD, policy, base, ours, theirs,
            items, resolved = PSeq(items, SourceInfo(Side.BASE, path, 0)))
    }

    private fun mergeScalar(base: PScalar?, ours: PScalar?, theirs: PScalar?, path: Path): MergeScalar {
        val bNull = base?.tag == ScalarTag.NULL
        val oNull = ours?.tag == ScalarTag.NULL
        val tNull = theirs?.tag == ScalarTag.NULL
        val eqBO = base == null && ours == null || (base != null && ours != null && base.fingerprint() == ours.fingerprint())
        val eqBT = base == null && theirs == null || (base != null && theirs != null && base.fingerprint() == theirs.fingerprint())
        val sameOT = ours != null && theirs != null && ours.fingerprint() == theirs.fingerprint()

        val (status, kind) = when {
            sameOT && ours != null && theirs != null ->
                MergeStatus.AUTO to if (base == null) ChangeKind.BOTH_ADD else ChangeKind.BOTH_MOD
            eqBO != eqBT -> {
                // exactly one side changed -> auto
                val changedOurs = !eqBO
                MergeStatus.AUTO to when {
                    base == null -> if (changedOurs) ChangeKind.OURS_ADD else ChangeKind.THEIRS_ADD
                    changedOurs && ours == null -> ChangeKind.OURS_DEL
                    !changedOurs && theirs == null -> ChangeKind.THEIRS_DEL
                    changedOurs && oNull -> ChangeKind.OURS_MOD
                    !changedOurs && tNull -> ChangeKind.THEIRS_MOD
                    changedOurs -> ChangeKind.OURS_MOD
                    else -> ChangeKind.THEIRS_MOD
                }
            }
            else -> {
                val oDel = ours == null
                val tDel = theirs == null
                MergeStatus.CONFLICT to when {
                    oDel || tDel -> ChangeKind.DEL_VS_MOD
                    (oNull != (base?.tag == ScalarTag.NULL)) ||
                        (tNull != (base?.tag == ScalarTag.NULL)) -> ChangeKind.NULL_VS_MOD
                    base == null -> ChangeKind.BOTH_ADD
                    else -> ChangeKind.BOTH_MOD
                }
            }
        }
        val resolved = when (status) {
            MergeStatus.AUTO -> when {
                !eqBO -> ours?.withPath(path)
                !eqBT -> theirs?.withPath(path)
                else -> ours?.withPath(path) ?: theirs?.withPath(path)
            }
            else -> null
        }
        return MergeScalar(path, status, kind, base, ours, theirs, resolved)
    }

    private fun mergeMap(base: PMap?, ours: PMap?, theirs: PMap?, path: Path,
                         policies: PolicyRegistry): MergeMap {
        val keys = LinkedHashSet<String>()
        base?.entries?.forEach { keys.add(it.key) }
        ours?.entries?.forEach { keys.add(it.key) }
        theirs?.entries?.forEach { keys.add(it.key) }

        val children = mutableListOf<Pair<String, MergeNode>>()
        var status = MergeStatus.UNCHANGED
        for (key in keys) {
            val b = base?.get(key)
            val o = ours?.get(key)
            val t = theirs?.get(key)
            val child = node(b, o, t, path + key, policies, children.size)
            children.add(key to child)
            status = lift(status, child.status)
        }
        val kind = when {
            status == MergeStatus.UNCHANGED -> ChangeKind.NONE
            base == null -> ChangeKind.BOTH_ADD
            else -> ChangeKind.BOTH_MOD
        }
        val resolved = if (status == MergeStatus.CONFLICT || status == MergeStatus.NEEDS_POLICY)
            null else buildResolvedMap(path, children)
        return MergeMap(path, status, kind, children, resolved)
    }

    fun lift(a: MergeStatus, b: MergeStatus): MergeStatus {
        if (a == MergeStatus.CONFLICT || b == MergeStatus.CONFLICT) return MergeStatus.CONFLICT
        if (a == MergeStatus.NEEDS_POLICY || b == MergeStatus.NEEDS_POLICY) return MergeStatus.NEEDS_POLICY
        if (a == MergeStatus.AUTO || b == MergeStatus.AUTO) return MergeStatus.AUTO
        return MergeStatus.UNCHANGED
    }

    private fun buildResolvedMap(path: Path, children: List<Pair<String, MergeNode>>): PNode {
        val entries = children.mapIndexedNotNull { i, (key, child) ->
            val value = effectiveValue(child) ?: return@mapIndexedNotNull null
            MapEntry(key, value.withPath(path + key, i), i)
        }
        return PMap(entries, SourceInfo(Side.BASE, path, 0))
    }

    /** Value an effectively-resolved node contributes; unresolved nodes yield null. */
    fun effectiveValue(node: MergeNode): PNode? = when (node) {
        is MergeScalar -> node.resolved
        is MergeMap -> node.resolved
        is MergeSeq -> node.resolved
    }
}
