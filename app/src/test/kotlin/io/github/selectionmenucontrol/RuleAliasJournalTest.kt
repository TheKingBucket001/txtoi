package io.github.selectionmenucontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises complete drafts through the real FIFO, codec and journal settlement boundaries. */
class RuleAliasJournalTest {
    private class MemoryStorage : PendingJournalStorage {
        private val values = mutableMapOf<String, String>()
        override fun readString(key: String) = values[key]
        override fun commit(values: Map<String, String?>): Boolean {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }

    private data class Settlement(
        val written: RuleConfig,
        val journal: PendingJournalRead,
        val global: String,
    )

    private fun mixedEdits(firstVerified: Boolean) = runBlocking {
        withTimeout(3_000) {
            val baseline = RuleConfig(emptySet(), emptyList())
            val named = RuleConfig(emptySet(), emptyList(), mapOf(A to "搜索"))
            val hidden = RuleConfig(setOf(B), named.orderedComponents, named.aliases)
            val ordered = RuleConfig(hidden.hiddenComponents, listOf(C, A, B), hidden.aliases)
            val resetOrder = RuleConfig(ordered.hiddenComponents, emptyList(), ordered.aliases)
            val resetHidden = RuleConfig(emptySet(), resetOrder.orderedComponents, resetOrder.aliases)
            val drafts = listOf(named, hidden, ordered, resetOrder, resetHidden)
            // Returning to the same contents must still retain the final edit's journal ownership.
            assertNotSame(named, resetHidden)
            assertEquals(named.encode(), resetHidden.encode())

            val store = PendingJournalStore(MemoryStorage())
            var global = baseline.encode()
            var accepted: RuleConfig? = null
            var uncertain = false
            var edit = 0
            val started = Channel<RuleConfig>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            val settled = Channel<Settlement>(Channel.UNLIMITED)
            val writes = mutableListOf<RuleConfig>()
            val queue = RuleSaveQueue(this, persist = { next: RuleConfig ->
                val record = (store.read() as PendingJournalRead.Found).record
                assertTrue(store.write(record.copy(inFlight = next.encode())))
                writes.add(next)
                started.send(next)
                release.receive()
                val verified = next !== named || firstVerified
                if (verified) global = next.encode()
                val journalSettled = settlePendingRuleWrite(store.read(), next.encode(), accepted === next,
                    verified, false, store::write, store::clear)
                uncertain = !journalSettled
                if ((verified || !uncertain) && accepted === next) accepted = null
                settled.send(Settlement(next, store.read(), global))
                verified && journalSettled
            }, read = { RuleConfig.decode(global) }, keepDraftOnFailure = { uncertain })
            queue.initialize(baseline)

            fun submit(value: RuleConfig) {
                accepted = value
                val previous = (store.read() as? PendingJournalRead.Found)?.record
                assertTrue(store.write(nextPendingJournalRecord("mixed-${++edit}", previous, null,
                    global, value.encode())))
                queue.submit(value)
            }
            drafts.forEach(::submit)
            val latestJournal = (store.read() as PendingJournalRead.Found).record
            assertEquals(resetHidden.encode(), latestJournal.target)
            assertEquals(baseline.encode(), latestJournal.baseline)
            assertEquals(named.encode(), latestJournal.inFlight)

            drafts.forEachIndexed { index, expected ->
                assertSame(expected, started.receive())
                assertSame(resetHidden, queue.state.value!!.value)
                assertTrue(queue.state.value!!.pending)
                assertEquals(mapOf(A to "搜索"), queue.state.value!!.value.aliases)
                val before = (store.read() as PendingJournalRead.Found).record
                assertEquals(latestJournal.id, before.id)
                assertEquals(resetHidden.encode(), before.target)
                assertEquals(expected.encode(), before.inFlight)
                release.send(Unit)
                val receipt = settled.receive()
                assertSame(expected, receipt.written)
                if (index < drafts.lastIndex) {
                    // Earlier successful or failed writes cannot clear the latest complete target.
                    val remaining = (receipt.journal as PendingJournalRead.Found).record
                    assertEquals(latestJournal.id, remaining.id)
                    assertEquals(resetHidden.encode(), remaining.target)
                    assertEquals(mapOf(A to "搜索"), RuleConfig.decode(remaining.target).aliases)
                    assertSame(resetHidden, queue.state.value!!.value)
                }
                if (index > 0 || firstVerified) {
                    val actual = RuleConfig.decode(receipt.global)
                    assertEquals(expected.hiddenComponents, actual.hiddenComponents)
                    assertEquals(expected.orderedComponents, actual.orderedComponents)
                    assertEquals(mapOf(A to "搜索"), actual.aliases)
                } else {
                    assertEquals(baseline.encode(), receipt.global)
                }
            }
            val done = queue.state.filterNotNull().first { !it.pending }
            assertEquals(drafts, writes)
            assertSame(resetHidden, done.value)
            assertEquals(1, done.successVersion)
            assertFalse(done.verificationPending)
            val finalRules = RuleConfig.decode(global)
            assertTrue(finalRules.hiddenComponents.isEmpty())
            assertTrue(finalRules.orderedComponents.isEmpty())
            assertEquals(mapOf(A to "搜索"), finalRules.aliases)
            assertEquals(PendingJournalRead.Missing, store.read())
        }
    }

    @Test fun aliasThenHideSortAndRestoreKeepsAliasWhileEarlierWritesSucceed() = mixedEdits(true)

    @Test fun earlierDefiniteFailureCannotClearTheLatestAliasDraftOrStopLaterMixedEdits() = mixedEdits(false)

    @Test fun uncertainEarlierWriteRetainsNewestAliasDraftAndJournalForRecovery() = runBlocking {
        withTimeout(3_000) {
            val initial = RuleConfig(emptySet(), emptyList())
            val first = RuleConfig(emptySet(), emptyList(), mapOf(A to "搜索"))
            val latest = RuleConfig(setOf(B), listOf(C, A), mapOf(A to "翻译"))
            val store = PendingJournalStore(MemoryStorage())
            val release = CompletableDeferred<Unit>()
            var accepted: RuleConfig? = null
            var uncertain = false
            val writes = mutableListOf<RuleConfig>()
            val queue = RuleSaveQueue(this, persist = { next: RuleConfig ->
                val record = (store.read() as PendingJournalRead.Found).record
                assertTrue(store.write(record.copy(inFlight = next.encode())))
                writes.add(next)
                release.await()
                uncertain = true
                assertTrue(settlePendingRuleWrite(store.read(), next.encode(), accepted === next,
                    false, true, store::write, store::clear))
                false
            }, read = { initial }, keepDraftOnFailure = { uncertain })
            queue.initialize(initial)
            fun submit(id: String, value: RuleConfig) {
                accepted = value
                val previous = (store.read() as? PendingJournalRead.Found)?.record
                assertTrue(store.write(nextPendingJournalRecord(id, previous, null, initial.encode(), value.encode())))
                queue.submit(value)
            }
            submit("first", first)
            submit("latest", latest)
            release.complete(Unit)
            val unresolved = queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf(first), writes)
            assertSame(latest, unresolved.value)
            assertTrue(unresolved.verificationPending)
            val retained = (store.read() as PendingJournalRead.Found).record
            assertEquals("latest", retained.id)
            assertEquals(initial.encode(), retained.baseline)
            assertEquals(first.encode(), retained.inFlight)
            assertEquals(latest.encode(), retained.target)
            val recoveredDraft = RuleConfig.decode(retained.target)
            assertEquals(setOf(B), recoveredDraft.hiddenComponents)
            assertEquals(listOf(C, A), recoveredDraft.orderedComponents)
            assertEquals(mapOf(A to "翻译"), recoveredDraft.aliases)
        }
    }

    @Test fun interruptedWriteReplaysNewestCompleteAliasTargetFromBaselineOrKnownInFlight() {
        val baseline = RuleConfig(setOf(B), listOf(B, A))
        val inFlight = RuleConfig(baseline.hiddenComponents, baseline.orderedComponents, mapOf(A to "搜索"))
        val target = RuleConfig(setOf(C), listOf(C, A, B), mapOf(A to "翻译", B to "朗读"))
        for (observed in listOf(baseline.encode(), inFlight.encode())) {
            val storage = MemoryStorage()
            assertTrue(PendingJournalStore(storage).write(PendingJournalRecord("crash", baseline.encode(),
                target.encode(), inFlight.encode())))
            // A new journal reader models page/process recreation after the interrupted write.
            val reopened = PendingJournalStore(storage)
            val journal = (reopened.read() as PendingJournalRead.Found).record
            var global = observed
            val action = PendingRuleRecovery.decide(journal.baseline, journal.target, journal.inFlight, global, true)
            assertEquals(PendingRuleRecovery.Action.REPLAY, action)
            if (action == PendingRuleRecovery.Action.REPLAY) global = journal.target
            val restored = RuleConfig.decode(global)
            assertEquals(setOf(C), restored.hiddenComponents)
            assertEquals(listOf(C, A, B), restored.orderedComponents)
            assertEquals(mapOf(A to "翻译", B to "朗读"), restored.aliases)
            assertEquals(PendingRuleRecovery.Action.COMPLETE,
                PendingRuleRecovery.decide(journal.baseline, journal.target, journal.inFlight, global, true))
            assertTrue(settlePendingRuleWrite(reopened.read(), global, true, true, false,
                reopened::write, reopened::clear))
            assertEquals(PendingJournalRead.Missing, reopened.read())
        }
    }

    @Test fun externalAliasChangeConflictsWithPendingTargetInsteadOfOverwritingIt() {
        val baseline = RuleConfig(emptySet(), emptyList())
        val inFlight = RuleConfig(emptySet(), emptyList(), mapOf(A to "搜索"))
        val target = RuleConfig(setOf(B), listOf(C, A), mapOf(A to "翻译"))
        val external = RuleConfig(setOf(C), listOf(B, A), mapOf(A to "其他设备设置"))
        val store = PendingJournalStore(MemoryStorage())
        val record = PendingJournalRecord("conflict", baseline.encode(), target.encode(), inFlight.encode())
        assertTrue(store.write(record))
        var global = external.encode()
        val journal = (store.read() as PendingJournalRead.Found).record
        val action = PendingRuleRecovery.decide(journal.baseline, journal.target, journal.inFlight, global, true)
        if (action == PendingRuleRecovery.Action.REPLAY) global = journal.target
        assertEquals(PendingRuleRecovery.Action.CONFLICT, action)
        assertEquals(external.encode(), global)
        assertEquals(mapOf(A to "其他设备设置"), RuleConfig.decode(global).aliases)
        assertEquals(PendingJournalRead.Found(record), store.read())
        assertEquals(PendingRuleRecovery.Action.WAIT,
            PendingRuleRecovery.decide(journal.baseline, journal.target, journal.inFlight, null, false))
    }

    @Test fun clearingLastAliasRecoversAsV3AndPreservesHiddenAndCustomOrder() {
        val baseline = RuleConfig(setOf(B), listOf(C, A, B), mapOf(A to "搜索"))
        val restoredName = RuleConfig(baseline.hiddenComponents, baseline.orderedComponents, emptyMap())
        assertTrue(baseline.encode().startsWith("v4:"))
        assertTrue(restoredName.encode().startsWith("v3:"))
        val store = PendingJournalStore(MemoryStorage())
        assertTrue(store.write(PendingJournalRecord("restore-label", baseline.encode(), restoredName.encode(), null)))
        val record = (store.read() as PendingJournalRead.Found).record
        assertEquals(PendingRuleRecovery.Action.REPLAY,
            PendingRuleRecovery.decide(record.baseline, record.target, record.inFlight, baseline.encode(), true))
        val committed = RuleConfig.decode(record.target)
        assertEquals(setOf(B), committed.hiddenComponents)
        assertEquals(listOf(C, A, B), committed.orderedComponents)
        assertTrue(committed.aliases.isEmpty())
        assertTrue(settlePendingRuleWrite(store.read(), committed.encode(), true, true, false, store::write, store::clear))
        assertEquals(PendingJournalRead.Missing, store.read())
    }

    companion object {
        private const val A = "p/p.Alpha"
        private const val B = "p/p.Beta"
        private const val C = "p/p.Gamma"
    }
}
