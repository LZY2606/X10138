package semmerge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import semmerge.json.JsonParser
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SScalar
import semmerge.model.ScalarKind
import semmerge.model.structuralEqual
import semmerge.yaml.YamlEmitter
import semmerge.yaml.YamlParser

class YamlJsonParseTest {

    @Test
    fun `yaml block map preserves order and types`() {
        val node = YamlParser.parse("""
            name: svc
            port: 8080
            ratio: 1.5
            enabled: true
            empty: null
            note: "hello world"
        """.trimIndent()) as SMap
        assertEquals(listOf("name", "port", "ratio", "enabled", "empty", "note"), node.keys)
        assertEquals(ScalarKind.STRING, (node.get("name") as SScalar).kind)
        assertEquals(ScalarKind.INT, (node.get("port") as SScalar).kind)
        assertEquals(ScalarKind.FLOAT, (node.get("ratio") as SScalar).kind)
        assertEquals(ScalarKind.BOOL, (node.get("enabled") as SScalar).kind)
        assertEquals(ScalarKind.NULL, (node.get("empty") as SScalar).kind)
    }

    @Test
    fun `missing key is different from explicit null`() {
        val node = YamlParser.parse("a: null\n") as SMap
        assertNotNull(node.get("a"))
        assertEquals(ScalarKind.NULL, (node.get("a") as SScalar).kind)
        assertNull(node.get("b"))
    }

    @Test
    fun `nested sequences and maps`() {
        val node = YamlParser.parse("""
            servers:
              - name: a
                ports: [80, 443]
              - name: b
                ports:
                  - 8080
                  - 8443
            flow: {x: 1, y: 2}
        """.trimIndent()) as SMap
        val servers = node.get("servers") as SList
        assertEquals(2, servers.items.size)
        val first = servers.items[0] as SMap
        assertEquals("a", (first.get("name") as SScalar).text)
        val ports = first.get("ports") as SList
        assertEquals(2, ports.items.size)
        val flow = node.get("flow") as SMap
        assertEquals("1", (flow.get("x") as SScalar).text)
    }

    @Test
    fun `anchors and aliases expand into independent copies`() {
        val node = YamlParser.parse("""
            defaults: &d
              timeout: 30
              retries: 3
            a: *d
            b: *d
        """.trimIndent()) as SMap
        val a = node.get("a") as SMap
        val b = node.get("b") as SMap
        assertTrue(structuralEqual(a, b))
        assertNotEquals(System.identityHashCode(a), System.identityHashCode(b))
        a.put("timeout", SScalar(ScalarKind.INT, "99", semmerge.model.ScalarStyle.PLAIN))
        assertEquals("30", ((b.get("timeout") as SScalar)).text)
    }

    @Test
    fun `literal block scalar keeps newlines`() {
        val node = YamlParser.parse("script: |\n  line1\n  line2\n") as SMap
        val s = node.get("script") as SScalar
        assertEquals("line1\nline2\n", s.text)
    }

    @Test
    fun `json parses and is structurally equal to yaml equivalent`() {
        val json = JsonParser.parse("""{"a": 1, "b": [true, null, "x"], "c": {"d": 2.0}}""")
        val yaml = YamlParser.parse("a: 1\nb:\n  - true\n  - null\n  - x\nc:\n  d: 2.0\n")
        assertTrue(structuralEqual(json, yaml))
    }

    @Test
    fun `emitter round trip`() {
        val yaml = YamlParser.parse("name: svc\nports:\n  - 8080\n  - 8443\nnested:\n  x: 1\n")
        val text = YamlEmitter.emit(yaml)
        val again = YamlParser.parse(text)
        assertTrue(structuralEqual(yaml, again))
    }
}
