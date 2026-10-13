package io.github.selectionmenucontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingJournalRecordTest {
    private val empty = RuleConfig(emptySet(), emptyList()).encode()

    @Test fun absentKeysAreTheOnlyMissingRecord() {
        assertEquals(PendingJournalRead.Missing, readPendingJournal { null })
        assertEquals(PendingJournalRead.Failed, readPendingJournal { if (it == "pending_target") empty else null })
    }

    @Test fun unreadableAndMalformedRecordsNeverBecomeMissing() {
        assertEquals(PendingJournalRead.Failed, readPendingJournal { throw IllegalStateException() })
        val values = mapOf("pending_id" to "id", "pending_target" to "invalid")
        assertEquals(PendingJournalRead.Failed, readPendingJournal(values::get))
    }

    @Test fun targetAndInFlightAreValidatedSeparately() {
        val values = mutableMapOf("pending_id" to "id", "pending_target" to empty)
        assertTrue(readPendingJournal(values::get) is PendingJournalRead.Found)
        values["pending_inflight"] = "invalid"
        assertEquals(PendingJournalRead.Failed, readPendingJournal(values::get))
    }

    @Test fun completeRecordPreservesNullBaselineAndInFlight() {
        val values = mapOf("pending_id" to "id", "pending_target" to empty)
        assertEquals(PendingJournalRead.Found(PendingJournalRecord("id", null, empty, null)),
            readPendingJournal(values::get))
    }

    @Test fun failedLatestStageUsesTheAcceptedDraftWithoutChangingItsBaseline() {
        val older = PendingJournalRecord("id", "original", "older", "in-flight")
        val resolved = pendingRecoveryRecord(PendingJournalRead.Found(older), null, "latest") as PendingJournalRead.Found
        assertEquals("latest", resolved.record.target)
        assertEquals("original", resolved.record.baseline)
        assertEquals("in-flight", resolved.record.inFlight)
        assertEquals(PendingRuleRecovery.Action.CONFLICT,
            PendingRuleRecovery.decide(resolved.record.baseline, resolved.record.target,
                resolved.record.inFlight, "external", true))
    }

    @Test fun missingCommitUsesOnlyItsRememberedBaselineAndReadFaultsNeverReplay() {
        val remembered = PendingJournalRecord("id", null, "target", null)
        assertEquals(PendingJournalRead.Found(remembered),
            pendingRecoveryRecord(PendingJournalRead.Missing, remembered, "target"))
        assertEquals(PendingJournalRead.Failed, pendingRecoveryRecord(PendingJournalRead.Missing, null, "target"))
        assertEquals(PendingJournalRead.Failed, pendingRecoveryRecord(PendingJournalRead.Failed, remembered, "target"))
        assertEquals(PendingJournalRead.Missing, pendingRecoveryRecord(PendingJournalRead.Missing, remembered, null))
    }
}
