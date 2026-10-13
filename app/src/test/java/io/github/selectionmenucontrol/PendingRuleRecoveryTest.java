package io.github.selectionmenucontrol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PendingRuleRecoveryTest {
    @Test public void pendingIsOnlyReplayedAgainstItsOwnBaseline() {
        assertEquals(PendingRuleRecovery.Action.REPLAY, PendingRuleRecovery.decide("base", "target", "base", true));
        assertEquals(PendingRuleRecovery.Action.COMPLETE, PendingRuleRecovery.decide("base", "target", "target", true));
        assertEquals(PendingRuleRecovery.Action.CONFLICT, PendingRuleRecovery.decide("base", "target", "other-user", true));
        assertEquals(PendingRuleRecovery.Action.WAIT, PendingRuleRecovery.decide(null, "target", null, false));
        assertEquals(PendingRuleRecovery.Action.REPLAY, PendingRuleRecovery.decide(null, "target", null, true));
    }
    @Test public void crashBetweenWriteAndReceiptCanRecoverTheNewestTarget() {
        assertEquals(PendingRuleRecovery.Action.REPLAY,
                PendingRuleRecovery.decide("base", "newest", "intermediate", "intermediate", true));
        assertEquals(PendingRuleRecovery.Action.CONFLICT,
                PendingRuleRecovery.decide("base", "newest", "intermediate", "unrelated", true));
        assertEquals(PendingRuleRecovery.Action.WAIT,
                PendingRuleRecovery.decide("base", "newest", "intermediate", "intermediate", false));
    }
}
