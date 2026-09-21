package semmerge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import semmerge.json.JsonParser
import semmerge.json.JsonWriter
import semmerge.merge.BundleService
import semmerge.merge.ListStrategy
import semmerge.merge.MList
import semmerge.merge.MMap
import semmerge.merge.OutputBuilder
import semmerge.merge.Resolution
import semmerge.model.SList
import semmerge.model.SMap
import semmerge.model.SScalar
import semmerge.session.InputSide
import semmerge.session.RawInput
import semmerge.session.ResolutionRequest
import semmerge.session.SessionService
import semmerge.session.SessionStore
import java.nio.file.Files

class PersistenceRoundTripTest {

    @Test
    fun `stable serialization ignores key order and format`() {
        val jsonOrder1 = JsonParser.parse("""{"b":1,"a":[1,2],"c":{"z":true,"y":null}}""")
        val jsonOrder2 = JsonParser.parse("""{"c":{"y":null,"z":true},"a":[1,2],"b":1}""")
        assertEquals(
            JsonWriter.write(jsonOrder1, sortKeys = true),
            JsonWriter.write(jsonOrder2, sortKeys = true),
        )
        val yaml = MergeTestSupport.yaml("c:\n  z: true\n  y: null\nb: 1\na:\n  - 1\n  - 2\n")
        assertEquals(
            JsonWriter.write(jsonOrder1, sortKeys = true),
            JsonWriter.write(yaml, sortKeys = true),
        )
    }

    @Test
    fun `session store reloads inputs policies and full decision history`() {
        val dir = Files.createTempDirectory("semcfg-persist")
        val svc = SessionService(SessionStore(dir))
        svc.updateInput(InputSide.BASE, RawInput("x: 1\n", "yaml"), svc.state.version)
        svc.updateInput(InputSide.BRANCH_A, RawInput("x: 2\n", "yaml"), svc.state.version)
        svc.updateInput(InputSide.BRANCH_B, RawInput("x: 3\n", "yaml"), svc.state.version)
        svc.setPolicy(PathRendererTest.parse("\$.items"), ListStrategy.ID, "id", svc.state.version)
        svc.resolve(listOf(ResolutionRequest("\$.x#VALUE", Resolution.TakeB)), svc.state.version)

        val reloaded = SessionService(SessionStore(dir))
        assertEquals("x: 3\n", reloaded.state.inputs[InputSide.BRANCH_B]?.text)
        assertEquals(ListStrategy.ID, reloaded.state.policies.at(PathRendererTest.parse("\$.items"))?.strategy)
        val merge = reloaded.recompute()
        assertTrue(merge.appliedDecisions.contains("\$.x#VALUE"))
        val history = reloaded.state.history("\$.x#VALUE")
        assertEquals(1, history.size)
        assertEquals(Resolution.TakeB::class, history.single().resolution::class)
    }

    @Test
    fun `export then import keeps paths provenance and result fingerprint`() {
        val dir = Files.createTempDirectory("semcfg-bundle")
        val svc = SessionService(SessionStore(dir))
        svc.updateInput(InputSide.BASE, RawInput(
            "svcs:\n  - id: web\n    port: 80\n  - id: db\n    port: 5432\n", "yaml"), svc.state.version)
        svc.updateInput(InputSide.BRANCH_A, RawInput(
            "svcs:\n  - id: db\n    port: 5432\n  - id: web\n    port: 8080\n", "yaml"), svc.state.version)
        svc.updateInput(InputSide.BRANCH_B, RawInput(
            "svcs:\n  - id: web\n    port: 80\n  - id: db\n    port: 5433\n  - id: cache\n    port: 6379\n",
            "yaml"), svc.state.version)
        svc.setPolicy(PathRendererTest.parse("\$.svcs"), ListStrategy.ID, "id", svc.state.version)

        val resultBefore = svc.recompute()
        val bundle = BundleService.export(svc.state, resultBefore, "yaml")
        val expectedFp = bundle.summary.resultFingerprint

        // Simulate a fresh machine: import bundle into a clean data dir.
        val bundleNode = JsonParser.parse(JsonWriter.write(bundle.toJson()))
        val (importedState, importedResult) = BundleService.importBundle(bundleNode)
        val errors = BundleService.verifyRoundTrip(bundle, importedState, importedResult)
        assertTrue(errors.isEmpty(), errors.toString())

        val output = OutputBuilder.build(importedResult)
        assertEquals(expectedFp, semmerge.iof.Fingerprint.of(output))

        // Result is a deep copy: mutating it does not touch parsed inputs.
        val svcs = (output as SMap).get("svcs") as SList
        ((svcs.items[0] as SMap).get("port") as SScalar)
        val freshResult = svc.recompute()
        val freshOut = OutputBuilder.build(freshResult)
        assertEquals(expectedFp, semmerge.iof.Fingerprint.of(freshOut))
    }

    @Test
    fun `yaml and json export of same merge share fingerprint`() {
        val b = MergeTestSupport.yaml("a: 1\nnested:\n  x: true\n")
        val a = MergeTestSupport.yaml("a: 2\nnested:\n  x: true\n")
        val c = MergeTestSupport.yaml("a: 1\nnested:\n  x: true\n")
        val result = MergeTestSupport.merge(b, a, c)
        val out = OutputBuilder.build(result)
        val yamlText = BundleService.renderOutput(out, "yaml")
        val jsonText = BundleService.renderOutput(out, "json")
        val reparsedYaml = semmerge.yaml.YamlParser.parse(yamlText)
        val reparsedJson = JsonParser.parse(jsonText)
        assertEquals(
            semmerge.iof.Fingerprint.of(out),
            semmerge.iof.Fingerprint.of(reparsedYaml),
        )
        assertEquals(
            semmerge.iof.Fingerprint.of(out),
            semmerge.iof.Fingerprint.of(reparsedJson),
        )
    }
}
