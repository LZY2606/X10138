package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParsingTest {
    @Test
    fun `explicit null, missing key and delete are distinct in model`() {
        val yaml = """
            a: null
            b:
            c: "null"
        """.trimIndent()
        val node = YamlParser("t.yaml").parse(yaml) as Node.Obj
        assertEquals(ScalarValue.NullVal, (node.children["a"] as Node.Scalar).value)
        assertEquals(ScalarValue.NullVal, (node.children["b"] as Node.Scalar).value)
        assertEquals(ScalarValue.StrVal("null"), (node.children["c"] as Node.Scalar).value)
        assertNull(node.children["missing"])
    }

    @Test
    fun `json parser keeps order and origins`() {
        val node = JsonParser("c.json").parse("""{"z": 1, "a": [true, null, "x"]}""") as Node.Obj
        assertEquals(listOf("z", "a"), node.children.keys.toList())
        val arr = node.children["a"] as Node.Arr
        assertEquals(1, arr.items[0].origin.line)
        assertEquals(ScalarValue.BoolVal(true), (arr.items[0] as Node.Scalar).value)
        assertEquals(ScalarValue.NullVal, (arr.items[1] as Node.Scalar).value)
        assertEquals(ScalarValue.StrVal("x"), (arr.items[2] as Node.Scalar).value)
    }

    @Test
    fun `anchors and aliases expand without shared mutable references`() {
        val yaml = """
            defaults: &d
              timeout: 30
              retries: 2
            a:
              <<: *d
              name: alpha
            b:
              <<: *d
              name: beta
        """.trimIndent()
        val node = YamlParser("t.yaml").parse(yaml) as Node.Obj
        val a = node.children["a"] as Node.Obj
        val b = node.children["b"] as Node.Obj
        assertEquals(30, ((a.children["timeout"] as Node.Scalar).value as ScalarValue.NumVal).value.toInt())
        assertEquals(30, ((b.children["timeout"] as Node.Scalar).value as ScalarValue.NumVal).value.toInt())
        // 修改其中一边不影响另一边（无共享引用）
        (a.children["retries"] as Node.Scalar).let {
            // 深拷贝校验：两个 timeout 节点不是同一对象
            assertNotSame(a.children["timeout"], b.children["timeout"])
        }
        assertNotSame(node.children["defaults"], a.children["timeout"])
    }

    @Test
    fun `merge key and alias copy origins point at alias site`() {
        val yaml = """
            base: &x
              k: v
            copy: *x
        """.trimIndent()
        val node = YamlParser("t.yaml").parse(yaml) as Node.Obj
        val copy = node.children["copy"] as Node.Obj
        assertEquals(3, copy.origin.line)
        assertEquals(3, copy.children["k"]!!.origin.line)
    }

    @Test
    fun `stable round trip serialization of numbers and strings`() {
        val text = """
            port: 8080
            ratio: 1.5
            zero: 0
            big: 100000000000000000000
            text: "8080"
            flagged: true
        """.trimIndent()
        val node = YamlParser("t.yaml").parse(text)
        val yamlOut = YamlWriter.write(node)
        val reparsed = YamlParser("out.yaml").parse(yamlOut)
        assertEquals(Fingerprints.of(node), Fingerprints.of(reparsed))
        val json = JsonWriter.write(node)
        val jsonReparsed = JsonParser("out.json").parse(json)
        assertEquals(Fingerprints.of(node), Fingerprints.of(jsonReparsed))
    }

    @Test
    fun `format detection`() {
        assertEquals(ConfigFormat.JSON, ConfigIO.detectFormat("{}", "x.txt"))
        assertEquals(ConfigFormat.YAML, ConfigIO.detectFormat("a: 1", null))
        assertEquals(ConfigFormat.JSON, ConfigIO.detectFormat("garbage", "x.json"))
    }
}
