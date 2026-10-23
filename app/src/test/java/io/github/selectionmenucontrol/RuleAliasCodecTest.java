package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RuleAliasCodecTest {
    private static final String A = "p/p.Alpha";
    private static final String B = "p/p.Beta";
    private static final String C = "p/p.Gamma";

    @Test public void oldVersionsHaveNoAliasesAndKeepV3Encoding() {
        for (String value : Arrays.asList(null, "v1:0:", "v2:", "v3::")) {
            RuleConfig decoded = RuleConfig.decode(value);
            assertTrue(decoded.aliases.isEmpty());
            assertEquals("v3::", decoded.encode());
        }
        RuleConfig nonEmpty = new RuleConfig(Collections.singleton(B), Arrays.asList(C, A));
        assertEquals(nonEmpty.encode(), new RuleConfig(nonEmpty.hiddenComponents,
                nonEmpty.orderedComponents, Collections.emptyMap()).encode());
        assertTrue(RuleConfig.decode(nonEmpty.encode()).aliases.isEmpty());
    }

    @Test public void aliasesRoundTripAlongsideHiddenAndOrderedRulesWithoutChangingIdentity() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put(A, "搜索");
        aliases.put(B, "隐藏仍保留");
        RuleConfig original = new RuleConfig(Collections.singleton(B), Arrays.asList(C, B, A), aliases);
        RuleConfig restored = RuleConfig.decode(original.encode());

        assertTrue(original.encode().startsWith("v4:"));
        assertEquals(original.aliases, restored.aliases);
        assertEquals(original.hiddenComponents, restored.hiddenComponents);
        assertEquals(original.orderedComponents, restored.orderedComponents);
        assertEquals(Arrays.asList(C, A), restored.apply(Arrays.asList(A, B, C), value -> value));
        assertEquals(Arrays.asList(A, C), restored.apply(Arrays.asList(A, B, C), value -> value, A::equals));
        assertEquals("隐藏仍保留", restored.aliases.get(B));
    }

    @Test public void aliasLookupDistinguishesActivitiesInTheSamePackage() {
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Collections.emptyList(),
                Map.of(A, "搜索", B, "翻译"));
        assertEquals("搜索", rules.aliases.get(A));
        assertEquals("翻译", rules.aliases.get(B));
        assertTrue(!rules.aliases.containsKey("p"));
    }

    @Test public void constructorCopiesMapAndExposesItAsImmutable() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put(A, "搜索");
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Collections.emptyList(), mutable);
        mutable.put(A, "被修改");
        mutable.put(B, "新增");
        assertEquals(Collections.singletonMap(A, "搜索"), rules.aliases);
        assertThrows(UnsupportedOperationException.class, () -> rules.aliases.put(B, "新增"));
        assertThrows(UnsupportedOperationException.class, () -> rules.aliases.entrySet().iterator().next().setValue("改"));
    }

    @Test public void normalizationTrimsUnicodeSpacesAndBlankValuesRestoreOriginalLabel() {
        assertEquals("搜索", RuleConfig.normalizeAlias(" \u3000搜索\u00a0 "));
        assertEquals("查 找", RuleConfig.normalizeAlias(" 查 找 "));
        assertEquals("", RuleConfig.normalizeAlias(" \u3000\u00a0\u2003"));
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Collections.emptyList(),
                Map.of(A, " \u3000", B, "  翻译  "));
        assertEquals(Collections.singletonMap(B, "翻译"), rules.aliases);
        assertEquals("v3::", new RuleConfig(Collections.emptySet(), Collections.emptyList(),
                Collections.singletonMap(A, " ")).encode());
    }

    @Test public void normalizedLengthCountsCodePointsAfterTrimmingRatherThanUtf16Units() {
        String emoji = "\uD83D\uDE00";
        String maximum = emoji.repeat(RuleConfig.MAX_ALIAS_CODE_POINTS);
        assertEquals(maximum, RuleConfig.normalizeAlias(" " + maximum + "\u3000"));
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(A, maximum));
        assertEquals(maximum, RuleConfig.decode(rules.encode()).aliases.get(A));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.normalizeAlias(maximum + "a"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(A, "字".repeat(21))));
    }

    @Test public void validSupplementaryCharactersAndEmojiSequencesSurviveStrictUtf8() {
        String component = "p/p.处理\uD840\uDC00";
        String alias = "翻译\uD83D\uDC69\u200d\uD83D\uDCBB e\u0301";
        RuleConfig rules = new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(component, alias));
        assertEquals(rules.aliases, RuleConfig.decode(rules.encode()).aliases);
    }

    @Test public void ordinaryRtlTextAndVisibleEmojiSequencesRemainValid() {
        for (String alias : Arrays.asList("مرحبا", "שלום", "بحث\u200f", "e\u0301", "\uD83D\uDC69\u200d\uD83D\uDCBB",
                "\u2764\uFE0F", "1\uFE0F\u20E3", "\uD83C\uDFF3\uFE0F\u200d\uD83C\uDF08")) {
            assertEquals(alias, RuleConfig.normalizeAlias(alias));
            RuleConfig value = new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(A, alias));
            assertEquals(alias, RuleConfig.decode(value.encode()).aliases.get(A));
        }
        assertEquals("", RuleConfig.normalizeAlias("\u3000\u2003\u00a0 "));
    }

    @Test public void nonEmptyAliasesWithoutABaseCharacterAreRejectedRatherThanBecomingBlankButtons() {
        for (String alias : Arrays.asList("\u200B", "\u2060", "\uFE0F", "\u200D", "\u200F", "\u0301", "\u20E3",
                " \u200B\uFE0F\u200D\u0301\u3000")) {
            assertThrows(alias, IllegalArgumentException.class, () -> RuleConfig.normalizeAlias(alias));
            assertThrows(alias, IllegalArgumentException.class,
                    () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(A, alias)));
            assertThrows(alias, IllegalArgumentException.class, () -> RuleConfig.decode("v4:::" + payload(A + "\t" + alias)));
        }
    }

    @Test public void explicitBidiEmbeddingOverrideAndIsolateControlsAreRejectedWithVisibleText() {
        int[] controls = {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069};
        for (int control : controls) {
            String alias = "abc" + new String(Character.toChars(control)) + "搜索";
            assertThrows(Integer.toHexString(control), IllegalArgumentException.class,
                    () -> RuleConfig.normalizeAlias(alias));
            assertThrows(Integer.toHexString(control), IllegalArgumentException.class,
                    () -> RuleConfig.decode("v4:::" + payload(A + "\t" + alias)));
        }
    }

    @Test public void controlCharactersSeparatorsAndUnpairedSurrogatesAreRejectedWithoutTruncation() {
        for (String alias : Arrays.asList("a\tb", "a\nb", "a\rb", "\u0000abc", "abc\u007f", "a\u0085b",
                "a\u2028b", "a\u2029b", "\uD800", "\uDC00", "a\uD800x", "\uDC00\uD800")) {
            assertThrows(alias, IllegalArgumentException.class, () -> RuleConfig.normalizeAlias(alias));
            assertThrows(alias, IllegalArgumentException.class,
                    () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(A, alias)));
        }
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.normalizeAlias(null));
        Map<String, String> nullAlias = new HashMap<>();
        nullAlias.put(A, null);
        assertThrows(IllegalArgumentException.class,
                () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), nullAlias));
    }

    @Test public void invalidAliasComponentsAreRejectedBeforeTheyCanBecomeRules() {
        for (String component : Arrays.asList("p", "/C", "p/", "p/C/More", "p/C\t", "p/C\n", "p /C")) {
            assertThrows(component, IllegalArgumentException.class,
                    () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of(component, "搜索")));
        }
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "搜索");
        assertThrows(IllegalArgumentException.class,
                () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), nullKey));
        RuleConfig malformedUnicode = new RuleConfig(Collections.emptySet(), Collections.emptyList(), Map.of("p/C\uD800", "搜索"));
        assertThrows(IllegalArgumentException.class, malformedUnicode::encode);
    }

    @Test public void aliasEncodingUsesSortedComponentOrderIndependentlyOfInputMapOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put(C, "三"); first.put(A, "一"); first.put(B, "二");
        Map<String, String> second = new LinkedHashMap<>();
        second.put(B, "二"); second.put(C, "三"); second.put(A, "一");
        RuleConfig left = new RuleConfig(Collections.emptySet(), Arrays.asList(C, A), first);
        RuleConfig right = new RuleConfig(Collections.emptySet(), Arrays.asList(C, A), second);
        assertEquals(left.encode(), right.encode());
        assertEquals(A + "\t一\n" + B + "\t二\n" + C + "\t三",
                new String(Base64.getDecoder().decode(left.encode().split(":", -1)[3]), StandardCharsets.UTF_8));
        assertEquals(left.encode(), RuleConfig.decode(left.encode()).encode());
    }

    @Test public void maximumAliasEntryCountIsRetainedAndOverflowIsRejected() {
        Map<String, String> aliases = new HashMap<>();
        for (int index = 0; index < RuleConfig.MAX_ENTRIES; index++) aliases.put("p/p.C" + index, "搜索");
        RuleConfig value = new RuleConfig(Collections.emptySet(), Collections.emptyList(), aliases);
        assertEquals(aliases, RuleConfig.decode(value.encode()).aliases);
        aliases.put("p/p.Overflow", "");
        assertThrows(IllegalArgumentException.class,
                () -> new RuleConfig(Collections.emptySet(), Collections.emptyList(), aliases));
        String duplicateRows = String.join("\n", Collections.nCopies(RuleConfig.MAX_ENTRIES + 1, A + "\t搜索"));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode("v4:::" + payload(duplicateRows)));
    }

    @Test public void malformedAliasRowsRejectTheWholePayloadInsteadOfDroppingFields() {
        for (String row : Arrays.asList(A, A + "\t", A + "\t ", A + "\t 搜索", A + "\t搜索 ",
                A + "\t搜索\t多余", A + "\t搜索\n", A + "\t搜索\n" + A + "\t翻译", "p\t搜索",
                A + "\t搜索\r", A + "\t搜索\u2028", A + "\t" + "字".repeat(21))) {
            assertThrows(row, IllegalArgumentException.class, () -> RuleConfig.decode("v4:::" + payload(row)));
        }
        for (String value : Arrays.asList("v4", "v4:", "v4::", "v4::::", "v4:::!invalid!", "v4:!invalid!::")) {
            assertThrows(value, IllegalArgumentException.class, () -> RuleConfig.decode(value));
        }
        assertEquals("v3::", RuleConfig.decode("v4:::").encode());
    }

    @Test public void malformedUtf8InAliasIsRejectedInsteadOfInsertingReplacementCharacters() {
        byte[] prefix = (A + "\t").getBytes(StandardCharsets.UTF_8);
        for (byte[] malformed : Arrays.asList(new byte[]{(byte) 0x80}, new byte[]{(byte) 0xc3, 0x28},
                new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + malformed.length);
            System.arraycopy(malformed, 0, bytes, prefix.length, malformed.length);
            String encoded = "v4:::" + Base64.getEncoder().encodeToString(bytes);
            assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode(encoded));
        }
    }

    @Test public void v4RequiresPaddedBase64InEveryFieldWhileLegacyUnpaddedInputsRemainCompatible() {
        RuleConfig original = new RuleConfig(Collections.singleton(B), Collections.singletonList(B), Map.of(A, "搜索"));
        String[] canonical = original.encode().split(":", -1);
        for (int field = 1; field < canonical.length; field++) {
            String[] unpadded = canonical.clone();
            unpadded[field] = unpadded[field].replace("=", "");
            assertTrue("Expected padding in field " + field, unpadded[field].length() < canonical[field].length());
            String wire = String.join(":", unpadded);
            assertThrows("Field " + field, IllegalArgumentException.class, () -> RuleConfig.decode(wire));
        }
        String legacy = payload(B).replace("=", "");
        assertEquals(Collections.singleton(B), RuleConfig.decode("v1:1:" + legacy).hiddenComponents);
        assertEquals(Collections.singleton(B), RuleConfig.decode("v2:" + legacy).hiddenComponents);
        RuleConfig v3 = RuleConfig.decode("v3:" + legacy + ":" + legacy);
        assertEquals(Collections.singleton(B), v3.hiddenComponents);
        assertEquals(Collections.singletonList(B), v3.orderedComponents);
    }

    @Test public void emptyV4FieldsRemainLegalAndDoNotInventRules() {
        String aliasPayload = payload(A + "\t搜索");
        RuleConfig aliasesOnly = RuleConfig.decode("v4:::" + aliasPayload);
        assertTrue(aliasesOnly.hiddenComponents.isEmpty());
        assertTrue(aliasesOnly.orderedComponents.isEmpty());
        assertEquals(Map.of(A, "搜索"), aliasesOnly.aliases);
        RuleConfig noOrder = RuleConfig.decode("v4:" + payload(B) + "::" + aliasPayload);
        assertEquals(Collections.singleton(B), noOrder.hiddenComponents);
        assertTrue(noOrder.orderedComponents.isEmpty());
        RuleConfig noHidden = RuleConfig.decode("v4::" + payload(C) + ":" + aliasPayload);
        assertTrue(noHidden.hiddenComponents.isEmpty());
        assertEquals(Collections.singletonList(C), noHidden.orderedComponents);
        RuleConfig noAliases = RuleConfig.decode("v4:" + payload(B) + ":" + payload(C) + ":");
        assertTrue(noAliases.aliases.isEmpty());
        assertEquals(new RuleConfig(Collections.singleton(B), Collections.singletonList(C)).encode(), noAliases.encode());
        assertEquals("v3::", RuleConfig.decode("v4:::").encode());
    }

    @Test public void aliasesShareTheExistingTotalEncodedPayloadLimit() {
        // v4's envelope uses 5 characters. Its largest Base64-aligned record is 65533.
        List<String> order = new ArrayList<>();
        for (int index = 0; index < 96; index++) {
            String prefix = "p/p.C" + index;
            int length = index == 95 ? 405 : RuleConfig.MAX_COMPONENT_LENGTH;
            order.add(prefix + "x".repeat(length - prefix.length()));
        }
        RuleConfig maximum = new RuleConfig(Collections.emptySet(), order, Map.of("p/C", "x"));
        assertEquals(RuleConfig.MAX_ENCODED_LENGTH - 3, maximum.encode().length());
        assertEquals(maximum.aliases, RuleConfig.decode(maximum.encode()).aliases);
        assertEquals(order, RuleConfig.decode(maximum.encode()).orderedComponents);
        order.set(95, order.get(95) + "x");
        RuleConfig oversized = new RuleConfig(Collections.emptySet(), order, Map.of("p/C", "x"));
        assertThrows(IllegalArgumentException.class, oversized::encode);
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode(maximum.encode() + "AAAA"));

        // Removing padding used to let an oversized canonical record enter as a "valid" short input.
        String oversizedWire = "v4::" + payload(String.join("\n", order)) + ":" + payload("p/C\tx");
        String unpaddedOversized = oversizedWire.replace("=", "");
        assertEquals(RuleConfig.MAX_ENCODED_LENGTH + 1, oversizedWire.length());
        assertEquals(RuleConfig.MAX_ENCODED_LENGTH - 2, unpaddedOversized.length());
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode(unpaddedOversized));
    }

    private static String payload(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
