package merger

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DecisionSessionTest {
    private fun doc(text: String, name: String) =
        MergeSession.InputDoc("$name.yaml", ConfigFormat.YAML, text)

    private fun session(): MergeSession {
        val base = doc("x: 1\n", "base")
        val a = doc("x: 2\n", "a")
        val b = doc("x: 3\n", "b")
        return MergeSession("t1", base, a, b, PolicyRegistry.EMPTY)
    }

    @Test
    fun `resolving a conflict applies and persists decision`() {
        val s = session()
        val conflict = s.document.conflicts.single()
        val rev0 = s.revision
        val result = s.resolve(rev0, listOf(ResolutionRequest(conflict.id, Resolution.Take(Side.A), "采用A")))
        assertTrue(result is MergeSession.ResolutionResult.Ok)
        val resolved = s.document.conflicts.single()
        assertTrue(resolved.resolved)
        val root = s.document.result as RNode.RObj
        assertEquals(2, ((root.children["x"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt())
        assertEquals(1, s.decisions().size)
    }

    @Test
    fun `stale revision cannot overwrite resolved items`() {
        val s = session()
        val conflictId = s.document.conflicts.single().id
        val r0 = s.revision
        // 页面1先解决
        s.resolve(r0, listOf(ResolutionRequest(conflictId, Resolution.Take(Side.A))))
        // 页面2持有旧 revision r0，尝试再解决同一个冲突
        val stale = s.resolve(r0, listOf(ResolutionRequest(conflictId, Resolution.Take(Side.B))))
        assertTrue(stale is MergeSession.ResolutionResult.Conflict)
        assertEquals(listOf(conflictId), (stale as MergeSession.ResolutionResult.Conflict).alreadyResolved)
        // 结果保持先到先得
        val root = s.document.result as RNode.RObj
        assertEquals(2, ((root.children["x"] as RNode.RScalar).value as ScalarValue.NumVal).value.toInt())
    }

    @Test
    fun `batch optimistic commit applies all items atomically`() {
        // 两个冲突的场景
        val base = doc("x: 1\ny: 1\n", "base")
        val a = doc("x: 2\ny: 2\n", "a")
        val b = doc("x: 3\ny: 3\n", "b")
        val s = MergeSession("t2", base, a, b, PolicyRegistry.EMPTY)
        val ids = s.document.conflicts.map { it.id }
        assertEquals(2, ids.size)
        val r0 = s.revision
        val res = s.resolve(
            r0,
            listOf(
                ResolutionRequest(ids[0], Resolution.Take(Side.A)),
                ResolutionRequest(ids[1], Resolution.Take(Side.B)),
            ),
        )
        assertTrue(res is MergeSession.ResolutionResult.Ok)
        assertTrue(s.document.conflicts.all { it.resolved })
    }

    @Test
    fun `decision is replayable when inputs unchanged`() {
        val s = session()
        val cid = s.document.conflicts.single().id
        s.resolve(s.revision, listOf(ResolutionRequest(cid, Resolution.Take(Side.A))))

        // 重新载入（等价于新建会话 + 注入历史）
        val fresh = MergeSession("t1", s.snapshot().baseInput, s.snapshot().aInput, s.snapshot().bInput, s.snapshot().policies)
        s.decisions().forEach(fresh::injectLoaded)
        fresh.recomputeAfterLoad()
        assertTrue(fresh.document.conflicts.single().resolved)
    }

    @Test
    fun `old decision is only a suggestion after side changes`() {
        val s = session()
        val cid = s.document.conflicts.single().id
        s.resolve(s.revision, listOf(ResolutionRequest(cid, Resolution.Take(Side.A))))
        val stored = s.decisions().single()

        // 分支 B 内容变化：指纹不匹配
        val changedB = doc("x: 42\n", "b")
        val changed = MergeSession("t2", s.snapshot().baseInput, s.snapshot().aInput, changedB, PolicyRegistry.EMPTY)
        changed.injectLoaded(stored)
        changed.recomputeAfterLoad()
        val conflict = changed.document.conflicts.single()
        assertFalse(conflict.resolved, "指纹变化后不得自动套用旧裁决")
        assertNotNull(conflict.suggested)
        assertEquals(stored.id, conflict.suggested!!.id)
    }

    @Test
    fun `delete decision differs from null set decision`() {
        val s = session()
        val cid = s.document.conflicts.single().id
        s.resolve(s.revision, listOf(ResolutionRequest(cid, Resolution.Delete)))
        val root = s.document.result as RNode.RObj
        assertFalse(root.children.containsKey("x"), "删除裁决应移除键")

        val s2 = session()
        val cid2 = s2.document.conflicts.single().id
        s2.resolve(s2.revision, listOf(ResolutionRequest(cid2, Resolution.SetValue(
            Node.Scalar(ScalarValue.NullVal, Origin.SYNTHETIC)
        ))))
        val root2 = s2.document.result as RNode.RObj
        assertTrue(root2.children.containsKey("x"))
        assertEquals(ScalarValue.NullVal, (root2.children["x"] as RNode.RScalar).value)
    }

    @Test
    fun `missing policy resolution upgrades policy version and remerges`() {
        val base = doc("servers:\n  - 1\n", "base")
        val a = doc("servers:\n  - 1\n  - 2\n", "a")
        val b = doc("servers:\n  - 1\n", "b")
        val s = MergeSession("tp", base, a, b, PolicyRegistry.EMPTY)
        val c = s.document.conflicts.single { it.type == ConflictType.MISSING_POLICY }
        s.resolve(s.revision, listOf(ResolutionRequest(c.id, Resolution.ChoosePolicy(ArrayPolicy.REPLACE, null))))
        assertEquals(2, s.policies.version)
        assertTrue(s.document.conflicts.isEmpty())
        val arr = (s.document.result as RNode.RObj).children["servers"] as RNode.RArr
        assertEquals(2, arr.items.size)
    }
}

class StoreRoundTripTest {
    @Test
    fun `persistence then reload keeps fingerprints and replay works`(@TempDir dir: Path) {
        val store = Store(dir)
        val base = MergeSession.InputDoc("base.yaml", ConfigFormat.YAML, "x: 1\n")
        val a = MergeSession.InputDoc("a.yaml", ConfigFormat.YAML, "x: 2\n")
        val b = MergeSession.InputDoc("b.yaml", ConfigFormat.YAML, "x: 3\n")
        val s = store.create("persist-1", base, a, b)
        val cid = s.document.conflicts.single().id
        s.resolve(s.revision, listOf(ResolutionRequest(cid, Resolution.Take(Side.A), "replay me")))
        store.save(s)
        val fp = s.document.fingerprints

        // 全新 store 实例从磁盘恢复
        val store2 = Store(dir)
        val loaded = store2.session("persist-1")!!
        assertEquals(fp, loaded.document.fingerprints)
        assertTrue(loaded.document.conflicts.single().resolved)
        assertEquals("replay me", loaded.decisions().single().reason)
    }
}
