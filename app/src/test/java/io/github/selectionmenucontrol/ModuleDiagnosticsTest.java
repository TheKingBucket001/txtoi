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
}
