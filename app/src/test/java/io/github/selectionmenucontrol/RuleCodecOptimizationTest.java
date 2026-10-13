package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public class RuleCodecOptimizationTest {
    @Test public void maximumEntryCountRoundTripsWithoutDroppingComponents() {
        List<String> order = new ArrayList<>();
        for (int index = 0; index < RuleConfig.MAX_ENTRIES; index++) order.add("p/p.C" + index);
        RuleConfig value = new RuleConfig(new HashSet<>(order), order);
        RuleConfig decoded = RuleConfig.decode(value.encode());
        assertEquals(RuleConfig.MAX_ENTRIES, decoded.hiddenComponents.size());
        assertEquals(order, decoded.orderedComponents);
    }

    @Test public void payloadEntryLimitAppliesBeforeDuplicateNormalization() {
        List<String> repeated = Collections.nCopies(RuleConfig.MAX_ENTRIES + 1, "p/p.C");
        String payload = Base64.getEncoder().encodeToString(String.join("\n", repeated).getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode("v3::" + payload));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode("v3:" + payload + ":"));
        assertThrows(IllegalArgumentException.class, () -> new RuleConfig(Collections.emptySet(), repeated));
    }

    @Test public void encodedLimitAcceptsExactBoundaryAndRejectsNextBase64Block() {
        // 95 * 512 + 414 + 95 newlines = 49149 UTF-8 bytes.
        // Base64 uses 65532 characters; the "v3::" envelope fills the 65536 limit.
        List<String> order = new ArrayList<>();
        for (int index = 0; index < 96; index++) {
            String prefix = "p/p.C" + index;
            int length = index == 95 ? 414 : RuleConfig.MAX_COMPONENT_LENGTH;
            order.add(prefix + "x".repeat(length - prefix.length()));
        }
        RuleConfig exact = new RuleConfig(Collections.emptySet(), order);
        String encoded = exact.encode();
        assertEquals(RuleConfig.MAX_ENCODED_LENGTH, encoded.length());
        assertEquals(order, RuleConfig.decode(encoded).orderedComponents);

        order.set(95, order.get(95) + "x");
        RuleConfig oversized = new RuleConfig(Collections.emptySet(), order);
        assertThrows(IllegalArgumentException.class, oversized::encode);
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.decode(encoded + "AAAA"));
    }

    @Test public void componentLengthUsesDocumentedBoundary() {
        String maximum = "p/" + "C".repeat(RuleConfig.MAX_COMPONENT_LENGTH - 2);
        RuleConfig value = new RuleConfig(Collections.singleton(maximum), Collections.singletonList(maximum));
        assertEquals(Collections.singletonList(maximum), RuleConfig.decode(value.encode()).orderedComponents);
        RuleConfig oversized = new RuleConfig(Collections.singleton(maximum + "C"), Collections.emptyList());
        assertThrows(IllegalArgumentException.class, oversized::encode);
    }

    @Test public void decoderRejectsNonCanonicalUtf8InsteadOfInventingComponentNames() {
        byte[][] invalidTails = {
                {(byte) 0x80}, // Lone continuation.
                {(byte) 0xc0, (byte) 0xaf}, // Overlong ASCII.
                {(byte) 0xf0, (byte) 0x9f}, // Truncated supplementary character.
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, // UTF-8 encoding of a surrogate.
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80} // Beyond Unicode's range.
        };
        for (byte[] tail : invalidTails) {
            byte[] payload = new byte[3 + tail.length];
            payload[0] = 'p'; payload[1] = '/'; payload[2] = 'C';
            System.arraycopy(tail, 0, payload, 3, tail.length);
            String encoded = Base64.getEncoder().encodeToString(payload);
            assertThrows(Arrays.toString(tail), IllegalArgumentException.class,
                    () -> RuleConfig.decode("v3::" + encoded));
        }
    }

    @Test public void encoderRejectsUnpairedSurrogatesButPreservesValidPairs() {
        for (String malformed : Arrays.asList("p/C\uD800", "p/C\uDC00", "p/C\uD800x", "p/C\uDC00\uD800")) {
            RuleConfig value = new RuleConfig(Collections.emptySet(), Collections.singletonList(malformed));
            assertThrows(IllegalArgumentException.class, value::encode);
        }
        String supplementary = "p/C\uD83D\uDE00";
        RuleConfig value = new RuleConfig(Collections.singleton(supplementary), Collections.singletonList(supplementary));
        assertEquals(value.orderedComponents, RuleConfig.decode(value.encode()).orderedComponents);
        assertEquals(value.hiddenComponents, RuleConfig.decode(value.encode()).hiddenComponents);
    }
}
