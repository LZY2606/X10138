package merger
fun main() {
    val chunks = Diff3.align(listOf("w1","w2"), listOf("w1","w2","w3"), listOf("w1","w2","w4")) { x,y -> x==y }
    chunks.forEach { c -> when(c) {
        is Diff3.Chunk.Stable -> println("STABLE ${c.base}")
        is Diff3.Chunk.Change -> println("CHANGE base=${c.base} a=${c.a} b=${c.b}")
    } }
}
