package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StableSerializationTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `canonical fingerprint ignores key order but keeps array order`() {
        val x = ConfigParser.parse("a: 1\nb: 2\narr:\n  - 1\n  - 2\n", Source.A)
        val y = ConfigParser.parse("b: 2\na: 1\narr:\n  - 1\n  - 2\n", Source.A)
        assertEquals(x.fingerprint, y.fingerprint)

        val z = ConfigParser.parse("a: 1\nb: 2\narr:\n  - 2\n  - 1\n", Source.A)
        assertNotEquals(x.fingerprint, z.fingerprint)
    }

    @Test
    fun `numeric forms normalize to same fingerprint and output`() {
        val x = ConfigParser.parse("v: 100\n", Source.A)
        val y = ConfigParser.parse("v: 1e2\n", Source.A)
        assertEquals(x.fingerprint, y.fingerprint)
        assertEquals("100", Emitter.toJson(y.root).let { it.lines().first { it.contains("v") }.trim().trimEnd(',').substringAfter(": ").trim() })
    }

    @Test
    fun `emission is byte-stable and free of anchors`() {
        val yaml = """
            t: &d { port: 80, keep: true }
            u: *d
            name: svc
        """.trimIndent()
        val doc = ConfigParser.parse(yaml, Source.A)
        val j1 = Emitter.toJson(doc.root); val j2 = Emitter.toJson(doc.root)
        assertEquals(j1, j2)
        val y1 = Emitter.toYaml(doc.root); val y2 = Emitter.toYaml(doc.root)
        assertEquals(y1, y2)
        assertFalse(j1.contains("&d") || j1.contains("*d"))
        assertFalse(y1.contains("&d") || y1.contains("*d"))
        // 再解析输出，值与锚点展开后的语义一致
        val roundTrip = ConfigParser.parse(y1, Source.A)
        assertTrue(Merger.semEq(doc.root, roundTrip.root))
    }

    @Test
    fun `YAML output round trips delete omission`() {
        val model = SMap(linkedMapOf("a" to SSString("x"), "b" to SSNull.INSTANCE))
        val text = Emitter.toYaml(model)
        val back = ConfigParser.parse(text, Source.A).root as SMap
        assertTrue(back["b"] is SSNull)
        assertEquals("x", (back["a"] as SSString).value)
    }
}
