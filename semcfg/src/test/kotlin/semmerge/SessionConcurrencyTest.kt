package semmerge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import semmerge.merge.ListStrategy
import semmerge.merge.PolicyRegistry
import semmerge.merge.Resolution
import semmerge.session.InputSide
import semmerge.session.OptimisticLockException
import semmerge.session.RawInput
import semmerge.session.ResolutionRequest
import semmerge.session.SessionService
import semmerge.session.SessionStore
import java.nio.file.Files

class SessionConcurrencyTest {

    private fun newService(dir: java.nio.file.Path): SessionService {
        val svc = SessionService(SessionStore(dir))
        svc.updateInput(InputSide.BASE, RawInput("x: 1\ny: 1\n", "yaml"), svc.state.version)
        svc.updateInput(InputSide.BRANCH_A, RawInput("x: 2\ny: 2\n", "yaml"), svc.state.version)
        svc.updateInput(InputSide.BRANCH_B, RawInput("x: 3\ny: 3\n", "yaml"), svc.state.version)
        return svc
    }

    @Test
    fun `stale batch is rejected wholesale and cannot overwrite solved items`() {
        val dir = Files.createTempDirectory("semcfg-cc")
        val svc = newService(dir)
        val merge = svc.recompute()
        val keys = merge.conflicts.map { it.key }.toSet()
        assertTrue(keys.contains("\$.x#VALUE"))
        assertTrue(keys.contains("\$.y#VALUE"))

        val staleVersion = svc.state.version

        // Another tab solves x first.
        svc.resolve(listOf(ResolutionRequest("\$.x#VALUE", Resolution.TakeA)), svc.state.version)

        // Stale page submits a batch including already-solved x plus open y.
        val ex = assertThrows(OptimisticLockException::class.java) {
            svc.resolve(
                listOf(
                    ResolutionRequest("\$.x#VALUE", Resolution.Delete),
                    ResolutionRequest("\$.y#VALUE", Resolution.TakeB),
                ),
                staleVersion,
            )
        }
        assertEquals(staleVersion, ex.expected)
        assertTrue(ex.alreadySolvedKeys.contains("\$.x#VALUE"))

        // The solved x must remain TakeA; y is still open (batch aborted before write).
        val after = svc.recompute()
        assertTrue(after.appliedDecisions.contains("\$.x#VALUE"))
        assertTrue(after.conflicts.any { it.key == "\$.y#VALUE" && it.key !in after.appliedDecisions })
    }

    @Test
    fun `batch on current version reports unknown keys as stale`() {
        val dir = Files.createTempDirectory("semcfg-cc2")
        val svc = newService(dir)
        val v = svc.state.version
        val commit = svc.resolve(
            listOf(
                ResolutionRequest("\$.x#VALUE", Resolution.TakeA),
                ResolutionRequest("\$.gone#VALUE", Resolution.TakeB),
            ),
            v,
        )
        assertEquals(listOf("\$.x#VALUE"), commit.acceptedKeys)
        assertEquals(listOf("\$.gone#VALUE"), commit.rejectedStaleKeys)
    }

    @Test
    fun `policies and inputs bump optimistic version`() {
        val dir = Files.createTempDirectory("semcfg-cc3")
        val svc = newService(dir)
        val v = svc.state.version
        svc.setPolicy(PathRendererTest.parse("\$.xs"), ListStrategy.ID, "id", v)
        assertThrows(OptimisticLockException::class.java) {
            svc.setPolicy(PathRendererTest.parse("\$.ys"), ListStrategy.REPLACE, "id", v)
        }
    }
}
