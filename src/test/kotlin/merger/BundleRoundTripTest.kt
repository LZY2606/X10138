package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BundleRoundTripTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `export then import preserves paths, provenance and result fingerprint`() {
        val store = Store(dir)
        val svc = SessionService(store)
        val s = svc.create("roundtrip")
        val base = """
            servers:
              - {id: web, port: 80}
            name: svc
        """.trimIndent()
        val a = """
            servers:
              - {id: web, port: 8080}
              - {id: cache, port: 6379}
            name: svc-edge
        """.trimIndent()
        val b = """
            servers:
              - {id: web, port: 80}
            name: svc-prod
        """.trimIndent()
        TestSupport.putAll(svc, s.id, base, a, b)
        svc.registerStrategy(s.id, "\$.servers", ArrayStrategy.ID, "id")
        var o = svc.remerge(s)
        // name 冲突，裁决选 B
        assertEquals(setOf("\$.name"), TestSupport.conflictPaths(o))
        svc.submitDecisions(s.id, SubmitRequest(s.version, "tester",
            listOf(SubmitItem("\$.name", "B", null, null))))
        o = svc.remerge(svc.get(s.id))
        assertEquals(0, o.unresolvedCount)

        val bundle = BundleIO.export(svc.get(s.id), o)
        val beforePaths = collectPaths(o.root)
        val beforeFp = o.resultFingerprint

        // 全新服务实例 + 空数据目录（通过内存 store 即可），模拟导出再导入
        val (ns, no) = BundleIO.import(svc, bundle, store)
        assertEquals(beforeFp, no.resultFingerprint)
        assertEquals(beforePaths, collectPaths(no.root))
        // 来源链中保留人工裁决记录
        val allNodes = mutableListOf<MergeNode>()
        fun w(n: MergeNode) { allNodes.add(n); n.children.values.forEach { w(it) } }
        w(no.root)
        assertTrue(allNodes.any { node -> node.provenance.any { it.action.contains("人工裁决") } })
        // 结果文本一致
        assertEquals(o.exported, no.exported)
    }

    @Test
    fun `imported decision replay still distinguishes delete and null`() {
        val store = Store(dir)
        val svc = SessionService(store)
        val s = svc.create("dn")
        val base = "g: x\n"
        val a = "g: !delete\n"
        val b = "g: null\n"
        TestSupport.putAll(svc, s.id, base, a, b)
        val o = svc.remerge(s)
        assertEquals(1, o.unresolvedCount)
        assertEquals(ConflictKind.DELETE_NULL, o.conflictIndex["\$.g"]!!.conflict!!.kind)
    }

    private fun collectPaths(n: MergeNode): List<String> {
        val out = mutableListOf(n.path)
        n.children.values.forEach { out.addAll(collectPaths(it)) }
        return out
    }
}
