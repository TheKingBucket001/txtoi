package io.github.selectionmenucontrol;

import java.nio.ByteBuffer;
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
    final Set<String> hiddenComponents;
    final List<String> orderedComponents;

    RuleConfig(Set<String> hiddenComponents, List<String> orderedComponents) {
        this.hiddenComponents = Collections.unmodifiableSet(new HashSet<>(hiddenComponents));
        this.orderedComponents = Collections.unmodifiableList(
                new ArrayList<>(new LinkedHashSet<>(orderedComponents)));
    }

    String encode() {
        List<String> hidden = new ArrayList<>(hiddenComponents);
        Collections.sort(hidden);
        return "v3:" + encodeList(hidden) + ":" + encodeList(orderedComponents);
    }

    static RuleConfig decode(String value) {
        if (value == null) {
            return new RuleConfig(Collections.emptySet(), Collections.emptyList());
        }
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
            Map<String, Integer> ranks = new HashMap<>();
            for (int index = 0; index < orderedComponents.size(); index++) {
                ranks.put(orderedComponents.get(index), index);
            }
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
        return Base64.getEncoder().encodeToString(
                String.join("\n", components).getBytes(StandardCharsets.UTF_8));
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
        }
        return components;
    }

    private static void validateComponent(String component) {
        if (component == null) {
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
