package merger

import java.math.BigDecimal

/**
 * 合并的三个边：共同祖先、分支 A、分支 B。
 */
enum class Side(val label: String) {
    BASE("祖先"),
    A("分支A"),
    B("分支B");

    companion object {
        fun parse(s: String): Side? = entries.firstOrNull { it.name == s || it.label == s }
    }
}

/**
 * 标量值。显式 null 用 [NullVal]；字段缺失不是标量，由容器键集合表达。
 */
sealed class ScalarValue {
    abstract val raw: String
    data class StrVal(val value: String) : ScalarValue() {
        override val raw: String get() = value
    }
    data class BoolVal(val value: Boolean) : ScalarValue() {
        override val raw: String get() = if (value) "true" else "false"
    }
    data class NumVal(val value: BigDecimal) : ScalarValue() {
        override val raw: String get() = value.toPlainString()
    }
    data object NullVal : ScalarValue() {
        override val raw: String get() = "null"
    }
}

/**
 * 一个值在原始文本中的出处。
 */
data class Origin(
    val side: Side,
    val file: String,
    val line: Int,
    val column: Int,
) {
    companion object {
        val SYNTHETIC = Origin(Side.BASE, "<合成>", 0, 0)
    }
}

/**
 * 解析后的配置节点。标量、对象（保留键顺序）、数组（保留元素顺序）。
 * 锚点/别名在解析阶段展开，展开后的子树是深拷贝，不共享可变引用。
 */
sealed class Node {
    abstract val origin: Origin

    abstract fun deepCopy(): Node
    /** 别名展开用：深拷贝整棵子树，并把出处替换为别名位置（值结构不共享）。 */
    abstract fun deepCopyWithOrigin(newOrigin: Origin): Node
    abstract fun canonicalTo(sb: StringBuilder)

    data class Scalar(val value: ScalarValue, override val origin: Origin) : Node() {
        override fun deepCopy(): Node = copy()
        override fun deepCopyWithOrigin(newOrigin: Origin): Node = copy(origin = newOrigin)
        override fun canonicalTo(sb: StringBuilder) {
            when (val v = value) {
                is ScalarValue.StrVal -> sb.append("s:").append(JsonText.quote(v.value))
                is ScalarValue.BoolVal -> sb.append(if (v.value) "b:true" else "b:false")
                is ScalarValue.NumVal -> sb.append("n:").append(v.value.stripTrailingZeros().toPlainString())
                ScalarValue.NullVal -> sb.append("z")
            }
        }
    }

    class Obj(val children: LinkedHashMap<String, Node>, override val origin: Origin) : Node() {
        override fun deepCopy(): Node =
            Obj(LinkedHashMap(children.mapValues { it.value.deepCopy() }), origin)

        override fun deepCopyWithOrigin(newOrigin: Origin): Node =
            Obj(LinkedHashMap(children.mapValues { it.value.deepCopyWithOrigin(newOrigin) }), newOrigin)

        override fun canonicalTo(sb: StringBuilder) {
            sb.append("{")
            children.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(",")
                sb.append(JsonText.quote(k)).append(":")
                v.canonicalTo(sb)
            }
            sb.append("}")
        }
    }

    class Arr(val items: MutableList<Node>, override val origin: Origin) : Node() {
        override fun deepCopy(): Node = Arr(items.map { it.deepCopy() }.toMutableList(), origin)
        override fun deepCopyWithOrigin(newOrigin: Origin): Node =
            Arr(items.map { it.deepCopyWithOrigin(newOrigin) }.toMutableList(), newOrigin)
        override fun canonicalTo(sb: StringBuilder) {
            sb.append("[")
            items.forEachIndexed { i, n ->
                if (i > 0) sb.append(",")
                n.canonicalTo(sb)
            }
            sb.append("]")
        }
    }
}

/**
 * 结构化路径，如 services[].name。段分为对象键与数组入口两类。
 */
data class Path(val segments: List<PathSeg>) {
    override fun toString(): String = segments.joinToString("") { it.render() }

    fun child(key: String): Path = Path(segments + PathSeg.Key(key))
    fun childIndex(index: Int): Path = Path(segments + PathSeg.Index(index))
    fun childEntry(): Path = Path(segments + PathSeg.Entry)

    companion object {
        val ROOT = Path(emptyList())
    }
}

sealed class PathSeg {
    abstract fun render(): String

    data class Key(val key: String) : PathSeg() {
        override fun render(): String = ".${escapeKey(key)}"
    }
    data class Index(val index: Int) : PathSeg() {
        override fun render(): String = "[${index}]"
    }
    /** 数组元素的通配段，用于策略登记与指纹定位。 */
    data object Entry : PathSeg() {
        override fun render(): String = "[]"
    }

    companion object {
        private val SIMPLE = Regex("[A-Za-z0-9_-]+")
        fun escapeKey(k: String): String = if (SIMPLE.matches(k)) k else JsonText.quote(k)
    }
}

/**
 * 内容指纹：对展开后的结构做规范序列化再 SHA-256，与出处、格式无关。
 */
object Fingerprints {
    fun of(node: Node?): String {
        val sb = StringBuilder()
        if (node == null) sb.append("<missing>") else node.canonicalTo(sb)
        return Hash.sha256Hex(sb.toString())
    }
}
