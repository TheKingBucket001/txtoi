package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class RuleStoreOptimizationTest {
    @Test public void missingAndExplicitlyClearedRecordsAreDistinctButBothValid() {
        SystemRuleStore.Snapshot missing = SystemRuleStore.decode(null);
        SystemRuleStore.Snapshot cleared = SystemRuleStore.decode("v3::");
        assertTrue(missing.valid);
        assertTrue(cleared.valid);
        assertEquals(SystemRuleStore.ReadStatus.MISSING, missing.status);
        assertEquals(SystemRuleStore.ReadStatus.VALID, cleared.status);
        assertTrue(missing.hiddenComponents.isEmpty());
        assertTrue(cleared.orderedComponents.isEmpty());
    }

    @Test public void malformedIsNotSilentlyConvertedToAnEmptyValidRecord() {
        for (String value : new String[]{"", "v3:bad!:", "v4::", "v3:::"}) {
            SystemRuleStore.Snapshot broken = SystemRuleStore.decode(value);
            assertFalse(broken.valid);
            assertEquals(SystemRuleStore.ReadStatus.MALFORMED, broken.status);
        }
    }

    @Test public void failureUsesSavedSnapshotButExplicitClearCannotReviveOldRules() {
        LastValidRules<SystemRuleStore.Snapshot> fallback = new LastValidRules<>(30000);
        SystemRuleStore.Snapshot saved = new SystemRuleStore.Snapshot(Collections.singleton("p/p.Hidden"));
        SystemRuleStore.Snapshot unreadable = SystemRuleStore.Snapshot.unavailable(SystemRuleStore.ReadStatus.UNREADABLE);
        fallback.accept(saved, saved.valid, 100);
        assertSame(saved, fallback.accept(unreadable, unreadable.valid, 110));
        SystemRuleStore.Snapshot malformed = SystemRuleStore.decode("v3:broken!:");
        assertSame(saved, fallback.accept(malformed, malformed.valid, 120));
        SystemRuleStore.Snapshot cleared = SystemRuleStore.decode("v3::");
        assertSame(cleared, fallback.accept(cleared, cleared.valid, 130));
        assertSame(cleared, fallback.accept(unreadable, unreadable.valid, 140));
        assertTrue(cleared.hiddenComponents.isEmpty());
    }
}
