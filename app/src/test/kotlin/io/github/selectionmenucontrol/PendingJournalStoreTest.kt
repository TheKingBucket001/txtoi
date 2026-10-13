package io.github.selectionmenucontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingJournalStoreTest {
    private val empty = RuleConfig(emptySet(), emptyList()).encode()
    private val record get() = PendingJournalRecord("id", null, empty, empty)

    private class Preferences : PendingJournalStorage {
        val memory = mutableMapOf<String, String>()
        val disk = mutableMapOf<String, String>()
        var failures = 0
        var commits = 0
        override fun readString(key: String) = memory[key]
        override fun commit(values: Map<String, String?>): Boolean {
            commits++
            values.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
            if (failures > 0) { failures--; return false }
            disk.clear()
            disk.putAll(memory)
            return true
        }
        fun restart() { memory.clear(); memory.putAll(disk) }
    }

    @Test fun failedCleanupCannotPretendTheDiskRecordHasDisappeared() {
        val preferences = Preferences()
        val store = PendingJournalStore(preferences)
        assertTrue(store.write(record))
        preferences.failures = 2
        assertFalse(store.clear(record.id))
        assertEquals(PendingJournalRead.Missing, store.read())
        preferences.restart()
        assertEquals(PendingJournalRead.Found(record), store.read())
    }

    @Test fun restagingAfterFailedCleanupKeepsTheOriginalTransactionThenCanFinish() {
        val preferences = Preferences()
        val store = PendingJournalStore(preferences)
        assertTrue(store.write(record))
        preferences.failures = 2
        assertFalse(store.clear(record.id))
        assertTrue(store.write(record))
        assertTrue(store.clear(record.id))
        preferences.restart()
        assertEquals(PendingJournalRead.Missing, store.read())
    }

    @Test fun aFalseFirstCommitIsRetriedWithoutRecheckingMutatedMemory() {
        val preferences = Preferences()
        val store = PendingJournalStore(preferences)
        assertTrue(store.write(record))
        preferences.failures = 1
        assertTrue(store.clear(record.id))
        assertEquals(3, preferences.commits)
        preferences.restart()
        assertEquals(PendingJournalRead.Missing, store.read())
    }

    @Test fun anOldReceiptCannotDeleteANewerDurableTarget() {
        val preferences = Preferences()
        val store = PendingJournalStore(preferences)
        val newer = record.copy(id = "newer")
        assertTrue(store.write(newer))
        assertFalse(store.clear("id"))
        assertEquals(1, preferences.commits)
        preferences.restart()
        assertEquals(PendingJournalRead.Found(newer), store.read())
    }

    @Test fun failedWriteIsNotAReceiptEvenThoughMemoryHasTheNewTarget() {
        val preferences = Preferences()
        val store = PendingJournalStore(preferences)
        assertTrue(store.write(record))
        preferences.failures = 2
        val newer = record.copy(id = "newer")
        assertFalse(store.write(newer))
        assertEquals(PendingJournalRead.Found(newer), store.read())
        preferences.restart()
        assertEquals(PendingJournalRead.Found(record), store.read())
    }

    @Test fun aSecondRecoveryRetiresTheRememberedRecordEvenWhenMemorySaysMissing() {
        val preferences = Preferences()
        val store = PendingJournalStore(preferences)
        assertTrue(store.write(record))
        preferences.restart() // Fresh process has a durable record but no accepted UI draft.
        val loaded = (store.read() as PendingJournalRead.Found).record
        preferences.failures = 2
        assertFalse(store.clear(loaded.id))
        assertEquals(PendingJournalRead.Missing, store.read())
        assertTrue(finishPendingJournalCleanup(store, loaded))
        preferences.restart()
        assertEquals(PendingJournalRead.Missing, store.read())
    }
}
