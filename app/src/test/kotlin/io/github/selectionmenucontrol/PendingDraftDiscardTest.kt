package io.github.selectionmenucontrol

import org.junit.Assert.*
import org.junit.Test

class PendingDraftDiscardTest {
    private val original = RuleConfig(emptySet(), emptyList()).encode()
    private val draft = RuleConfig(setOf("p/p.Hidden"), emptyList()).encode()
    private val record = PendingJournalRecord("pending", original, draft, draft)

    private class Storage : PendingJournalStorage {
        val memory = mutableMapOf<String, String>()
        val disk = mutableMapOf<String, String>()
        var failures = 0
        var commits = 0
        override fun readString(key: String) = memory[key]
        override fun commit(values: Map<String, String?>): Boolean {
            commits++
            values.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
            if (failures > 0) { failures--; return false }
            disk.clear(); disk.putAll(memory)
            return true
        }
    }

    @Test fun missingBaselineCanBeDiscardedOnlyAfterTheFenceAndARealClearReceipt() {
        val effects = mutableListOf<String>()
        val result = discardPendingDraft(
            readGlobal = { effects.add("read"); DiscardGlobalValue(original, true) },
            readJournal = { effects.add("journal"); PendingJournalRead.Missing },
            verifyStable = { value -> effects.add("fence"); assertEquals(original, value); true },
            clearJournal = { expected -> effects.add("clear"); assertEquals(PendingJournalRead.Missing, expected); true },
        )
        assertEquals(DraftDiscardResult.Complete(original), result)
        assertEquals(listOf("read", "journal", "fence", "read", "clear", "read"), effects)
    }

    @Test fun unreadableGlobalNeverCallsTheFenceOrClearsTheJournal() {
        assertEquals(DraftDiscardResult.Failed, discardPendingDraft(
            readGlobal = { DiscardGlobalValue(null, false) },
            readJournal = { fail("Must not read pending after a bad Global read"); PendingJournalRead.Missing },
            verifyStable = { fail("Must not fence an unreadable Global"); true },
            clearJournal = { fail("Must not clear an unreadable Global"); true },
        ))
    }

    @Test fun unreadableOrMalformedJournalNeverCallsTheFenceOrClearsIt() {
        assertEquals(DraftDiscardResult.Failed, discardPendingDraft(
            readGlobal = { DiscardGlobalValue(original, true) },
            readJournal = { PendingJournalRead.Failed },
            verifyStable = { fail("Must not fence an unreadable journal"); true },
            clearJournal = { fail("Must not delete an unreadable journal"); true },
        ))
    }

    @Test fun unsuccessfulFenceKeepsThePendingRecord() {
        assertEquals(DraftDiscardResult.Failed, discardPendingDraft(
            readGlobal = { DiscardGlobalValue(original, true) },
            readJournal = { PendingJournalRead.Found(record) },
            verifyStable = { false },
            clearJournal = { fail("A pending remote writer must keep its journal"); true },
        ))
    }

    @Test fun changedOrUnreadableGlobalAfterTheFenceDoesNotClearTheRecord() {
        for (after in listOf(DiscardGlobalValue(draft, true), DiscardGlobalValue(null, false))) {
            var reads = 0
            assertEquals(DraftDiscardResult.Failed, discardPendingDraft(
                readGlobal = { if (reads++ == 0) DiscardGlobalValue(original, true) else after },
                readJournal = { PendingJournalRead.Found(record) },
                verifyStable = { true },
                clearJournal = { fail("Global no longer agrees with the fence"); true },
            ))
        }
    }

    @Test fun failedClearReceiptNeverReportsDiscardComplete() {
        assertEquals(DraftDiscardResult.Failed, discardPendingDraft(
            readGlobal = { DiscardGlobalValue(original, true) },
            readJournal = { PendingJournalRead.Found(record) },
            verifyStable = { true },
            clearJournal = { false },
        ))
    }

    @Test fun externalChangeDuringCleanupIsReadBackInsteadOfReplayingTheDraft() {
        var cleared = false
        assertEquals(DraftDiscardResult.Complete(draft), discardPendingDraft(
            readGlobal = { DiscardGlobalValue(if (cleared) draft else original, true) },
            readJournal = { PendingJournalRead.Found(record) },
            verifyStable = { value -> assertEquals(original, value); true },
            clearJournal = { cleared = true; true },
        ))
    }

    @Test fun unreadableFinalReadKeepsTheDiscardUnresolved() {
        var cleared = false
        assertEquals(DraftDiscardResult.Failed, discardPendingDraft(
            readGlobal = { DiscardGlobalValue(original, !cleared) },
            readJournal = { PendingJournalRead.Missing },
            verifyStable = { true },
            clearJournal = { cleared = true; true },
        ))
    }

    @Test fun onlyPendingKeysAreRemovedAndTheBackupIsPreserved() {
        val storage = Storage()
        storage.memory["backup"] = "keep"
        val store = PendingJournalStore(storage)
        assertTrue(store.write(record))
        assertTrue(discardPendingJournal(storage, PendingJournalRead.Found(record)))
        assertEquals(PendingJournalRead.Missing, store.read())
        assertEquals(mapOf("backup" to "keep"), storage.disk)
    }

    @Test fun failedCommitCanRemoveMemoryButMustBeRetriedBeforeReportingSuccess() {
        val storage = Storage()
        val store = PendingJournalStore(storage)
        assertTrue(store.write(record))
        storage.failures = 2
        assertFalse(discardPendingJournal(storage, PendingJournalRead.Found(record)))
        assertEquals(PendingJournalRead.Missing, store.read())
        assertEquals(record.id, storage.disk["pending_id"])
        assertTrue(discardPendingJournal(storage, PendingJournalRead.Missing))
        assertTrue(storage.disk.isEmpty())
        assertEquals(4, storage.commits)
    }

    @Test fun aNewerRecordOrReadFailureCannotBeClearedByAnOldDecision() {
        val storage = Storage()
        val store = PendingJournalStore(storage)
        val newer = record.copy(id = "newer")
        assertTrue(store.write(newer))
        assertFalse(discardPendingJournal(storage, PendingJournalRead.Found(record)))
        assertFalse(discardPendingJournal(storage, PendingJournalRead.Failed))
        assertEquals(1, storage.commits)
        assertEquals(PendingJournalRead.Found(newer), store.read())
    }
}
