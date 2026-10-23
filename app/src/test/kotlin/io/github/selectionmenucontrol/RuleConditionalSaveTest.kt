package io.github.selectionmenucontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production save result, FIFO and journal contracts without an Android Context. */
class RuleConditionalSaveTest {
    private class Storage : PendingJournalStorage {
        private val values = mutableMapOf<String, String>()
        override fun readString(key: String) = values[key]
        override fun commit(values: Map<String, String?>): Boolean {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }

    private fun draft(alias: String) = SystemRuleStore.Snapshot(emptySet(), emptyList(), mapOf(A to alias))
    private fun execution(failure: RootAccess.Failure) = RootAccess.ExecutionResult(failure, 0, 0, false)

    @Test fun authoritativeObservationKeepsRawLegacyAndMissingIdentityButDraftsHaveNoCredential() {
        for (raw in listOf(null, "v2:", "v3::", draft("名称").encode())) {
            val page = SystemRuleStore.decodeGlobal(raw)
            assertTrue(page.valid)
            assertNotNull(page.sourceGlobal)
            assertEquals(raw, page.sourceGlobal.value)
            assertTrue(page.sourceGlobal.valid)
            assertNull(SystemRuleStore.decode(raw).sourceGlobal)
            assertNull(SystemRuleStore.Snapshot(page.hiddenComponents, page.orderedComponents, page.aliases).sourceGlobal)
            if (raw == null || raw == "v2:") assertNotEquals(raw, page.encode())
        }
        assertNull(SystemRuleStore.decodeGlobal("broken").sourceGlobal)
    }

    @Test fun rejectedRefreshCannotGrantANewerRawBaselineToAnOlderDraft() = runBlocking {
        val loaded = SystemRuleStore.decodeGlobal("v2:")
        val queue = RuleSaveQueue(this, persist = { _: SystemRuleStore.Snapshot -> true }, read = { loaded })
        assertTrue(queue.refresh(loaded, null))
        val beforeLoad = queue.state.value
        val local = draft("本地")
        queue.submit(local)
        val external = SystemRuleStore.decodeGlobal(draft("外部").encode())
        assertFalse(queue.refresh(external, beforeLoad))
        assertSame(local, queue.state.value!!.value)
        assertNull(queue.state.value!!.value.sourceGlobal)
    }

    @Test fun staleResultStopsAllLaterOldDraftsAndKeepsTheLatestJournalForReconciliation() = runBlocking {
        withTimeout(3_000) {
            val page = SystemRuleStore.decodeGlobal("v2:")
            val first = draft("第一次")
            val latest = draft("最后一次")
            val external = draft("外部修改").encode()
            val store = PendingJournalStore(Storage())
            val release = CompletableDeferred<Unit>()
            var needsReconciliation = false
            val attempts = mutableListOf<String>()
            var backupCalls = 0
            val queue = RuleSaveQueue(this, persist = { next: SystemRuleStore.Snapshot ->
                val record = (store.read() as PendingJournalRead.Found).record
                assertTrue(store.write(record.copy(inFlight = next.encode())))
                attempts.add(next.encode())
                release.await()
                val result = SystemRuleStore.saveDetailed(next, record.baseline, { _, expected ->
                    assertEquals("v2:", expected)
                    execution(RootAccess.Failure.STALE_REVISION)
                }, { backupCalls++; true })
                needsReconciliation = result.requiresReconciliation()
                assertFalse(result.uncertain)
                assertTrue(settlePendingRuleWrite(store.read(), next.encode(), next === latest,
                    result.verified, needsReconciliation, store::write, store::clear))
                result.verified
            }, read = { SystemRuleStore.decodeGlobal(external) },
                keepDraftOnFailure = { needsReconciliation })
            queue.initialize(page)
            fun submit(id: String, next: SystemRuleStore.Snapshot) {
                val previous = (store.read() as? PendingJournalRead.Found)?.record
                assertTrue(store.write(nextPendingJournalRecord(id, previous, null, page.sourceGlobal.value, next.encode())))
                queue.submit(next)
            }
            submit("first", first)
            submit("latest", latest)
            release.complete(Unit)
            val unresolved = queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf(first.encode()), attempts)
            assertEquals(0, backupCalls)
            assertSame(latest, unresolved.value)
            assertTrue(unresolved.verificationPending)
            val retained = (store.read() as PendingJournalRead.Found).record
            assertEquals("v2:", retained.baseline)
            assertEquals(first.encode(), retained.inFlight)
            assertEquals(latest.encode(), retained.target)
            assertEquals(PendingRuleRecovery.Action.CONFLICT, PendingRuleRecovery.decide(
                retained.baseline, retained.target, retained.inFlight, external, true))
            assertTrue(store.clear(retained.id))
            assertTrue(queue.resolve(SystemRuleStore.decodeGlobal(external), unresolved))
            assertEquals(external, queue.state.value!!.value.sourceGlobal.value)
            assertFalse(queue.state.value!!.verificationPending)
            assertEquals(listOf(first.encode()), attempts)
        }
    }

    @Test fun verifiedFifoAdvancesExpectedFromRawBaselineThroughEachAcknowledgedWrite() = runBlocking {
        withTimeout(3_000) {
            val page = SystemRuleStore.decodeGlobal("v2:")
            val first = draft("搜索")
            val middle = draft("翻译")
            val last = draft("搜索")
            assertNotSame(first, last)
            val release = CompletableDeferred<Unit>()
            val store = PendingJournalStore(Storage())
            var global: String? = page.sourceGlobal.value
            var accepted: SystemRuleStore.Snapshot? = null
            var needsReconciliation = false
            val expectedValues = mutableListOf<String?>()
            val queue = RuleSaveQueue(this, persist = { next: SystemRuleStore.Snapshot ->
                val record = (store.read() as PendingJournalRead.Found).record
                assertTrue(store.write(record.copy(inFlight = next.encode())))
                if (next === first) release.await()
                val result = SystemRuleStore.saveDetailed(next, record.baseline, { encoded, expected ->
                    expectedValues.add(expected)
                    if (global != expected) execution(RootAccess.Failure.STALE_REVISION)
                    else { global = encoded; execution(RootAccess.Failure.NONE) }
                }, { true })
                needsReconciliation = result.requiresReconciliation()
                val settled = settlePendingRuleWrite(store.read(), next.encode(), accepted === next,
                    result.verified, needsReconciliation, store::write, store::clear)
                result.verified && settled
            }, read = { SystemRuleStore.decodeGlobal(global) }, keepDraftOnFailure = { needsReconciliation })
            queue.initialize(page)
            for ((index, next) in listOf(first, middle, last).withIndex()) {
                accepted = next
                val previous = (store.read() as? PendingJournalRead.Found)?.record
                assertTrue(store.write(nextPendingJournalRecord("fifo-$index", previous, null,
                    page.sourceGlobal.value, next.encode())))
                queue.submit(next)
            }
            release.complete(Unit)
            val done = queue.state.filterNotNull().first { !it.pending }
            assertFalse(done.verificationPending)
            assertEquals(listOf("v2:", first.encode(), middle.encode()), expectedValues)
            assertEquals(last.encode(), global)
            assertEquals(PendingJournalRead.Missing, store.read())
        }
    }

    @Test fun replayFromKnownInflightRebasesDurablyBeforeItsConditionalWrite() {
        val store = PendingJournalStore(Storage())
        val inFlight = draft("第一次").encode()
        val latest = draft("最后一次")
        val original = PendingJournalRecord("replay", null, latest.encode(), inFlight)
        assertTrue(store.write(original))
        assertEquals(PendingRuleRecovery.Action.REPLAY, PendingRuleRecovery.decide(
            original.baseline, original.target, original.inFlight, inFlight, true))
        val rebased = original.copy(baseline = inFlight, inFlight = null)
        assertTrue(store.write(rebased))
        val durable = (store.read() as PendingJournalRead.Found).record
        assertEquals(latest.encode(), durable.target)
        var global = inFlight
        val result = SystemRuleStore.saveDetailed(latest, durable.baseline, { encoded, expected ->
            assertEquals(inFlight, expected)
            if (global != expected) execution(RootAccess.Failure.STALE_REVISION)
            else { global = encoded; execution(RootAccess.Failure.NONE) }
        }, { true })
        assertTrue(result.verified)
        assertFalse(result.requiresReconciliation())
        assertEquals(latest.encode(), global)
    }

    companion object { private const val A = "p/p.First" }
}
