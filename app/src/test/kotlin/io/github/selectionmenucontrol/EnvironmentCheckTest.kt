package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnvironmentCheckTest {
    private fun root(failure: RootAccess.Failure) = RootAccess.Result(
        RootAccess.ExecutionResult(failure, 5L, 0, false),
    )

    @Test fun rejectedRootNeverPreparesOrQueriesSystemScope() = runBlocking {
        var scopeCalls = 0
        val states = mutableListOf<GateState>()
        val result = checkEnvironment(
            checkRoot = { root(RootAccess.Failure.NOT_ROOT) },
            checkScope = { scopeCalls++; error("scope must not run") },
            publish = { states += it },
        )
        assertEquals(0, scopeCalls)
        assertFalse(result.rootReady)
        assertFalse(result.systemHookReady)
        assertFalse(result.checking)
        assertFalse(result.cleanupPending)
        assertTrue(result.rootChecked)
        assertEquals("su 返回的身份不是 uid=0", result.rootMessage)
    }

    @Test fun missingScopeRetainsTheVerifiedRootIdentity() = runBlocking {
        val status = SystemRuleStore.HookStatus.unavailable()
        val result = checkEnvironment(
            checkRoot = { root(RootAccess.Failure.NONE) },
            checkScope = { publish -> publish(status); status },
            publish = { },
        )
        assertTrue(result.rootReady)
        assertEquals("已获得 uid=0", result.rootMessage)
        assertFalse(result.systemHookReady)
        assertEquals(status.message, result.systemMessage)
    }

    @Test fun verifiedScopeIsPublishedBeforeItsCleanupFinishes() = runBlocking {
        val ready = CompletableDeferred<GateState>()
        val finished = CompletableDeferred<GateState>()
        val finishCleanup = CompletableDeferred<Unit>()
        val task = launch {
            finished.complete(checkEnvironment(
                checkRoot = { root(RootAccess.Failure.NONE) },
                checkScope = { publish ->
                    val status = SystemRuleStore.HookStatus(ProbeProtocol.Status.READY)
                    publish(status)
                    finishCleanup.await()
                    status
                },
                publish = {
                    if (it.systemHookReady) ready.complete(it)
                },
            ))
        }
        val state = ready.await()
        assertTrue(state.rootReady)
        assertFalse(state.checking)
        assertTrue(state.cleanupPending)
        assertTrue(task.isActive)
        finishCleanup.complete(Unit)
        task.join()
        assertFalse(finished.await().cleanupPending)
    }

    @Test fun failedScopeKeepsCleanupPendingUntilTheSessionReturns() = runBlocking {
        val failed = CompletableDeferred<GateState>()
        val finished = CompletableDeferred<GateState>()
        val finishCleanup = CompletableDeferred<Unit>()
        val status = SystemRuleStore.HookStatus.unavailable()
        val task = launch {
            finished.complete(checkEnvironment(
                checkRoot = { root(RootAccess.Failure.NONE) },
                checkScope = { publish ->
                    publish(status)
                    finishCleanup.await()
                    status
                },
                publish = {
                    if (!it.checking && it.rootReady) failed.complete(it)
                },
            ))
        }
        val state = failed.await()
        assertTrue(state.rootReady)
        assertFalse(state.systemHookReady)
        assertFalse(state.checking)
        assertTrue(state.cleanupPending)
        assertTrue(task.isActive)
        finishCleanup.complete(Unit)
        task.join()
        val result = finished.await()
        assertFalse(result.cleanupPending)
        assertTrue(result.rootReady)
        assertFalse(result.systemHookReady)
        assertEquals(status.message, result.systemMessage)
    }

    @Test fun scopeExceptionCannotRevokeAPreviouslyVerifiedRoot() = runBlocking {
        val result = checkEnvironment(
            checkRoot = { root(RootAccess.Failure.NONE) },
            checkScope = { throw IllegalStateException("query failed") },
            publish = { },
        )
        assertTrue(result.rootReady)
        assertFalse(result.systemHookReady)
        assertFalse(result.cleanupPending)
        assertEquals("已获得 uid=0", result.rootMessage)
    }

    @Test fun cancellationPropagatesWithoutPublishingAFailedOrReadyGate() = runBlocking {
        val states = mutableListOf<GateState>()
        try {
            checkEnvironment(
                checkRoot = { root(RootAccess.Failure.NONE) },
                checkScope = { throw CancellationException("cancelled") },
                publish = { states += it },
            )
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, states.size)
            assertTrue(states.single().checking)
            assertFalse(states.single().systemHookReady)
        }
    }
}
