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

    @Test fun twoAcceptedDraftsBeforeAnyDurableStageKeepTheOriginalBaseline() {
        val original = empty
        val firstDraft = RuleConfig(setOf("p/p.First"), emptyList()).encode()
        val latestDraft = RuleConfig(setOf("p/p.Latest"), emptyList()).encode()
        // Both Global reads failed while the journal was Missing. The second UI snapshot is a draft.
        val first = nextPendingJournalRecord("first", null, null, original, firstDraft)
        val latest = nextPendingJournalRecord("latest", null, first, firstDraft, latestDraft)
        val recovered = (pendingRecoveryRecord(PendingJournalRead.Missing, latest, latestDraft)
            as PendingJournalRead.Found).record
        assertEquals(original, recovered.baseline)
        assertEquals(latestDraft, recovered.target)
        assertEquals(PendingRuleRecovery.Action.REPLAY,
            PendingRuleRecovery.decide(recovered.baseline, recovered.target, recovered.inFlight, original, true))
        // Counterexample for the previous stage logic: rebasing to the first draft loses the latest edit.
        assertEquals(PendingRuleRecovery.Action.CONFLICT,
            PendingRuleRecovery.decide(firstDraft, latestDraft, null, original, true))
    }

    @Test fun aGenuinelyMissingOriginalGlobalRemainsNullAcrossAcceptedDrafts() {
        val first = nextPendingJournalRecord("first", null, null, null, "first-draft")
        val latest = nextPendingJournalRecord("latest", null, first, "first-draft", "latest-draft")
        assertEquals(null, latest.baseline)
        assertEquals(PendingRuleRecovery.Action.REPLAY,
            PendingRuleRecovery.decide(latest.baseline, latest.target, latest.inFlight, null, true))
    }

    @Test fun durableRecordWinsOverRememberedBaselineAndInFlight() {
        val durable = PendingJournalRecord("disk", "disk-baseline", "disk-target", "disk-in-flight")
        val remembered = PendingJournalRecord("memory", "memory-baseline", "memory-target", "memory-in-flight")
        assertEquals(PendingJournalRecord("latest", "disk-baseline", "latest-draft", "disk-in-flight"),
            nextPendingJournalRecord("latest", durable, remembered, "page-snapshot", "latest-draft"))
    }

    @Test fun rememberedInFlightSurvivesAMissingDurableRecord() {
        val remembered = PendingJournalRecord("memory", "original", "first-draft", "in-flight")
        assertEquals(PendingJournalRecord("latest", "original", "latest-draft", "in-flight"),
            nextPendingJournalRecord("latest", null, remembered, "first-draft", "latest-draft"))
    }

    @Test fun retiredMemoryCannotRestoreAnOldTransaction() {
        assertEquals(PendingJournalRecord("new", "new-global", "new-draft", null),
            nextPendingJournalRecord("new", null, null, "new-global", "new-draft"))
        assertEquals(PendingJournalRead.Missing, pendingRecoveryRecord(PendingJournalRead.Missing, null, null))
    }
}
