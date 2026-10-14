package io.github.selectionmenucontrol;

/** Content-free challenge identity; timestamps use boot elapsed time, never wall time. */
final class ProbeProtocol {
    static final String PREFIX = "v3:";
    static final int CAPABILITY = 1;
    enum Status { READY, NO_RESPONSE, PREPARATION_FAILED, QUERY_FAILED, READ_FAILED,
        INCOMPATIBLE, EXPIRED, INVALID_RESPONSE }

    static final class Record {
        final String nonce, buildId;
        final int boot, version, capability;
        final long uptime;
        Record(String nonce, int boot, int version, int capability, String buildId, long uptime) {
            this.nonce = nonce; this.boot = boot; this.version = version;
            this.capability = capability; this.buildId = buildId; this.uptime = uptime;
        }
    }

    static String requestPrefix(String nonce, int boot, int version, String buildId) {
        return PREFIX + nonce + ":" + boot + ":" + version + ":" + CAPABILITY + ":" + buildId + ":";
    }

    static String response(Record request, int version, String buildId, long now) {
        return requestPrefix(request.nonce, request.boot, version, buildId) + now;
    }

    static Record parse(String value) {
        if (value == null || value.length() > 240) return null;
        String[] parts = value.split(":", -1);
        if (parts.length != 7 || !"v3".equals(parts[0])
                || !parts[1].matches("[a-f0-9]{32}") || !parts[5].matches("[a-f0-9]{64}")) return null;
        try {
            Record result = new Record(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]), parts[5], Long.parseLong(parts[6]));
            return result.boot < 0 || result.version < 1 || result.capability < 1 || result.uptime < 0 ? null : result;
        } catch (NumberFormatException error) { return null; }
    }

    // A different build can answer with its own identity, letting the client
    // distinguish a loaded old candidate from an absent or unreadable response.
    static boolean currentRequest(Record value, int boot, long now, long validity) {
        return value != null && boot >= 0 && value.boot == boot
                && value.uptime <= now && now - value.uptime <= validity;
    }

    static Status evaluate(String raw, String nonce, long issuedAt, int boot, int version,
                           String buildId, long now, long validity) {
        if (raw == null) return Status.NO_RESPONSE;
        Record value = parse(raw);
        if (value == null) {
            if (raw.startsWith("v2:") || raw.startsWith("v1:")) {
                return Status.INCOMPATIBLE;
            }
            return Status.INVALID_RESPONSE;
        }
        if (!nonce.equals(value.nonce)) return Status.NO_RESPONSE;
        if (boot < 0) return Status.READ_FAILED;
        if (value.boot != boot || value.uptime < issuedAt) return Status.NO_RESPONSE;
        if (value.uptime > now) return Status.INVALID_RESPONSE;
        if (now - value.uptime > validity) return Status.EXPIRED;
        if (value.version != version || value.capability != CAPABILITY || !buildId.equals(value.buildId)) {
            return Status.INCOMPATIBLE;
        }
        return Status.READY;
    }
}
