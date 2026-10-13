package io.github.selectionmenucontrol;

import static org.junit.Assert.assertSame;

import org.junit.Test;

public class LastValidRulesOptimizationTest {
    @Test public void failureBeforeAnySuccessfulReadHasNoInventedFallback() {
        LastValidRules<Object> fallback = new LastValidRules<>(30000);
        Object unavailable = new Object();
        assertSame(unavailable, fallback.accept(unavailable, false, 10));
    }

    @Test public void repeatedReadFailuresDoNotExtendTheOriginalRetentionWindow() {
        LastValidRules<Object> fallback = new LastValidRules<>(30000);
        Object saved = new Object();
        Object unavailable = new Object();
        assertSame(saved, fallback.accept(saved, true, 100));
        assertSame(saved, fallback.accept(unavailable, false, 101));
        assertSame(saved, fallback.accept(unavailable, false, 30099));
        assertSame(saved, fallback.accept(unavailable, false, 30100));
        assertSame(unavailable, fallback.accept(unavailable, false, 30101));
        assertSame(unavailable, fallback.accept(unavailable, false, 30200));
    }

    @Test public void aDeliberateClearImmediatelyReplacesPreviousHiddenRules() {
        LastValidRules<Object> fallback = new LastValidRules<>(30000);
        Object hidden = new Object();
        Object cleared = new Object();
        Object unavailable = new Object();
        fallback.accept(hidden, true, 100);
        assertSame(cleared, fallback.accept(cleared, true, 110));
        assertSame(cleared, fallback.accept(unavailable, false, 120));
    }

    @Test public void aFreshSuccessfulReadRenewsFallbackButClockRollbackDoesNot() {
        LastValidRules<Object> fallback = new LastValidRules<>(30000);
        Object first = new Object();
        Object second = new Object();
        Object unavailable = new Object();
        fallback.accept(first, true, 100);
        fallback.accept(second, true, 10000);
        assertSame(second, fallback.accept(unavailable, false, 40000));
        assertSame(unavailable, fallback.accept(unavailable, false, 40001));
        assertSame(unavailable, fallback.accept(unavailable, false, 9999));
    }
}
