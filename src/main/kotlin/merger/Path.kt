package merger

/**
 * 合并树中的路径。Map 用字段名；数组有两类段：
 *  - [Index] 普通有序序列/替换数组中的位置；id 合并数组在未解析冲突区也可能出现位置
 *  - [Id]    id 合并数组中按稳定 id 定位的元素（如 servers[id=web-1]）
 */
sealed class PathSeg {
    data class Key(val name: String) : PathSeg()
    data class Index(val i: Int) : PathSeg()
    data class Id(val field: String, val value: String) : PathSeg()
    data class Any(val label: String) : PathSeg() // 通配/占位
}

data class Path(val segs: List<PathSeg>) {
    fun child(seg: PathSeg): Path = Path(segs + seg)
    fun startsWith(prefix: Path): Boolean =
        segs.size >= prefix.segs.size && segs.subList(0, prefix.segs.size) == prefix.segs
    override fun toString(): String = render()

    fun render(): String {
        if (segs.isEmpty()) return "\$"
        val sb = StringBuilder("\$")
        for (s in segs) when (s) {
            is PathSeg.Key -> {
                sb.append('.')
                sb.append(if (SIMPLE_KEY.matches(s.name)) s.name else "[" + quote(s.name) + "]")
            }
            is PathSeg.Index -> sb.append('[').append(s.i).append(']')
            is PathSeg.Id -> sb.append('[').append(s.field).append('=').append(quote(s.value)).append(']')
            is PathSeg.Any -> sb.append("[").append(s.label).append("]")
        }
        return sb.toString()
    }

    companion object {
        private val SIMPLE_KEY = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        val ROOT = Path(emptyList())

        private fun quote(v: String): String =
            "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"

        /**
         * 策略登记路径接受点分名字、下标与通配段，例如：
         *   $.servers / servers / features.list[*]
         */
        fun parse(raw: String): Path {
            var s = raw.trim()
            if (s.startsWith("$")) s = s.substring(1)
            if (s.startsWith(".")) s = s.substring(1)
            val segs = mutableListOf<PathSeg>()
            var i = 0
            val token = StringBuilder()
            fun flushKey() {
                if (token.isNotEmpty()) {
                    segs.add(PathSeg.Key(token.toString()))
                    token.clear()
                }
            }
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '.' -> { flushKey(); i++ }
                    c == '[' -> {
                        flushKey()
                        val end = s.indexOf(']', i + 1)
                        require(end > i) { "路径括号不闭合: $raw" }
                        val inside = s.substring(i + 1, end).trim()
                        when {
                            inside == "*" -> segs.add(PathSeg.Any("*"))
                            inside == "#" -> segs.add(PathSeg.Any("#"))
                            inside.toIntOrNull() != null -> segs.add(PathSeg.Index(inside.toInt()))
                            inside.contains('=') -> {
                                val (f, v) = inside.split('=', limit = 2)
                                segs.add(PathSeg.Id(f.trim(), unquote(v.trim())))
                            }
                            else -> throw IllegalArgumentException("无法识别的数组段 [$inside]: $raw")
                        }
                        i = end + 1
                    }
                    else -> { token.append(c); i++ }
                }
            }
            flushKey()
            return Path(segs)
        }

        private fun unquote(v: String): String =
            if (v.length >= 2 && (v.first() == '\'' && v.last() == '\'' || v.first() == '"' && v.last() == '"'))
                v.substring(1, v.length - 1)
            else v

        /**
         * 判断合并树中的实际路径是否命中登记路径：
         * 登记路径中的 Any(*) 段可匹配实际路径中的任意单段。
         */
        fun matches(registered: Path, actual: Path): Boolean {
            if (registered.segs.size != actual.segs.size) return false
            for ((r, a) in registered.segs.zip(actual.segs)) {
                when {
                    r is PathSeg.Any -> Unit
                    r::class != a::class -> return false
                    r is PathSeg.Key && (a as PathSeg.Key).name != r.name -> return false
                    r is PathSeg.Index && (a as PathSeg.Index).i != r.i -> return false
                    r is PathSeg.Id && a is PathSeg.Id && (a.field != r.field || a.value != r.value) -> return false
                }
            }
            return true
        }
    }
}
