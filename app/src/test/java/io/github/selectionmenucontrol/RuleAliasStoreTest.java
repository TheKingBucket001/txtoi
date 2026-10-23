package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class RuleAliasStoreTest {
    private static final String A = "p/p.Alpha";
    private static final String B = "p/p.Beta";

    @Test public void snapshotsRetainAliasesThroughStoreDecoding() {
        SystemRuleStore.Snapshot saved = new SystemRuleStore.Snapshot(Collections.singleton(B),
                Arrays.asList(B, A), Map.of(A, "搜索", B, "翻译"));
        SystemRuleStore.Snapshot decoded = SystemRuleStore.decode(saved.encode());
        assertTrue(decoded.valid);
        assertEquals(SystemRuleStore.ReadStatus.VALID, decoded.status);
        assertEquals(saved.hiddenComponents, decoded.hiddenComponents);
        assertEquals(saved.orderedComponents, decoded.orderedComponents);
        assertEquals(saved.aliases, decoded.aliases);
        assertEquals(saved.encode(), decoded.encode());
    }

    @Test public void malformedV4CannotReplaceLastValidAliasesWithAnEmptySnapshot() {
        SystemRuleStore.Snapshot saved = new SystemRuleStore.Snapshot(Collections.emptySet(),
                Collections.emptyList(), Map.of(A, "搜索"));
        LastValidRules<SystemRuleStore.Snapshot> fallback = new LastValidRules<>(30000);
        fallback.accept(saved, saved.valid, 100);
        SystemRuleStore.Snapshot broken = SystemRuleStore.decode("v4:::!broken!");
        assertFalse(broken.valid);
        assertEquals(SystemRuleStore.ReadStatus.MALFORMED, broken.status);
        assertSame(saved, fallback.accept(broken, broken.valid, 110));
        assertEquals(Map.of(A, "搜索"), fallback.accept(broken, broken.valid, 120).aliases);
    }

    @Test public void intentionalAliasRemovalIsAValidV3RecordAndDoesNotReviveOldValues() {
        SystemRuleStore.Snapshot saved = new SystemRuleStore.Snapshot(Collections.emptySet(),
                Collections.emptyList(), Map.of(A, "搜索"));
        LastValidRules<SystemRuleStore.Snapshot> fallback = new LastValidRules<>(30000);
        fallback.accept(saved, saved.valid, 100);
        SystemRuleStore.Snapshot cleared = SystemRuleStore.decode("v3::");
        assertSame(cleared, fallback.accept(cleared, cleared.valid, 110));
        SystemRuleStore.Snapshot unavailable = SystemRuleStore.Snapshot.unavailable(SystemRuleStore.ReadStatus.UNREADABLE);
        assertSame(cleared, fallback.accept(unavailable, unavailable.valid, 120));
        assertTrue(cleared.aliases.isEmpty());
        assertTrue(SystemRuleStore.decode(null).aliases.isEmpty());
    }
}
