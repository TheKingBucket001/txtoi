package io.github.selectionmenucontrol;

/** Protocol timestamps are boot uptime, never adjustable wall time. */
final class ProbeProtocol {
    static final class Record {
        final String nonce;
        final int boot, version;
        final long uptime;
        Record(String nonce, int boot, int version, long uptime) {
            this.nonce = nonce; this.boot = boot; this.version = version; this.uptime = uptime;
        }
    }
    static Record parse(String value) {
        if (value == null || value.length() > 160) return null;
        String[] parts = value.split(":", -1);
        if (parts.length != 5 || !"v2".equals(parts[0]) || !parts[1].matches("[a-f0-9]{32}")) return null;
        try {
            Record result = new Record(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Long.parseLong(parts[4]));
            return result.boot < 0 || result.version < 1 || result.uptime < 0 ? null : result;
        } catch (NumberFormatException error) { return null; }
    }
    static boolean current(Record value, int boot, int version, long now, long validity) {
        return value != null && boot >= 0 && value.boot == boot && value.version == version
                && value.uptime <= now && now - value.uptime <= validity;
    }
    static boolean matches(Record value, String nonce, long issuedAt, int boot, int version, long now, long validity) {
        return current(value, boot, version, now, validity) && nonce.equals(value.nonce) && value.uptime >= issuedAt;
    }
}
