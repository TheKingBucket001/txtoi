package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class RuleConfigTest {
    private static final String A = "com.example.alpha/com.example.alpha.ProcessText";
    private static final String B = "com.example.beta/com.example.beta.ProcessText";
    private static final String C = "com.example.gamma/com.example.gamma.ProcessText";
    private static final String D = "com.example.delta/com.example.delta.ProcessText";
    private static final String STALE = "com.example.removed/com.example.removed.ProcessText";

    @Test
    public void legacyV1PreservesHiddenRulesWhenRemovedSwitchWasOff() {
        RuleConfig rules = RuleConfig.decode("v1:0:" + payload(B, A));

        assertEquals(set(A, B), rules.hiddenComponents);
        assertTrue(rules.orderedComponents.isEmpty());
        assertEquals(Collections.singletonList(C), rules.apply(Arrays.asList(A, C, B), value -> value));
        assertEquals(rules.hiddenComponents, RuleConfig.decode(rules.encode()).hiddenComponents);
    }

    @Test
    public void legacyV2PreservesHiddenRulesWithoutCreatingCustomOrder() {
        RuleConfig rules = RuleConfig.decode("v2:" + payload(C, A));

        assertEquals(set(A, C), rules.hiddenComponents);
        assertTrue(rules.orderedComponents.isEmpty());
        assertEquals(Arrays.asList(D, B), rules.apply(Arrays.asList(D, C, B, A), value -> value));
    }

    @Test
    public void absentRulesAndEmptyLegacyRulesUseSystemOrder() {
        for (String encoded : Arrays.asList(null, "v1:1:", "v2:", "v3::")) {
            RuleConfig rules = RuleConfig.decode(encoded);
            assertTrue(rules.hiddenComponents.isEmpty());
            assertTrue(rules.orderedComponents.isEmpty());
            assertEquals(Arrays.asList(C, A, B), rules.apply(Arrays.asList(C, A, B), value -> value));
        }
    }

    @Test
    public void v3RoundTripPreservesCustomOrderRatherThanSortingComponentNames() {
        RuleConfig rules = new RuleConfig(set(B), Arrays.asList(C, A, B));
        RuleConfig restored = RuleConfig.decode(rules.encode());

        assertTrue(rules.encode().startsWith("v3:"));
        assertEquals(set(B), restored.hiddenComponents);
        assertEquals(Arrays.asList(C, A, B), restored.orderedComponents);
        assertEquals(Arrays.asList(C, A), restored.apply(Arrays.asList(A, B, C), value -> value));
    }

    @Test
    public void unicodeActivityNamesSurviveRoundTripAndSorting() {
        String unicode = "com.example.unicode/com.example.unicode.处理文本";
        RuleConfig rules = new RuleConfig(set(A), Arrays.asList(unicode, B, A));
        RuleConfig restored = RuleConfig.decode(rules.encode());

        assertEquals(set(A), restored.hiddenComponents);
        assertEquals(Arrays.asList(unicode, B, A), restored.orderedComponents);
        assertEquals(Arrays.asList(unicode, B), restored.apply(Arrays.asList(A, B, unicode), value -> value));
    }

    @Test
    public void hidingAnItemKeepsItsSavedPositionForLaterRestoration() {
        List<String> order = Arrays.asList(C, B, A);
        RuleConfig hidden = new RuleConfig(set(B), order);
        RuleConfig restored = RuleConfig.decode(new RuleConfig(Collections.emptySet(), order).encode());

        assertEquals(Arrays.asList(C, A), hidden.apply(Arrays.asList(A, B, C), value -> value));
        assertEquals(order, hidden.orderedComponents);
        assertEquals(order, restored.apply(Arrays.asList(A, B, C), value -> value));
    }

    @Test
    public void restoreDefaultOrderPreservesHiddenItemsAndOriginalQueryOrder() {
        RuleConfig custom = new RuleConfig(set(B), Arrays.asList(A, C, B, D));
        RuleConfig restored = RuleConfig.decode(
                new RuleConfig(custom.hiddenComponents, Collections.emptyList()).encode());

        assertEquals(set(B), restored.hiddenComponents);
        assertTrue(restored.orderedComponents.isEmpty());
        assertEquals(Arrays.asList(D, C, A), restored.apply(Arrays.asList(D, B, C, A), value -> value));
    }

    @Test
    public void customOrderWorksEvenWhenNoItemsAreHidden() {
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Arrays.asList(C, B, A));

        assertEquals(Arrays.asList(C, B, A), rules.apply(Arrays.asList(A, B, C), value -> value));
    }

    @Test
    public void sameComponentCanBecomeFixedWhenRuntimeClassificationChanges() {
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Arrays.asList(C, A, B));
        List<String> original = Arrays.asList(B, A, C);

        assertEquals(Arrays.asList(C, A, B), rules.apply(original, value -> value, value -> false));
        assertEquals(Arrays.asList(B, C, A), rules.apply(original, value -> value, B::equals));
        assertEquals(Arrays.asList(C, A, B), rules.apply(original, value -> value));
    }

    @Test
    public void ordinarySortingPreservesRuntimeFixedSlotsAndObjectIdentity() {
        Entry a = new Entry(A);
        Entry b = new Entry(B);
        Entry c = new Entry(C);
        Entry firstFixed = new Entry(D);
        Entry secondFixed = new Entry(STALE);
        List<Entry> original = Arrays.asList(a, firstFixed, b, secondFixed, c);
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Arrays.asList(C, B, A));

        List<Entry> result = rules.apply(original, entry -> entry.component,
                entry -> entry == firstFixed || entry == secondFixed);

        assertEquals(Arrays.asList(c, firstFixed, b, secondFixed, a), result);
        assertSame(firstFixed, result.get(1));
        assertSame(secondFixed, result.get(3));
        assertEquals(Arrays.asList(a, firstFixed, b, secondFixed, c), original);
    }

    @Test
    public void savedRanksCannotMoveRuntimeFixedItemsButHiddenRulesStillApply() {
        RuleConfig rules = RuleConfig.decode(new RuleConfig(set(STALE),
                Arrays.asList(D, STALE, C, A, B)).encode());

        assertEquals(Arrays.asList(C, D, A, B), rules.apply(Arrays.asList(A, D, STALE, B, C),
                value -> value, value -> D.equals(value) || STALE.equals(value)));
        assertEquals(set(STALE), rules.hiddenComponents);
        assertEquals(Arrays.asList(D, STALE, C, A, B), rules.orderedComponents);
    }

    @Test
    public void defaultRestoreRetainsFixedAndOrdinaryHiddenRules() {
        RuleConfig restored = new RuleConfig(set(D, B), Collections.emptyList());

        assertEquals(Arrays.asList(C, A), restored.apply(Arrays.asList(D, C, B, A), value -> value, D::equals));
        assertEquals(set(D, B), restored.hiddenComponents);
    }

    @Test
    public void runtimeClassificationIsPerEntryAndEvaluatedOnceBeforeReordering() {
        Entry firstB = new Entry(B);
        Entry fixedB = new Entry(B);
        Entry a = new Entry(A);
        Entry c = new Entry(C);
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Arrays.asList(C, B, A));
        List<Entry> inspected = new ArrayList<>();

        List<Entry> result = rules.apply(Arrays.asList(a, fixedB, firstB, c), entry -> entry.component,
                entry -> {
                    inspected.add(entry);
                    return entry == fixedB;
                });

        assertEquals(Arrays.asList(c, fixedB, firstB, a), result);
        assertEquals(Arrays.asList(a, fixedB, firstB, c), inspected);
        assertSame(fixedB, result.get(1));
    }

    @Test
    public void newUnspecifiedItemsRemainStableAtTheEnd() {
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Arrays.asList(B, A));

        assertEquals(Arrays.asList(B, A, D, C), rules.apply(Arrays.asList(D, A, C, B), value -> value));
    }

    @Test
    public void duplicateOrderEntriesUseTheirFirstOccurrence() {
        RuleConfig rules = RuleConfig.decode("v3::" + payload(C, A, C, B, A));

        assertEquals(Arrays.asList(C, A, B), rules.orderedComponents);
        assertEquals(Arrays.asList(C, A, B), rules.apply(Arrays.asList(B, A, C), value -> value));
        assertEquals(rules.orderedComponents, RuleConfig.decode(rules.encode()).orderedComponents);
    }

    @Test
    public void staleComponentsStayPersistedWithoutBeingInventedInQueryResults() {
        RuleConfig rules = RuleConfig.decode(new RuleConfig(set(STALE), Arrays.asList(STALE, B, A)).encode());

        assertEquals(set(STALE), rules.hiddenComponents);
        assertEquals(Arrays.asList(STALE, B, A), rules.orderedComponents);
        assertEquals(Arrays.asList(B, A, C), rules.apply(Arrays.asList(A, C, B), value -> value));
    }

    @Test
    public void duplicateResolvedComponentsKeepTheirRelativeIdentityOrder() {
        Entry firstB = new Entry(B);
        Entry secondB = new Entry(B);
        Entry a = new Entry(A);
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Arrays.asList(B, A));

        List<Entry> result = rules.apply(Arrays.asList(a, firstB, secondB), entry -> entry.component);

        assertSame(firstB, result.get(0));
        assertSame(secondB, result.get(1));
        assertSame(a, result.get(2));
    }

    @Test
    public void applyDoesNotModifyTheOriginalListOrCopyEntryObjects() {
        Entry a = new Entry(A);
        Entry b = new Entry(B);
        Entry c = new Entry(C);
        List<Entry> original = Collections.unmodifiableList(Arrays.asList(a, b, c));
        RuleConfig rules = new RuleConfig(set(B), Arrays.asList(C, A));

        List<Entry> result = rules.apply(original, entry -> entry.component);

        assertEquals(Arrays.asList(a, b, c), original);
        assertNotSame(original, result);
        assertEquals(Arrays.asList(c, a), result);
        assertSame(c, result.get(0));
        assertSame(a, result.get(1));
    }

    @Test
    public void snapshotsDefensivelyCopyCollectionsAndExposeImmutableRules() {
        Set<String> hidden = set(B);
        List<String> order = new ArrayList<>(Arrays.asList(C, A, B));
        RuleConfig rules = new RuleConfig(hidden, order);
        hidden.clear();
        order.clear();

        assertEquals(set(B), rules.hiddenComponents);
        assertEquals(Arrays.asList(C, A, B), rules.orderedComponents);
        assertThrows(UnsupportedOperationException.class, () -> rules.hiddenComponents.add(A));
        assertThrows(UnsupportedOperationException.class, () -> rules.orderedComponents.add(D));
    }

    @Test
    public void hiddenEncodingIsDeterministicWithoutChangingCustomOrder() {
        RuleConfig first = new RuleConfig(set(C, A, B), Arrays.asList(C, A, B));
        RuleConfig second = new RuleConfig(set(B, C, A), Arrays.asList(C, A, B));

        assertEquals(first.encode(), second.encode());
        assertEquals(Arrays.asList(C, A, B), RuleConfig.decode(first.encode()).orderedComponents);
    }

    @Test
    public void unknownVersionsAndBrokenEnvelopeShapesAreRejected() {
        for (String value : Arrays.asList("", "v4::", "v3:", "v3:::extra", "v2::", "v1:1")) {
            assertThrows(value, IllegalArgumentException.class, () -> RuleConfig.decode(value));
        }
    }

    @Test
    public void corruptBase64AndInvalidActivityComponentsAreRejected() {
        for (String value : Arrays.asList("v2:%not-base64%", "v3::%not-base64%",
                "v2:" + payload("missing-separator"), "v3::" + payload("/Class"),
                "v3::" + payload("package/"), "v3::" + payload("package/Class/Extra"),
                "v3::" + payload("package/Class\n"), "v3::" + payload("package/Cl\u0000ass"))) {
            assertThrows(value, IllegalArgumentException.class, () -> RuleConfig.decode(value));
        }
    }

    @Test
    public void malformedUtf8PayloadIsRejectedInsteadOfBecomingAReplacementClassName() {
        byte[] invalidUtf8 = {'p', 'k', 'g', '/', (byte) 0xc3, (byte) 0x28};
        String value = "v3::" + Base64.getEncoder().encodeToString(invalidUtf8);

        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode(value));
    }

    @Test
    public void invalidRulesCannotBeEncodedForPersistence() {
        RuleConfig invalidHidden = new RuleConfig(set("missing-separator"), Collections.emptyList());
        RuleConfig invalidOrder = new RuleConfig(Collections.emptySet(), Collections.singletonList("package/Class/Extra"));

        assertThrows(IllegalArgumentException.class, invalidHidden::encode);
        assertThrows(IllegalArgumentException.class, invalidOrder::encode);
        assertFalse(new RuleConfig(Collections.emptySet(), Collections.emptyList()).encode().isEmpty());
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static String payload(String... values) {
        return Base64.getEncoder().encodeToString(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
    }

    private static final class Entry {
        final String component;

        Entry(String component) {
            this.component = component;
        }
    }
}
