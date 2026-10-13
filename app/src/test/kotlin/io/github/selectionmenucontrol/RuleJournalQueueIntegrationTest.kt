package io.github.selectionmenucontrol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RuleJournalQueueIntegrationTest {
    private class MemoryStore : PendingJournalStorage {
        val values = mutableMapOf<String, String>()
        override fun readString(key: String) = values[key]
        override fun commit(values: Map<String, String?>): Boolean {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }

    private fun repeatedContents(firstVerified: Boolean) = runBlocking {
        withTimeout(3_000) {
            val baseline = RuleConfig(emptySet(), emptyList())
            val first = RuleConfig(setOf("p/p.Hidden"), emptyList())
            val middle = RuleConfig(emptySet(), emptyList())
            val last = RuleConfig(setOf("p/p.Hidden"), emptyList())
            assertNotSame(first, last)
            assertEquals(first.encode(), last.encode())
            val store = PendingJournalStore(MemoryStore())
            var global = baseline.encode()
            var accepted: RuleConfig? = null
            var uncertain = false
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<RuleConfig>()
            val queue = RuleSaveQueue(this, persist = { next: RuleConfig ->
                val journal = store.read() as? PendingJournalRead.Found
                if (journal == null) { uncertain = true; false } else {
                    assertTrue(store.write(journal.record.copy(inFlight = next.encode())))
                    writes.add(next)
                    if (next === first) release.await()
                    val verified = next !== first || firstVerified
                    if (verified) global = next.encode()
                    val settled = settlePendingRuleWrite(store.read(), next.encode(), accepted === next,
                        verified, false, store::write, store::clear)
                    uncertain = !settled
                    if ((verified || !uncertain) && accepted === next) accepted = null
                    verified && settled
                }
            }, read = { RuleConfig.decode(global) }, keepDraftOnFailure = { uncertain })
            queue.initialize(baseline)
            fun submit(value: RuleConfig) {
                accepted = value
                val previous = (store.read() as? PendingJournalRead.Found)?.record
                assertTrue(store.write(PendingJournalRecord("edit-" + value.hashCode(),
                    previous?.baseline ?: global, value.encode(), previous?.inFlight)))
                queue.submit(value)
            }
            submit(first)
            submit(middle)
            submit(last)
            release.complete(Unit)
            queue.state.filterNotNull().first { !it.pending }
            assertEquals(listOf(first, middle, last), writes)
            assertEquals(last.encode(), global)
            assertSame(last, queue.state.value!!.value)
            assertFalse(queue.state.value!!.verificationPending)
            assertEquals(1, queue.state.value!!.successVersion)
            assertEquals(PendingJournalRead.Missing, store.read())
        }
    }

    @Test fun earlierSuccessWithRepeatedContentsCannotClearTheLatestJournal() = repeatedContents(true)
    @Test fun earlierDefiniteFailureWithRepeatedContentsCannotClearTheLatestJournal() = repeatedContents(false)

    @Test fun latestRecoveredEditCanStillRetireItsOwnJournal() {
        val store = PendingJournalStore(MemoryStore())
        val encoded = RuleConfig(emptySet(), emptyList()).encode()
        assertTrue(store.write(PendingJournalRecord("replay", null, encoded, encoded)))
        assertTrue(settlePendingRuleWrite(store.read(), encoded, true, true, false, store::write, store::clear))
        assertEquals(PendingJournalRead.Missing, store.read())
    }
}
