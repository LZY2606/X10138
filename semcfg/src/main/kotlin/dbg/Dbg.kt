package dbg
import semmerge.yaml.YamlParser
import semmerge.yaml.YamlEmitter
import semmerge.json.JsonParser
import semmerge.model.structuralEqual
fun main() {
    val texts = listOf(
      "nested" to """
            servers:
              - name: a
                ports: [80, 443]
              - name: b
                ports:
                  - 8080
                  - 8443
            flow: {x: 1, y: 2}
        """.trimIndent(),
      "emitter" to "name: svc\nports:\n  - 8080\n  - 8443\nnested:\n  x: 1\n",
      "jsoneq" to "a: 1\nb:\n  - true\n  - null\n  - x\nc:\n  d: 2.0\n"
    )
    for ((name, t) in texts) {
        try {
            val n = YamlParser.parse(t)
            println("$name OK: $n")
            if (name == "emitter") {
                val out = YamlEmitter.emit(n)
                println("EMIT:\n$out---")
                YamlParser.parse(out)
                println("reparse OK")
            }
            if (name == "jsoneq") {
                val j = JsonParser.parse("""{"a": 1, "b": [true, null, "x"], "c": {"d": 2.0}}""")
                println("equal=${structuralEqual(j, n)}")
            }
        } catch (e: Exception) { println("$name FAIL: ${e.message}") }
    }
}
