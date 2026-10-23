package io.github.selectionmenucontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real FIFO/journal/recovery cores with injected storage receipts, not Android or physical power loss. */
class RuleStorageFaultMatrixTest {
    private class FaultStorage : PendingJournalStorage {
        val memory = mutableMapOf<String, String>()
        private val disk = mutableMapOf<String, String>()
        val receipts = ArrayDeque<Boolean>()
        var failRead = false
        var throwCommits = 0
        var commits = 0

        override fun readString(key: String): String? {
            check(!failRead) { "Injected preferences read failure" }
            return memory[key]
        }

        override fun commit(values: Map<String, String?>): Boolean {
            commits++
            values.forEach { (key, value) ->
                if (value == null) memory.remove(key) else memory[key] = value
            }
            if (throwCommits > 0) { throwCommits--; error("Injected commit failure") }
            if (receipts.isNotEmpty() && !receipts.removeFirst()) return false
            disk.clear()
            disk.putAll(memory)
            return true
        }

        /** Discard volatile preferences and rebuild only from acknowledged model disk writes. */
        fun restart(): PendingJournalStore {
            memory.clear()
            memory.putAll(disk)
            return PendingJournalStore(this)
        }
    }

    private val baseline = RuleConfig(emptySet(), emptyList())
    private val first = RuleConfig(setOf(B), listOf(B, A), mapOf(A to "搜索"))
    private val latest = RuleConfig(setOf(C), listOf(C, A, B), mapOf(A to "翻译", B to "朗读"))
    private val external = RuleConfig(setOf(A), listOf(B, C), mapOf(C to "外部名称"))

    private fun decide(record: PendingJournalRecord, current: String?, readable: Boolean = true) =
        PendingRuleRecovery.decide(record.baseline, record.target, record.inFlight, current, readable)

    @Test fun durableRawBaselineMatrixDistinguishesMissingLegacyAndCanonicalEmpty() {
        for (rawBaseline in listOf(null, "v2:", baseline.encode(), first.encode())) {
            val storage = FaultStorage()
            val record = PendingJournalRecord("raw-baseline", rawBaseline, latest.encode(), first.encode())
            assertTrue(PendingJournalStore(storage).write(record))
            val reopened = storage.restart()
            val restored = (reopened.read() as PendingJournalRead.Found).record
            assertEquals(record, restored)
            assertEquals(PendingRuleRecovery.Action.REPLAY, decide(restored, rawBaseline))
            assertEquals(PendingRuleRecovery.Action.REPLAY, decide(restored, first.encode()))
            assertEquals(PendingRuleRecovery.Action.COMPLETE, decide(restored, latest.encode()))
            assertEquals(PendingRuleRecovery.Action.CONFLICT, decide(restored, external.encode()))
            assertEquals(PendingRuleRecovery.Action.WAIT, decide(restored, null, false))
            val decoded = RuleConfig.decode(restored.target)
            assertEquals(latest.hiddenComponents, decoded.hiddenComponents)
            assertEquals(latest.orderedComponents, decoded.orderedComponents)
            assertEquals(latest.aliases, decoded.aliases)
            if (rawBaseline == null || rawBaseline == "v2:") {
                assertEquals("Raw missing/legacy identity must not be canonicalized",
                    PendingRuleRecovery.Action.CONFLICT, decide(restored, baseline.encode()))
            }
        }
    }

    @Test fun twoFailedInitialStageReceiptsStopRootAndCannotSurviveModelRestart() = runBlocking {
        withTimeout(3_000) {
            val storage = FaultStorage()
            storage.receipts.addAll(listOf(false, false))
            val store = PendingJournalStore(storage)
            val record = PendingJournalRecord("no-disk-receipt", null, latest.encode(), null)
            val writer = OrderedStageWriter(this, Dispatchers.Unconfined)
            val stage = writer.submit { store.write(record) }
            var rootWrites = 0
            var uncertain = false
            val queue = RuleSaveQueue(this, persist = { _: RuleConfig ->
                if (!stage.await()) { uncertain = true; false }
                else { rootWrites++; true }
            }, read = { baseline }, keepDraftOnFailure = { uncertain })
            queue.initialize(baseline)
            queue.submit(latest)
            val state = queue.state.filterNotNull().first { !it.pending }
            assertEquals(0, rootWrites)
            assertEquals(2, storage.commits)
            assertSame(latest, state.value)
            assertTrue(state.verificationPending)
            assertEquals(PendingJournalRead.Found(record), store.read())
            assertEquals("No receipt means no process-death durability guarantee",
                PendingJournalRead.Missing, storage.restart().read())
        }
    }

    @Test fun failedNewStageKeepsNewestLiveDraftButRestartRecoversOnlyLastDurableTarget() = runBlocking {
        withTimeout(3_000) {
            val storage = FaultStorage()
            val store = PendingJournalStore(storage)
            val original = PendingJournalRecord("durable", baseline.encode(), first.encode(), null)
            assertTrue(store.write(original))
            storage.receipts.addAll(listOf(false, false))
            val newer = nextPendingJournalRecord("volatile", original, null, external.encode(), latest.encode())
            val stage = OrderedStageWriter(this, Dispatchers.Unconfined).submit { store.write(newer) }
            var rootWrites = 0
            val queue = RuleSaveQueue(this, persist = { _: RuleConfig ->
                if (!stage.await()) false else { rootWrites++; true }
            }, read = { baseline }, keepDraftOnFailure = { true })
            queue.initialize(baseline)
            queue.submit(latest)
            val state = queue.state.filterNotNull().first { !it.pending }
            assertEquals(0, rootWrites)
            assertSame(latest, state.value)
            assertTrue(state.verificationPending)
            val recoveredInProcess = pendingRecoveryRecord(store.read(), newer, latest.encode())
            assertEquals(PendingJournalRead.Found(newer), recoveredInProcess)
            val reopened = storage.restart()
            assertEquals(PendingJournalRead.Found(original), reopened.read())
            assertEquals(PendingRuleRecovery.Action.REPLAY, decide(original, baseline.encode()))
        }
    }

    @Test fun twoFailedInflightReceiptsPreventLaunchEvenWhenMemoryAlreadyContainsInflight() = runBlocking {
        withTimeout(3_000) {
            val storage = FaultStorage()
            val store = PendingJournalStore(storage)
            val staged = PendingJournalRecord("staged", null, latest.encode(), null)
            assertTrue(store.write(staged))
            storage.receipts.addAll(listOf(false, false))
            var rootWrites = 0
            val queue = RuleSaveQueue(this, persist = { next: RuleConfig ->
                val record = (store.read() as PendingJournalRead.Found).record
                if (!store.write(record.copy(inFlight = next.encode()))) false
                else { rootWrites++; true }
            }, read = { baseline }, keepDraftOnFailure = { true })
            queue.initialize(baseline)
            queue.submit(latest)
            val state = queue.state.filterNotNull().first { !it.pending }
            assertTrue(state.verificationPending)
            assertEquals(0, rootWrites)
            assertEquals(latest.encode(), (store.read() as PendingJournalRead.Found).record.inFlight)
            assertEquals(PendingJournalRead.Found(staged), storage.restart().read())
        }
    }

    private fun uncertainWriterModelRestart(committed: Boolean) = runBlocking {
        withTimeout(3_000) {
            val storage = FaultStorage()
            val store = PendingJournalStore(storage)
            val release = CompletableDeferred<Unit>()
            var uncertain = false
            var global: String? = null
            val writes = mutableListOf<RuleConfig>()
            val queue = RuleSaveQueue(this, persist = { next: RuleConfig ->
                val record = (store.read() as PendingJournalRead.Found).record
                assertTrue(store.write(record.copy(inFlight = next.encode())))
                writes.add(next)
                release.await()
                if (committed) global = next.encode()
                uncertain = true
                assertTrue(settlePendingRuleWrite(store.read(), next.encode(), false,
                    false, true, store::write, store::clear))
                false
            }, read = { baseline }, keepDraftOnFailure = { uncertain })
            queue.initialize(baseline)
            fun submit(id: String, draft: RuleConfig) {
                val previous = (store.read() as? PendingJournalRead.Found)?.record
                assertTrue(store.write(nextPendingJournalRecord(id, previous, null, global, draft.encode())))
                queue.submit(draft)
            }
            submit("first", first)
            submit("latest", latest)
            release.complete(Unit)
            val state = queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf(first), writes)
            assertTrue(state.verificationPending)
            assertSame(latest, state.value)
            val reopened = storage.restart()
            val record = (reopened.read() as PendingJournalRead.Found).record
            assertEquals("latest", record.id)
            assertNull(record.baseline)
            assertEquals(first.encode(), record.inFlight)
            assertEquals(latest.encode(), record.target)
            assertEquals(PendingRuleRecovery.Action.REPLAY, decide(record, global))
            assertEquals(PendingRuleRecovery.Action.CONFLICT, decide(record, external.encode()))
            assertEquals(PendingRuleRecovery.Action.WAIT, decide(record, global, false))
            assertEquals(PendingRuleRecovery.Action.COMPLETE, decide(record, latest.encode()))
        }
    }

    @Test fun uncertainWriterBeforeProviderCommitRetainsNewestDurableDraftAcrossModelRestart() =
        uncertainWriterModelRestart(false)

    @Test fun uncertainWriterAfterProviderCommitRetainsKnownInflightAcrossModelRestart() =
        uncertainWriterModelRestart(true)

    @Test fun failedCleanupAfterVerifiedWriteRebuildsAsCompleteAndNeedsNoSecondRootWrite() {
        val storage = FaultStorage()
        var store = PendingJournalStore(storage)
        val record = PendingJournalRecord("complete", baseline.encode(), latest.encode(), latest.encode())
        assertTrue(store.write(record))
        storage.receipts.addAll(listOf(false, false))
        assertFalse(settlePendingRuleWrite(store.read(), latest.encode(), true,
            true, false, store::write, store::clear))
        assertEquals(PendingJournalRead.Missing, store.read())
        store = storage.restart()
        val restored = (store.read() as PendingJournalRead.Found).record
        assertEquals(PendingRuleRecovery.Action.COMPLETE, decide(restored, latest.encode()))
        assertTrue(finishPendingJournalCleanup(store, restored))
        assertEquals(PendingJournalRead.Missing, storage.restart().read())
    }

    @Test fun journalReadFaultCannotBeReplacedByRememberedDraftOrDestructiveSettlement() {
        val storage = FaultStorage()
        val store = PendingJournalStore(storage)
        val record = PendingJournalRecord("read-fault", baseline.encode(), latest.encode(), first.encode())
        assertTrue(store.write(record))
        val commitsBeforeFault = storage.commits
        storage.failRead = true
        assertEquals(PendingJournalRead.Failed, store.read())
        assertEquals(PendingJournalRead.Failed, pendingRecoveryRecord(store.read(), record, latest.encode()))
        assertFalse(settlePendingRuleWrite(store.read(), latest.encode(), true,
            true, false, store::write, store::clear))
        assertFalse(store.clear(record.id))
        assertEquals(commitsBeforeFault, storage.commits)
        storage.failRead = false
        assertEquals(PendingJournalRead.Found(record), storage.restart().read())
        assertEquals(PendingRuleRecovery.Action.REPLAY, decide(record, first.encode()))
    }

    @Test fun partialAndCorruptJournalMatrixFailsClosedUntilDurableRecordCanBeRead() {
        val malformed = listOf(
            mapOf("pending_id" to "partial"),
            mapOf("pending_target" to latest.encode()),
            mapOf("pending_id" to "bad-inflight", "pending_target" to latest.encode(), "pending_inflight" to "bad"),
            mapOf("pending_id" to "bad-baseline", "pending_target" to latest.encode(), "pending_baseline" to "bad"),
        )
        for (partial in malformed) {
            val storage = FaultStorage()
            val store = PendingJournalStore(storage)
            val durable = PendingJournalRecord("durable", null, latest.encode(), first.encode())
            assertTrue(store.write(durable))
            storage.memory.clear()
            storage.memory.putAll(partial)
            assertEquals(PendingJournalRead.Failed, store.read())
            assertEquals(PendingJournalRead.Failed,
                pendingRecoveryRecord(store.read(), durable, latest.encode()))
            assertFalse(settlePendingRuleWrite(store.read(), latest.encode(), true,
                true, false, store::write, store::clear))
            assertEquals(PendingJournalRead.Found(durable), storage.restart().read())
        }
    }

    @Test fun commitExceptionAndFalseReceiptRemainBoundedAndPreserveTheOriginalDiskBaseline() {
        val storage = FaultStorage()
        val store = PendingJournalStore(storage)
        val durable = PendingJournalRecord("durable", "v2:", first.encode(), null)
        assertTrue(store.write(durable))
        storage.throwCommits = 1
        storage.receipts.add(false)
        val newer = nextPendingJournalRecord("volatile", durable, null, external.encode(), latest.encode())
        assertFalse(store.write(newer))
        assertEquals(3, storage.commits)
        assertEquals(PendingJournalRead.Found(newer), store.read())
        assertEquals(PendingJournalRead.Found(durable), storage.restart().read())
    }

    companion object {
        private const val A = "p/p.First"
        private const val B = "p/p.Second"
        private const val C = "p/p.Third"
    }
}
