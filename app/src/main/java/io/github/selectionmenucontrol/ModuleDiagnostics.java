package io.github.selectionmenucontrol;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Bounded, content-free diagnostics. No file IO, worker thread or raw exception text. */
final class ModuleDiagnostics {
    enum Code {
        HOOK_LOAD_FAILED, OPTIONS_HOOK_LOAD_FAILED, RULES_READ_FAILED, RULES_INVALID, RULES_LIMIT_EXCEEDED,
        ROOT_FAILED, ROOT_TIMEOUT, SAVE_STARTED, SAVE_VERIFIED, SAVE_FAILED,
        BACKUP_FAILED, JOURNAL_FAILED, JOURNAL_CONFLICT, POLICY_UNAVAILABLE,
        POLICY_FAILED, QUERY_FAILED, PROBE_FAILED, CACHE_INVALIDATED, UPDATE_FAILED,
        ROOT_DENIED, ROOT_UNAVAILABLE, ROOT_LOCKED, ROOT_EXPIRED, ROOT_STALE,
        ROOT_STORAGE, ROOT_WRITE, ROOT_VERIFY, ROOT_PROTOCOL, ROOT_IO, ROOT_INTERRUPTED,
        PROBE_READ_FAILED, PROBE_INCOMPATIBLE, PROBE_EXPIRED, PROBE_INVALID,
        PROBE_NO_RESPONSE, PROBE_CLEANUP_FAILED, PROBE_PREPARATION_FAILED, PROBE_QUERY_FAILED
    }
    enum Metric { QUERY_MS, POLICY_MS, POLICY_CACHE_HIT, POLICY_BACKOFF_HIT, RULE_CACHE_HIT, ROOT_MS, SAVE_MS,
        QUEUE_DEPTH, PROBE_PREPARE_MS, PROBE_QUERY_MS, PROBE_RESPONSE_MS, PROBE_CLEANUP_MS }
    enum EventPhase { EVENT, FAULT, RECOVERY }
    private static final ModuleDiagnostics INSTANCE = new ModuleDiagnostics(() -> System.nanoTime() / 1_000_000L);
    static final int MAX_EVENTS = 64;
    static final int MAX_OUTPUT = 16 * 1024;
    static final int LOG_BUDGET = 5;
    static final long WINDOW_MS = 10 * 60 * 1000L;
    private final LongSupplier clock;
    private final EnumMap<Code, Long> counts = new EnumMap<>(Code.class);
    private final EnumMap<Metric, long[]> metrics = new EnumMap<>(Metric.class);
    private final EnumSet<Code> active = EnumSet.noneOf(Code.class);
    private final EnumMap<Code, Long> faultWindows = new EnumMap<>(Code.class);
    private final EnumMap<Code, Long> recoveryWindows = new EnumMap<>(Code.class);
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private BiConsumer<Boolean, String> sink = (warning, summary) -> {};
    private long windowStarted = -1;
    private int emitted;

    ModuleDiagnostics(LongSupplier clock) { this.clock = clock; }
    static void configure(BiConsumer<Boolean, String> sink) { INSTANCE.setSink(sink); }
    static void fault(Code code) { INSTANCE.failure(code); }
    static void recover(Code code) { INSTANCE.recovered(code); }
    static void event(Code code, long operation, long elapsedMs) { INSTANCE.addEvent(code, operation, elapsedMs); }
    static void metric(Metric metric, long value) { INSTANCE.measure(metric, value); }
    static String snapshot() { return INSTANCE.report(); }

    synchronized void setSink(BiConsumer<Boolean, String> next) { sink = next; }
    synchronized void failure(Code code) {
        long count = counts.getOrDefault(code, 0L);
        counts.put(code, count == Long.MAX_VALUE ? count : count + 1);
        boolean first = active.add(code);
        refreshBudget();
        if (first) addEvent(EventPhase.FAULT, code, 0, 0);
        if (!Long.valueOf(windowStarted).equals(faultWindows.get(code))) {
            if (emit(true, code.name() + " count=" + counts.get(code))) faultWindows.put(code, windowStarted);
        }
    }
    synchronized void recovered(Code code) {
        if (active.remove(code)) {
            refreshBudget();
            addEvent(EventPhase.RECOVERY, code, 0, 0);
            if (!Long.valueOf(windowStarted).equals(recoveryWindows.get(code))
                    && emit(false, code.name() + " recovered count=" + counts.getOrDefault(code, 0L))) {
                recoveryWindows.put(code, windowStarted);
            }
        }
    }
    synchronized void addEvent(Code code, long operation, long elapsedMs) {
        addEvent(EventPhase.EVENT, code, operation, elapsedMs);
    }
    private void addEvent(EventPhase phase, Code code, long operation, long elapsedMs) {
        if (events.size() == MAX_EVENTS) events.removeFirst();
        events.addLast(clock.getAsLong() + " phase=" + phase.name() + " " + code.name() + " op=" + Math.max(0, operation)
                + " ms=" + Math.max(0, elapsedMs));
    }
    synchronized void measure(Metric metric, long value) {
        long[] buckets = metrics.computeIfAbsent(metric, ignored -> new long[16]);
        int bucket = value <= 0 ? 0 : Math.min(15, 64 - Long.numberOfLeadingZeros(value));
        if (buckets[bucket] != Long.MAX_VALUE) buckets[bucket]++;
    }
    synchronized String report() {
        StringBuilder report = new StringBuilder("txtoi diagnostics\nactive=" + active + "\n");
        counts.forEach((code, count) -> report.append(code).append(" count=").append(count).append('\n'));
        metrics.forEach((metric, buckets) -> {
            report.append(metric).append(" buckets[0,1,2-3,4-7,...]=");
            for (long bucket : buckets) report.append(bucket).append(',');
            report.append('\n');
        });
        for (String event : events) report.append(event).append('\n');
        return report.length() <= MAX_OUTPUT ? report.toString() : report.substring(0, MAX_OUTPUT);
    }
    private boolean refreshBudget() {
        long now = clock.getAsLong();
        if (windowStarted < 0 || now < windowStarted || now - windowStarted >= WINDOW_MS) {
            windowStarted = now;
            emitted = 0;
            return true;
        }
        return false;
    }
    private boolean emit(boolean warning, String summary) {
        if (emitted >= LOG_BUDGET) return false;
        emitted++;
        try { sink.accept(warning, summary); } catch (RuntimeException ignored) { /* Never affect a query. */ }
        return true;
    }
}
