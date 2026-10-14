package io.github.selectionmenucontrol

internal data class PendingJournalRecord(
    val id: String,
    val baseline: String?,
    val target: String,
    val inFlight: String?,
)

internal sealed interface PendingJournalRead {
    data object Missing : PendingJournalRead
    data object Failed : PendingJournalRead
    data class Found(val record: PendingJournalRecord) : PendingJournalRead
}

/** A missing durable journal must not replace the first remembered baseline with a later draft. */
internal fun nextPendingJournalRecord(
    id: String,
    previous: PendingJournalRecord?,
    remembered: PendingJournalRecord?,
    newBaseline: String?,
    target: String,
): PendingJournalRecord {
    val source = previous ?: remembered
    return PendingJournalRecord(id, if (source != null) source.baseline else newBaseline, target, source?.inFlight)
}

/** Preserve the original baseline when a failed durable stage still has a newer memory draft. */
internal fun pendingRecoveryRecord(
    read: PendingJournalRead,
    staged: PendingJournalRecord?,
    acceptedTarget: String?,
): PendingJournalRead {
    if (read === PendingJournalRead.Failed || acceptedTarget == null) return read
    val source = (read as? PendingJournalRead.Found)?.record ?: staged ?: return PendingJournalRead.Failed
    return PendingJournalRead.Found(source.copy(target = acceptedTarget))
}

/** A read failure or partial record cannot prove that an uncertain writer has finished. */
internal fun readPendingJournal(readString: (String) -> String?): PendingJournalRead = try {
    val id = readString("pending_id")
    val baseline = readString("pending_baseline")
    val target = readString("pending_target")
    val inFlight = readString("pending_inflight")
    if (id == null && baseline == null && target == null && inFlight == null) {
        PendingJournalRead.Missing
    } else {
        require(!id.isNullOrEmpty() && id.length <= 64 && target != null)
        RuleConfig.decode(target)
        RuleConfig.decode(baseline)
        RuleConfig.decode(inFlight)
        PendingJournalRead.Found(PendingJournalRecord(id, baseline, target, inFlight))
    }
} catch (_: Exception) {
    PendingJournalRead.Failed
}
