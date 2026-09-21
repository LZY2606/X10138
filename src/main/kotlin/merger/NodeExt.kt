package merger

/** Return a distinct node carrying the given merged provenance path/order. */
fun PNode.withPath(path: Path, order: Int = this.src.order, anchor: String? = this.src.anchor): PNode {
    val newSrc = src.copy(path = path, order = order, anchor = anchor)
    return when (this) {
        is PScalar -> copy(src = newSrc)
        is PSeq -> copy(
            items = items.mapIndexed { i, child ->
                child.withPath(path + ("[" + i + "]"), i, child.src.anchor)
            },
            src = newSrc,
        )
        is PMap -> copy(
            entries = entries.map { e ->
                e.copy(value = e.value.withPath(path + e.key, e.order, e.value.src.anchor))
            },
            src = newSrc,
        )
    }
}
