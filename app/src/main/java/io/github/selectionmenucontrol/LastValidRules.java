package io.github.selectionmenucontrol;

/** A read error never means a deliberate clear. The fallback is bounded and monotonic. */
final class LastValidRules<T> {
    private final long retentionMs;
    private T last;
    private long observedAt;
    LastValidRules(long retentionMs) { this.retentionMs = retentionMs; }
    synchronized long expiresAt() { return observedAt + retentionMs; }
    synchronized T accept(T value, boolean valid, long now) {
        if (valid) { last = value; observedAt = now; return value; }
        return last != null && now >= observedAt && now - observedAt <= retentionMs ? last : value;
    }
}
