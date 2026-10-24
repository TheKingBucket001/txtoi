package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ProcessTextQueryRulesTest {
    private static final String A = "com.example.alpha/com.example.alpha.ProcessText";
    private static final String B = "com.example.beta/com.example.beta.ProcessText";
    private static final String C = "com.example.gamma/com.example.gamma.ProcessText";
    private static final String D = "com.example.delta/com.example.delta.ProcessText";

    @Test
    public void absentSpecificIntentsDoNotInspectSpecificIndices() {
        List<Entry> resolved = Arrays.asList(entry(A, 0), entry(B, -1));

        assertEquals(0, ProcessTextQueryRules.specificPrefixLength(resolved, false, value -> {
            throw new AssertionError("No specific intents were supplied");
        }));
    }

    @Test
    public void unresolvedSpecificIntentsDoNotProtectOrdinaryResults() {
        List<Entry> resolved = Arrays.asList(entry(A, -1), entry(B, -1));
        int count = prefix(resolved);

        assertEquals(0, count);
        assertEquals(Collections.singletonList(resolved.get(1)),
                apply(resolved, count, rules(Collections.singletonList(A), Collections.emptyList())));
    }

    @Test
    public void onlyTheContiguousResolvedSpecificPrefixIsProtected() {
        List<Entry> resolved = Arrays.asList(entry(A, 2), entry(B, 5), entry(C, -1), entry(D, 7));

        assertEquals(2, prefix(resolved));
    }

    @Test
    public void allSpecificResultsRemainInOrderAndKeepTheirOriginalObjects() {
        List<Entry> resolved = Arrays.asList(entry(A, 0), entry(B, 2));
        RuleConfig rules = rules(Arrays.asList(A, B), Arrays.asList(B, A), A, "Renamed");

        List<Entry> result = apply(resolved, prefix(resolved), rules);

        assertEquals(resolved, result);
        assertNotSame(resolved, result);
        assertSame(resolved.get(0), result.get(0));
        assertSame(resolved.get(1), result.get(1));
    }

    @Test
    public void hiddenSpecificComponentDoesNotProtectItsOrdinaryDuplicate() {
        Entry explicitA = entry(A, 0);
        Entry ordinaryA = entry(A, -1);
        Entry ordinaryB = entry(B, -1);
        List<Entry> resolved = Arrays.asList(explicitA, ordinaryA, ordinaryB);

        List<Entry> result = apply(resolved, prefix(resolved),
                rules(Collections.singletonList(A), Collections.emptyList(), A, "Alias"));

        assertEquals(Arrays.asList(explicitA, ordinaryB), result);
        assertSame(explicitA, result.get(0));
        assertEquals("Original", explicitA.label);
        assertEquals(Arrays.asList(explicitA, ordinaryA, ordinaryB), resolved);
    }

    @Test
    public void aliasOnlyChangesTheOrdinaryDuplicateAndUsesACopy() {
        Entry explicitA = entry(A, 0);
        Entry ordinaryA = entry(A, -1);
        List<Entry> resolved = Arrays.asList(explicitA, ordinaryA);

        List<Entry> result = apply(resolved, 1,
                rules(Collections.emptyList(), Collections.emptyList(), A, "Alias"));

        assertSame(explicitA, result.get(0));
        assertNotSame(ordinaryA, result.get(1));
        assertEquals(A, result.get(1).component);
        assertEquals(-1, result.get(1).specificIndex);
        assertEquals("Alias", result.get(1).label);
        assertEquals("Original", explicitA.label);
        assertEquals("Original", ordinaryA.label);
        assertEquals(Arrays.asList(explicitA, ordinaryA), resolved);
    }

    @Test
    public void ordinaryResultsApplyHidingOrderingAndAliasesTogether() {
        Entry explicitD = entry(D, 0);
        Entry a = entry(A, -1);
        Entry b = entry(B, -1);
        Entry c = entry(C, -1);
        List<Entry> resolved = Collections.unmodifiableList(Arrays.asList(explicitD, a, b, c));
        RuleConfig rules = rules(Collections.singletonList(B), Arrays.asList(C, A), C, "Custom");

        List<Entry> result = apply(resolved, 1, rules);

        assertEquals(3, result.size());
        assertSame(explicitD, result.get(0));
        assertEquals(C, result.get(1).component);
        assertEquals("Custom", result.get(1).label);
        assertNotSame(c, result.get(1));
        assertSame(a, result.get(2));
        assertEquals(Arrays.asList(explicitD, a, b, c), resolved);
        assertEquals("Original", c.label);
    }

    @Test
    public void fixedSlotsArePreservedWithinTheOrdinarySection() {
        Entry explicitD = entry(D, 0);
        Entry a = entry(A, -1);
        Entry fixed = new Entry(D, -1, true, "Original");
        Entry b = entry(B, -1);
        Entry c = entry(C, -1);
        List<Entry> resolved = Arrays.asList(explicitD, a, fixed, b, c);

        List<Entry> result = apply(resolved, 1,
                rules(Collections.emptyList(), Arrays.asList(C, B, A, D)));

        assertEquals(Arrays.asList(explicitD, c, fixed, b, a), result);
        assertSame(fixed, result.get(2));
        assertEquals(Arrays.asList(explicitD, a, fixed, b, c), resolved);
    }

    @Test
    public void emptyRulesKeepTheInputOrderAndObjects() {
        List<Entry> resolved = Arrays.asList(entry(D, 0), entry(C, -1), entry(A, -1));

        List<Entry> result = apply(resolved, 1, RuleConfig.decode(null));

        assertEquals(resolved, result);
        assertNotSame(resolved, result);
        for (int index = 0; index < resolved.size(); index++) {
            assertSame(resolved.get(index), result.get(index));
        }
    }

    @Test
    public void emptyResultsHaveNoSpecificPrefixOrRuleEffects() {
        List<Entry> resolved = Collections.emptyList();

        assertEquals(0, prefix(resolved));
        assertTrue(apply(resolved, 0,
                rules(Collections.singletonList(A), Collections.singletonList(B), C, "Alias")).isEmpty());
    }

    @Test
    public void invalidSpecificCountsAreRejected() {
        List<Entry> resolved = Collections.singletonList(entry(A, -1));
        RuleConfig rules = RuleConfig.decode(null);

        assertThrows(IllegalArgumentException.class, () -> apply(resolved, -1, rules));
        assertThrows(IllegalArgumentException.class, () -> apply(resolved, 2, rules));
    }

    @Test
    public void aliasCallbackIsOnlyInvokedForMatchingOrdinaryEntries() {
        Entry explicitA = entry(A, 0);
        Entry b = entry(B, -1);
        List<Entry> resolved = Arrays.asList(explicitA, b);
        RuleConfig rules = rules(Collections.emptyList(), Collections.emptyList(), A, "Alias");

        List<Entry> result = ProcessTextQueryRules.apply(resolved, 1, rules,
                value -> value.component, value -> value.fixed, (value, alias) -> {
                    throw new AssertionError("No ordinary entry has an alias");
                });

        assertEquals(resolved, result);
    }

    private static int prefix(List<Entry> resolved) {
        return ProcessTextQueryRules.specificPrefixLength(resolved, true, value -> value.specificIndex);
    }

    private static List<Entry> apply(List<Entry> resolved, int count, RuleConfig rules) {
        return ProcessTextQueryRules.apply(resolved, count, rules, value -> value.component,
                value -> value.fixed,
                (value, alias) -> new Entry(value.component, value.specificIndex, value.fixed, alias));
    }

    private static RuleConfig rules(List<String> hidden, List<String> order, String... aliases) {
        Map<String, String> named = new HashMap<>();
        for (int index = 0; index < aliases.length; index += 2) {
            named.put(aliases[index], aliases[index + 1]);
        }
        return new RuleConfig(new HashSet<>(hidden), order, named);
    }

    private static Entry entry(String component, int specificIndex) {
        return new Entry(component, specificIndex, false, "Original");
    }

    private static final class Entry {
        final String component;
        final int specificIndex;
        final boolean fixed;
        final String label;

        Entry(String component, int specificIndex, boolean fixed, String label) {
            this.component = component;
            this.specificIndex = specificIndex;
            this.fixed = fixed;
            this.label = label;
        }
    }
}
