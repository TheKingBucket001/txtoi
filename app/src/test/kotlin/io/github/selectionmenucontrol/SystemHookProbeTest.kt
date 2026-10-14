package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SystemHookProbeTest {
    @Test fun missingResponsePublishesWithoutPollingAndStillOwnsCleanup() = runBlocking {
        val published = CompletableDeferred<SystemRuleStore.HookStatus>()
        val finishCleanup = CompletableDeferred<Unit>()
        var queries = 0
        var reads = 0
        var cleaned = false
        val task = launch {
            runProbeSession<String, SystemRuleStore.HookStatus>(
                prepare = { "owned-nonce" },
                evaluate = {
                    querySystemHook(
                        query = { queries++ },
                        readResponse = { reads++; SystemRuleStore.HookStatus.unavailable() },
                    )
                },
                publish = { published.complete(it); Unit },
                cleanup = { finishCleanup.await(); cleaned = true },
            )
        }
        try {
            val status = published.await()
            assertEquals(ProbeProtocol.Status.NO_RESPONSE, status.status)
            assertFalse(status.loadedForCurrentBoot)
            assertEquals(1, queries)
            assertEquals(1, reads)
            assertTrue(task.isActive)
            assertFalse(cleaned)
        } finally {
            finishCleanup.complete(Unit)
            task.join()
        }
        assertTrue(cleaned)
    }

    @Test fun responseIsReadAfterSynchronousQueryFinishesEvenWhenQueryIsSlow() = runBlocking {
        val enteredQuery = CompletableDeferred<Unit>()
        val finishQuery = CompletableDeferred<Unit>()
        val published = CompletableDeferred<SystemRuleStore.HookStatus>()
        var reads = 0
        val task = launch {
            published.complete(querySystemHook(
                query = { enteredQuery.complete(Unit); finishQuery.await() },
                readResponse = { reads++; SystemRuleStore.HookStatus(ProbeProtocol.Status.READY) },
            ))
        }
        try {
            enteredQuery.await()
            assertEquals(0, reads)
            assertFalse(published.isCompleted)
        } finally {
            finishQuery.complete(Unit)
            task.join()
        }
        assertTrue(published.await().loadedForCurrentBoot)
        assertEquals(1, reads)
    }

    @Test fun failedQueryDoesNotReadResponseAndStillCleansTheSession() = runBlocking {
        var cleaned = false
        val status = runProbeSession<String, SystemRuleStore.HookStatus>(
            prepare = { "session" },
            evaluate = {
                querySystemHook(
                    query = { throw IllegalStateException("binder failed") },
                    readResponse = { error("Must not read a response for a failed query") },
                )
            },
            publish = { },
            cleanup = { cleaned = true },
        )
        assertEquals(ProbeProtocol.Status.QUERY_FAILED, status.status)
        assertFalse(status.loadedForCurrentBoot)
        assertTrue(cleaned)
    }

    @Test fun failedReadIsDistinctFromQueryFailure() = runBlocking {
        val status = querySystemHook(
            query = { },
            readResponse = { throw IllegalStateException("provider failed") },
        )
        assertEquals(ProbeProtocol.Status.READ_FAILED, status.status)
        assertFalse(status.loadedForCurrentBoot)
    }

    @Test fun queryCancellationNeverReadsOrPublishesAndStillCleans() = runBlocking {
        var cleaned = false
        var published = false
        try {
            runProbeSession<String, SystemRuleStore.HookStatus>(
                prepare = { "session" },
                evaluate = {
                    querySystemHook(
                        query = { throw CancellationException("cancelled") },
                        readResponse = { error("Cancelled query must not read") },
                    )
                },
                publish = { published = true },
                cleanup = { cleaned = true },
            )
            fail("Must propagate cancellation")
        } catch (_: CancellationException) {
            assertTrue(cleaned)
            assertFalse(published)
        }
    }

    @Test fun completedBinderCallCannotPublishAfterJobCancellation() = runBlocking {
        var read = false
        var published = false
        var cleaned = false
        val task = launch {
            runProbeSession<String, SystemRuleStore.HookStatus>(
                prepare = { "session" },
                evaluate = {
                    querySystemHook(
                        query = { currentCoroutineContext().cancel() },
                        readResponse = { read = true; SystemRuleStore.HookStatus(ProbeProtocol.Status.READY) },
                    )
                },
                publish = { published = true },
                cleanup = { cleaned = true },
            )
        }
        task.join()
        assertFalse(read)
        assertFalse(published)
        assertTrue(cleaned)
    }

    @Test fun cancellationDuringResponseReadCannotPublishReady() = runBlocking {
        var published = false
        var cleaned = false
        val task = launch {
            runProbeSession<String, SystemRuleStore.HookStatus>(
                prepare = { "session" },
                evaluate = {
                    querySystemHook(
                        query = { },
                        readResponse = {
                            currentCoroutineContext().cancel()
                            SystemRuleStore.HookStatus(ProbeProtocol.Status.READY)
                        },
                    )
                },
                publish = { published = true },
                cleanup = { cleaned = true },
            )
        }
        task.join()
        assertFalse(published)
        assertTrue(cleaned)
    }
}
