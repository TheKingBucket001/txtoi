package io.github.selectionmenucontrol;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ModuleDiagnosticsTest {
    @Test public void repeatedAndAlternatingFaultsHaveAGlobalBudgetAndKeepCounts() {
        AtomicLong now = new AtomicLong();
        ModuleDiagnostics diagnostics = new ModuleDiagnostics(now::get);
        List<String> output = new ArrayList<>();
        diagnostics.setSink((warning, summary) -> output.add(summary));
        for (int i = 0; i < 1000; i++) {
            diagnostics.failure(ModuleDiagnostics.Code.ROOT_TIMEOUT);
            diagnostics.recovered(ModuleDiagnostics.Code.ROOT_TIMEOUT);
            diagnostics.failure(ModuleDiagnostics.Code.POLICY_FAILED);
        }
        assertEquals(3, output.size());
        assertTrue(diagnostics.report().contains("ROOT_TIMEOUT count=1000"));
        assertTrue(diagnostics.report().contains("POLICY_FAILED count=1000"));
        now.set(ModuleDiagnostics.WINDOW_MS);
        diagnostics.failure(ModuleDiagnostics.Code.POLICY_FAILED);
        assertEquals(4, output.size());
    }
    @Test public void unrelatedFaultsStillShareOneBudget() {
        ModuleDiagnostics diagnostics = new ModuleDiagnostics(() -> 1L);
        List<String> output = new ArrayList<>();
        diagnostics.setSink((warning, summary) -> output.add(summary));
        for (ModuleDiagnostics.Code code : ModuleDiagnostics.Code.values()) {
            diagnostics.failure(code); diagnostics.recovered(code);
        }
        assertEquals(ModuleDiagnostics.LOG_BUDGET, output.size());
    }
    @Test public void normalMetricsNeverEmitAndHistoryIsBounded() {
        ModuleDiagnostics diagnostics = new ModuleDiagnostics(() -> 1L);
        diagnostics.setSink((warning, summary) -> fail("Normal operations must not print"));
        for (int i = 0; i < 10000; i++) {
            diagnostics.measure(ModuleDiagnostics.Metric.QUERY_MS, i);
            diagnostics.addEvent(ModuleDiagnostics.Code.SAVE_VERIFIED, i, 4);
        }
        String report = diagnostics.report();
        assertFalse(report.contains("op=1 "));
        assertTrue(report.contains("op=9999 "));
        assertTrue(report.length() < ModuleDiagnostics.MAX_OUTPUT);
    }
    @Test public void faultRecoveryAndRecurrenceRemainDistinctAtTheSameTimestamp() {
        ModuleDiagnostics diagnostics = new ModuleDiagnostics(() -> 100L);
        diagnostics.failure(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        diagnostics.recovered(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        diagnostics.failure(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        diagnostics.addEvent(ModuleDiagnostics.Code.SAVE_VERIFIED, 7, 12);
        String report = diagnostics.report();
        assertTrue(report.contains("active=[ROOT_TIMEOUT]"));
        assertTrue(report.contains("ROOT_TIMEOUT count=2"));
        String fault = "100 phase=FAULT ROOT_TIMEOUT op=0 ms=0\n";
        String recovery = "100 phase=RECOVERY ROOT_TIMEOUT op=0 ms=0\n";
        assertTrue(report.contains(fault + recovery + fault
                + "100 phase=EVENT SAVE_VERIFIED op=7 ms=12\n"));
        assertEquals(4, report.lines().filter(line -> line.contains(" phase=")).count());
    }
    @Test public void exhaustedLogBudgetStillKeepsDistinctTransitionsInBoundedHistory() {
        ModuleDiagnostics diagnostics = new ModuleDiagnostics(() -> 1L);
        List<String> output = new ArrayList<>();
        diagnostics.setSink((warning, summary) -> output.add(summary));
        ModuleDiagnostics.Code[] codes = ModuleDiagnostics.Code.values();
        for (int index = 0; index < ModuleDiagnostics.LOG_BUDGET; index++) diagnostics.failure(codes[index]);
        assertEquals(ModuleDiagnostics.LOG_BUDGET, output.size());
        diagnostics.failure(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        diagnostics.recovered(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        diagnostics.failure(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        assertEquals(ModuleDiagnostics.LOG_BUDGET, output.size());
        String fault = "1 phase=FAULT ROOT_TIMEOUT op=0 ms=0\n";
        assertTrue(diagnostics.report().contains(fault
                + "1 phase=RECOVERY ROOT_TIMEOUT op=0 ms=0\n" + fault));
        assertEquals(ModuleDiagnostics.LOG_BUDGET + 3,
                diagnostics.report().lines().filter(line -> line.contains(" phase=")).count());
    }
    @Test public void metricsDoNotAddFaultRecoveryOrEventHistory() {
        ModuleDiagnostics diagnostics = new ModuleDiagnostics(() -> 1L);
        diagnostics.setSink((warning, summary) -> fail("Normal metrics must not print"));
        for (int index = 0; index < 10000; index++) {
            diagnostics.measure(ModuleDiagnostics.Metric.QUERY_MS, index);
            diagnostics.measure(ModuleDiagnostics.Metric.POLICY_CACHE_HIT, 1);
        }
        String report = diagnostics.report();
        assertTrue(report.contains("active=[]"));
        assertFalse(report.contains(" phase="));
        assertFalse(report.contains(" count="));
        assertTrue(report.length() < ModuleDiagnostics.MAX_OUTPUT);
    }
}
