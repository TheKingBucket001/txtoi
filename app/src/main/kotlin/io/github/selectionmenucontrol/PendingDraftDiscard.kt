package io.github.selectionmenucontrol

internal data class DiscardGlobalValue(val value: String?, val valid: Boolean)

internal sealed interface DraftDiscardResult {
    data class Complete(val current: String?) : DraftDiscardResult
    data object Failed : DraftDiscardResult
}

/** Called only after the latest stage has finished, while edits and journal writers are excluded. */
internal fun discardPendingDraft(
    readGlobal: () -> DiscardGlobalValue,
    readJournal: () -> PendingJournalRead,
    verifyStable: (String?) -> Boolean,
    clearJournal: (PendingJournalRead) -> Boolean,
): DraftDiscardResult {
    val current = readGlobal()
    if (!current.valid) return DraftDiscardResult.Failed
    val journal = readJournal()
    if (journal === PendingJournalRead.Failed) return DraftDiscardResult.Failed
    if (!verifyStable(current.value)) return DraftDiscardResult.Failed
    val stable = readGlobal()
    if (!stable.valid || stable.value != current.value) return DraftDiscardResult.Failed
    if (!clearJournal(journal)) return DraftDiscardResult.Failed
    // Reflect a valid external change made during cleanup rather than restoring a private draft.
    val actual = readGlobal()
    return if (actual.valid) DraftDiscardResult.Complete(actual.value) else DraftDiscardResult.Failed
}

/** Even Missing memory requires a disk receipt: an earlier false commit may have removed its keys. */
internal fun discardPendingJournal(storage: PendingJournalStorage, expected: PendingJournalRead): Boolean {
    if (expected === PendingJournalRead.Failed || readPendingJournal(storage::readString) != expected) return false
    val removals = mapOf<String, String?>(
        "pending_id" to null, "pending_baseline" to null,
        "pending_target" to null, "pending_inflight" to null,
    )
    repeat(2) {
        try { if (storage.commit(removals)) return true } catch (_: Exception) { }
    }
    return false
}
