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
    static final int MAX_ALIAS_CODE_POINTS = 20;
    final Set<String> hiddenComponents;
    final List<String> orderedComponents;
    final Map<String, String> aliases;
    private final Map<String, Integer> ranks;

    RuleConfig(Set<String> hiddenComponents, List<String> orderedComponents) {
        this(hiddenComponents, orderedComponents, Collections.emptyMap());
    }

    RuleConfig(Set<String> hiddenComponents, List<String> orderedComponents, Map<String, String> aliases) {
        if (hiddenComponents.size() > MAX_ENTRIES || orderedComponents.size() > MAX_ENTRIES
                || aliases.size() > MAX_ENTRIES) {
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
        Map<String, String> nextAliases = new HashMap<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            validateComponent(entry.getKey());
            String alias = normalizeAlias(entry.getValue());
            if (!alias.isEmpty()) nextAliases.put(entry.getKey(), alias);
        }
        this.aliases = Collections.unmodifiableMap(nextAliases);
    }

    String encode() {
        List<String> hidden = new ArrayList<>(hiddenComponents);
        Collections.sort(hidden);
        String result = aliases.isEmpty()
                ? "v3:" + encodeList(hidden) + ":" + encodeList(orderedComponents)
                : "v4:" + encodeList(hidden) + ":" + encodeList(orderedComponents) + ":" + encodeAliases(aliases);
        if (result.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("Rules too large");
        return result;
    }

    static RuleConfig decode(String value) {
        if (value == null) {
            return new RuleConfig(Collections.emptySet(), Collections.emptyList());
        }
        if (value.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("Rules too large");
        String[] parts = value.split(":", -1);
        if (parts.length == 4 && "v4".equals(parts[0])) {
            // Java accepts omitted Base64 padding, which could hide an oversized
            // canonical record behind a short input that cannot be saved again.
            for (int index = 1; index < parts.length; index++) {
                if (parts[index].length() % 4 != 0) throw new IllegalArgumentException("Invalid rule payload padding");
            }
            return new RuleConfig(new HashSet<>(decodeList(parts[1])), decodeList(parts[2]), decodeAliases(parts[3]));
        }
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

    /** Empty or whitespace-only input restores the activity's original label. */
    static String normalizeAlias(String value) {
        if (value == null) throw new IllegalArgumentException("Invalid menu alias");
        boolean hasBaseCharacter = false;
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Invalid menu alias");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid menu alias");
            }
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029
                    || (codePoint >= 0x202A && codePoint <= 0x202E)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
                throw new IllegalArgumentException("Invalid menu alias");
            }
            int type = Character.getType(codePoint);
            if (!isAliasSpace(codePoint) && type != Character.FORMAT && type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK) {
                hasBaseCharacter = true;
            }
            index += Character.charCount(codePoint);
        }
        int start = 0;
        int end = value.length();
        while (start < end && isAliasSpace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isAliasSpace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        String normalized = value.substring(start, end);
        if (!normalized.isEmpty() && !hasBaseCharacter) {
            throw new IllegalArgumentException("Menu alias needs a base character");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_ALIAS_CODE_POINTS) {
            throw new IllegalArgumentException("Menu alias too long");
        }
        return normalized;
    }

    private static boolean isAliasSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
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
        return encodeText(String.join("\n", components));
    }

    private static String encodeAliases(Map<String, String> aliases) {
        List<String> components = new ArrayList<>(aliases.keySet());
        Collections.sort(components);
        List<String> entries = new ArrayList<>(components.size());
        for (String component : components) entries.add(component + "\t" + aliases.get(component));
        return encodeText(String.join("\n", entries));
    }

    private static String encodeText(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid rule payload encoding", error);
        }
    }

    private static List<String> decodeList(String encoded) {
        String decoded = decodeText(encoded);
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

    private static Map<String, String> decodeAliases(String encoded) {
        String decoded = decodeText(encoded);
        if (decoded.isEmpty()) return Collections.emptyMap();
        Map<String, String> aliases = new HashMap<>();
        String[] entries = decoded.split("\n", -1);
        if (entries.length > MAX_ENTRIES) throw new IllegalArgumentException("Too many rule entries");
        for (String entry : entries) {
            int tab = entry.indexOf('\t');
            if (tab <= 0 || tab != entry.lastIndexOf('\t')) {
                throw new IllegalArgumentException("Invalid alias entry");
            }
            String component = entry.substring(0, tab);
            validateComponent(component);
            String alias = entry.substring(tab + 1);
            // Persisted values must already be normalized. Never discard or repair a damaged record.
            if (alias.isEmpty() || !alias.equals(normalizeAlias(alias)) || aliases.containsKey(component)) {
                throw new IllegalArgumentException("Invalid alias entry");
            }
            aliases.put(component, alias);
        }
        return aliases;
    }

    private static String decodeText(String encoded) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid rule payload encoding", error);
        }
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
