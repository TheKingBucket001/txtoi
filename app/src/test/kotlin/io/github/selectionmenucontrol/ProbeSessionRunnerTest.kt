package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSessionRunnerTest {
    @Test
    fun verifiedResultIsPublishedWhileCleanupIsStillBlocked() = runBlocking {
        val published = CompletableDeferred<String>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        val check = launch {
            runProbeSession<String, String>(
                prepare = { "owned-session" },
                evaluate = { "ready" },
                publish = { if (published.complete(it)) Unit else Unit },
                cleanup = { releaseCleanup.await(); cleaned = true },
            )
        }
        assertEquals("ready", published.await())
        assertFalse(cleaned)
        assertTrue(check.isActive)
        releaseCleanup.complete(Unit)
        check.join()
        assertTrue(cleaned)
    }

    @Test
    fun failedPreparationResultStillRetiresItsOwnedSession() = runBlocking {
        val events = mutableListOf<String>()
        val result = runProbeSession(
            prepare = { "partially-written" },
            evaluate = { "unconfirmed" },
            publish = { events += it },
            cleanup = { events += "clean:$it" },
        )
        assertEquals("unconfirmed", result)
        assertEquals(listOf("unconfirmed", "clean:partially-written"), events)
    }

    @Test
    fun queryCancellationStillRetiresTheSessionWithoutPublishingReady() = runBlocking {
        var cleaned = false
        var published = false
        try {
            runProbeSession(
                prepare = { "session" },
                evaluate = { throw CancellationException("cancelled") },
                publish = { _: String -> published = true },
                cleanup = { cleaned = true },
            )
        } catch (_: CancellationException) {
            // Cancellation must propagate rather than becoming a successful check.
        }
        assertTrue(cleaned)
        assertFalse(published)
    }
}
