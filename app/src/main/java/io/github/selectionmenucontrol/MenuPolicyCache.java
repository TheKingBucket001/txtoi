package io.github.selectionmenucontrol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stores only classification integers and indices, never framework menu or package objects. */
final class MenuPolicyCache {
    static final int CAPACITY = 8;
    static final long TTL_MS = 30_000L;
    static final class Value {
        final int[] kinds, order;
        final long issuedAt;
        Value(int[] kinds, int[] order, long issuedAt) {
            this.kinds = kinds.clone(); this.order = order.clone(); this.issuedAt = issuedAt;
        }
    }
    private final LinkedHashMap<String, Value> values = new LinkedHashMap<>(CAPACITY, .75f, true);
    synchronized Value get(String key, long now) {
        Value value = values.get(key);
        if (value != null && (now < value.issuedAt || now - value.issuedAt >= TTL_MS)) {
            values.remove(key); return null;
        }
        return value == null ? null : new Value(value.kinds, value.order, value.issuedAt);
    }
    synchronized void put(String key, int[] kinds, int[] order, long now) {
        if (kinds.length != order.length || kinds.length > RuleConfig.MAX_ENTRIES) return;
        boolean[] seen = new boolean[kinds.length];
        for (int index : order) {
            if (index < 0 || index >= kinds.length || seen[index]) return;
            seen[index] = true;
        }
        values.put(key, new Value(kinds, order, now));
        if (values.size() > CAPACITY) values.remove(values.keySet().iterator().next());
    }
    synchronized void clear() { values.clear(); }
}
