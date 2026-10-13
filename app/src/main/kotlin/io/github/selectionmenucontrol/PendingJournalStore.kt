package io.github.selectionmenucontrol

internal interface PendingJournalStorage {
    fun readString(key: String): String?
    fun commit(values: Map<String, String?>): Boolean
}

/** A false commit can change memory while leaving disk unchanged; it is never a durable receipt. */
internal class PendingJournalStore(private val storage: PendingJournalStorage) {
    fun read(): PendingJournalRead = readPendingJournal(storage::readString)

    fun write(record: PendingJournalRecord): Boolean = commit(mapOf(
        "pending_id" to record.id,
        "pending_baseline" to record.baseline,
        "pending_target" to record.target,
        "pending_inflight" to record.inFlight,
    ))

    fun clear(expectedId: String): Boolean = try {
        if (storage.readString("pending_id") != expectedId) false
        else commit(mapOf(
            "pending_id" to null, "pending_baseline" to null,
            "pending_target" to null, "pending_inflight" to null,
        ))
    } catch (_: Exception) { false }

    private fun commit(values: Map<String, String?>): Boolean {
        repeat(2) {
            try { if (storage.commit(values)) return true } catch (_: Exception) { }
        }
        return false
    }
}

/** Retry a failed cleanup even when an earlier false commit already removed the memory keys. */
internal fun finishPendingJournalCleanup(store: PendingJournalStore, record: PendingJournalRecord): Boolean =
    store.write(record) && store.clear(record.id)
