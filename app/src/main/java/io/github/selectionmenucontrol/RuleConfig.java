package io.github.selectionmenucontrol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Immutable rules shared by the app and system_server. Empty order means system order. */
class RuleConfig {
    static final int MAX_ENCODED_LENGTH = 65_536;
    static final int MAX_ENTRIES = 512;
    static final int MAX_COMPONENT_LENGTH = 512;
    final Set<String> hiddenComponents;
    final List<String> orderedComponents;
    private final Map<String, Integer> ranks;

    RuleConfig(Set<String> hiddenComponents, List<String> orderedComponents) {
        if (hiddenComponents.size() > MAX_ENTRIES || orderedComponents.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many rule entries");
        }
        this.hiddenComponents = Collections.unmodifiableSet(new HashSet<>(hiddenComponents));
        this.orderedComponents = Collections.unmodifiableList(
                new ArrayList<>(new LinkedHashSet<>(orderedComponents)));
        Map<String, Integer> nextRanks = new HashMap<>();
        for (int index = 0; index < this.orderedComponents.size(); index++) {
            nextRanks.put(this.orderedComponents.get(index), index);
        }
        ranks = Collections.unmodifiableMap(nextRanks);
    }

    String encode() {
        List<String> hidden = new ArrayList<>(hiddenComponents);
        Collections.sort(hidden);
        String result = "v3:" + encodeList(hidden) + ":" + encodeList(orderedComponents);
        if (result.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("Rules too large");
        return result;
    }

    static RuleConfig decode(String value) {
        if (value == null) {
            return new RuleConfig(Collections.emptySet(), Collections.emptyList());
        }
        if (value.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("Rules too large");
        String[] parts = value.split(":", -1);
        if (parts.length == 3 && "v3".equals(parts[0])) {
            return new RuleConfig(new HashSet<>(decodeList(parts[1])), decodeList(parts[2]));
        }
        if (parts.length == 2 && "v2".equals(parts[0])) {
            return new RuleConfig(new HashSet<>(decodeList(parts[1])), Collections.emptyList());
        }
        if (parts.length == 3 && "v1".equals(parts[0])) {
            return new RuleConfig(new HashSet<>(decodeList(parts[2])), Collections.emptyList());
        }
        throw new IllegalArgumentException("Unrecognized rule format");
    }

    <T> List<T> apply(List<T> original, Function<T, String> componentOf) {
        return apply(original, componentOf, entry -> false);
    }

    /** Fixed slots are determined by the current system, not by component names. */
    <T> List<T> apply(List<T> original, Function<T, String> componentOf, Predicate<T> fixed) {
        List<T> visible = new ArrayList<>(original.size());
        for (T entry : original) {
            if (!hiddenComponents.contains(componentOf.apply(entry))) {
                visible.add(entry);
            }
        }
        if (!orderedComponents.isEmpty()) {
            List<T> ordinary = new ArrayList<>();
            boolean[] fixedSlots = new boolean[visible.size()];
            for (int index = 0; index < visible.size(); index++) {
                T entry = visible.get(index);
                fixedSlots[index] = fixed.test(entry);
                if (!fixedSlots[index]) {
                    ordinary.add(entry);
                }
            }
            // Stable sort keeps unspecified/new activities in their original relative order.
            ordinary.sort((left, right) -> Integer.compare(
                    ranks.getOrDefault(componentOf.apply(left), Integer.MAX_VALUE),
                    ranks.getOrDefault(componentOf.apply(right), Integer.MAX_VALUE)));
            // Preserve fixed slots and objects; only reorder the movable activities.
            int next = 0;
            for (int index = 0; index < visible.size(); index++) {
                if (!fixedSlots[index]) {
                    visible.set(index, ordinary.get(next++));
                }
            }
        }
        return visible;
    }

    private static String encodeList(List<String> components) {
        for (String component : components) {
            validateComponent(component);
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(String.join("\n", components)));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid rule payload encoding", error);
        }
    }

    private static List<String> decodeList(String encoded) {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid rule payload encoding", error);
        }
        if (decoded.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> components = new ArrayList<>();
        for (String component : decoded.split("\n", -1)) {
            validateComponent(component);
            components.add(component);
            if (components.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many rule entries");
        }
        return components;
    }

    private static void validateComponent(String component) {
        if (component == null || component.length() > MAX_COMPONENT_LENGTH) {
            throw new IllegalArgumentException("Invalid activity component");
        }
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1 || slash != component.lastIndexOf('/')) {
            throw new IllegalArgumentException("Invalid activity component");
        }
        for (int index = 0; index < component.length(); index++) {
            char character = component.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                throw new IllegalArgumentException("Invalid activity component");
            }
        }
    }
}
