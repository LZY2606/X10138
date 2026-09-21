package merger

/**
 * 合并结果树。删除动作（一侧删除、另一侧未修改）显式保留为 [RDelete]，
 * 与显式 null（[RScalar] 包裹 NullVal）严格区分；未决冲突产生 [RConflictRef]。
 */
sealed class RNode {
    abstract val path: Path
    abstract val sources: List<SourceRecord>

    data class RScalar(
        override val path: Path,
        val value: ScalarValue,
        override val sources: List<SourceRecord>,
    ) : RNode()

    data class RDelete(
        override val path: Path,
        override val sources: List<SourceRecord>,
    ) : RNode()

    class RObj(
        override val path: Path,
        val children: LinkedHashMap<String, RNode>,
        override val sources: List<SourceRecord>,
    ) : RNode()

    class RArr(
        override val path: Path,
        val items: MutableList<RNode>,
        val policy: ArrayPolicy?,
        override val sources: List<SourceRecord>,
    ) : RNode() {
        /** 仅 ID 策略使用：顺序冲突 id（元素本身已合并）。 */
        var orderConflictId: String? = null
    }

    /** 指向 MergeDocument.conflicts 中的某一项；未解决时占据结果位置。 */
    data class RConflictRef(
        override val path: Path,
        val conflictId: String,
        override val sources: List<SourceRecord>,
    ) : RNode()
}

/**
 * 某个结果值与各边的关系。changed=true 表示该边相对祖先有修改。
 */
data class SourceRecord(
    val side: Side,
    val present: Boolean,
    val equal: Boolean,
    val origin: Origin?,
) {
    companion object {
        fun chain(side: Side, node: Node?, equalToResult: Boolean): SourceRecord =
            SourceRecord(side, present = node != null, equal = equalToResult, origin = node?.origin)
    }
}
