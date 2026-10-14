package io.github.selectionmenucontrol;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProbeSessionTest {
    @Test public void preparationFailuresNeverDiscardTheCleanupSessionOrPartialOutcome() {
        SystemRuleStore.ProbeRequest request = new SystemRuleStore.ProbeRequest(
                "0123456789abcdef0123456789abcdef", 100L, 1, 18, ProbeProtocol.CAPABILITY, "a".repeat(64));
        for (RootAccess.Failure failure : RootAccess.Failure.values()) {
            RootAccess.ExecutionResult result = new RootAccess.ExecutionResult(failure, 12L, 77,
                    failure != RootAccess.Failure.NONE);
            SystemRuleStore.ProbeStart start = SystemRuleStore.preparedProbe(request, result);
            assertSame(request, start.request);
            assertSame(result, start.preparation);
            assertEquals(failure == RootAccess.Failure.NONE, start.prepared);
        }
    }

    @Test public void typedResponseFailuresCannotTurnIntoLoadedOrMissingScopeAssertions() {
        for (ProbeProtocol.Status status : ProbeProtocol.Status.values()) {
            SystemRuleStore.HookStatus result = SystemRuleStore.probeStatus(status);
            assertEquals(status, result.status);
            assertEquals(status == ProbeProtocol.Status.READY, result.loadedForCurrentBoot);
            assertFalse(result.message.contains("启用 LSPosed"));
        }
    }

    @Test public void scopePreparationFailureDoesNotMasqueradeAsRootFailure() {
        SystemRuleStore.HookStatus status = SystemRuleStore.probeStatus(ProbeProtocol.Status.PREPARATION_FAILED,
                "系统框架作用域检测请求未能写入，请重新检查");
        assertFalse(status.loadedForCurrentBoot);
        assertEquals(ProbeProtocol.Status.PREPARATION_FAILED, status.status);
        assertEquals("系统框架作用域检测请求未能写入，请重新检查", status.message);
    }

    @Test public void invalidInputsHaveAnExplicitFiniteMessage() {
        assertEquals("Root 命令参数无效", RootAccess.failureMessage(
                new RootAccess.ExecutionResult(RootAccess.Failure.INVALID_INPUT, 0L, -1, false)));
    }

    @Test public void preparationAndQueryFailuresHaveIndependentDiagnosticsThenReadyRetiresThem() {
        SystemRuleStore.probeStatus(ProbeProtocol.Status.READY);
        long preparationBefore = count(ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED);
        long queryBefore = count(ModuleDiagnostics.Code.PROBE_QUERY_FAILED);
        long missingBefore = count(ModuleDiagnostics.Code.PROBE_NO_RESPONSE);
        try {
            SystemRuleStore.HookStatus preparation = SystemRuleStore.probeStatus(
                    ProbeProtocol.Status.PREPARATION_FAILED, "具体准备失败原因");
            assertEquals("具体准备失败原因", preparation.message);
            assertFalse(preparation.loadedForCurrentBoot);
            assertTrue(active().contains(ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED.name()));
            assertFalse(active().contains(ModuleDiagnostics.Code.PROBE_QUERY_FAILED.name()));
            assertEquals(preparationBefore + 1, count(ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED));
            assertEquals(queryBefore, count(ModuleDiagnostics.Code.PROBE_QUERY_FAILED));
            assertEquals(missingBefore, count(ModuleDiagnostics.Code.PROBE_NO_RESPONSE));

            SystemRuleStore.HookStatus query = SystemRuleStore.probeStatus(
                    ProbeProtocol.Status.QUERY_FAILED, "具体查询失败原因");
            assertEquals("具体查询失败原因", query.message);
            assertFalse(query.loadedForCurrentBoot);
            assertTrue(active().contains(ModuleDiagnostics.Code.PROBE_QUERY_FAILED.name()));
            assertEquals(queryBefore + 1, count(ModuleDiagnostics.Code.PROBE_QUERY_FAILED));
            assertEquals(preparationBefore + 1, count(ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED));
            assertEquals(missingBefore, count(ModuleDiagnostics.Code.PROBE_NO_RESPONSE));

            assertTrue(SystemRuleStore.probeStatus(ProbeProtocol.Status.READY).loadedForCurrentBoot);
            assertFalse(active().contains(ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED.name()));
            assertFalse(active().contains(ModuleDiagnostics.Code.PROBE_QUERY_FAILED.name()));
            assertEquals(preparationBefore + 1, count(ModuleDiagnostics.Code.PROBE_PREPARATION_FAILED));
            assertEquals(queryBefore + 1, count(ModuleDiagnostics.Code.PROBE_QUERY_FAILED));
            assertEquals(missingBefore, count(ModuleDiagnostics.Code.PROBE_NO_RESPONSE));
        } finally {
            SystemRuleStore.probeStatus(ProbeProtocol.Status.READY);
        }
    }

    @Test public void successfulPreparationRetiresEarlierRootFailureWithoutErasingItsCountOrPendingFaults() {
        SystemRuleStore.ProbeRequest request = new SystemRuleStore.ProbeRequest(
                "0123456789abcdef0123456789abcdef", 100L, 1, 18, ProbeProtocol.CAPABILITY, "a".repeat(64));
        RootAccess.ExecutionResult success = new RootAccess.ExecutionResult(RootAccess.Failure.NONE, 12L, 0, false);
        SystemRuleStore.preparedProbe(request, success);
        long timeoutBefore = count(ModuleDiagnostics.Code.ROOT_TIMEOUT);
        boolean saveAlreadyActive = active().contains(ModuleDiagnostics.Code.SAVE_FAILED.name());
        boolean journalAlreadyActive = active().contains(ModuleDiagnostics.Code.JOURNAL_FAILED.name());
        try {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.SAVE_FAILED);
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED);
            SystemRuleStore.preparedProbe(request,
                    new RootAccess.ExecutionResult(RootAccess.Failure.TIMEOUT, 3000L, -1, true));
            assertTrue(active().contains(ModuleDiagnostics.Code.ROOT_TIMEOUT.name()));
            assertEquals(timeoutBefore + 1, count(ModuleDiagnostics.Code.ROOT_TIMEOUT));
            assertTrue(SystemRuleStore.preparedProbe(request, success).prepared);
            assertFalse(active().contains(ModuleDiagnostics.Code.ROOT_TIMEOUT.name()));
            assertEquals(timeoutBefore + 1, count(ModuleDiagnostics.Code.ROOT_TIMEOUT));
            assertTrue(active().contains(ModuleDiagnostics.Code.SAVE_FAILED.name()));
            assertTrue(active().contains(ModuleDiagnostics.Code.JOURNAL_FAILED.name()));
        } finally {
            SystemRuleStore.preparedProbe(request, success);
            if (!saveAlreadyActive) ModuleDiagnostics.recover(ModuleDiagnostics.Code.SAVE_FAILED);
            if (!journalAlreadyActive) ModuleDiagnostics.recover(ModuleDiagnostics.Code.JOURNAL_FAILED);
        }
    }

    private static String active() {
        return ModuleDiagnostics.snapshot().lines().filter(line -> line.startsWith("active=")).findFirst().orElseThrow();
    }

    private static long count(ModuleDiagnostics.Code code) {
        String prefix = code.name() + " count=";
        return ModuleDiagnostics.snapshot().lines().filter(line -> line.startsWith(prefix))
                .mapToLong(line -> Long.parseLong(line.substring(prefix.length()))).findFirst().orElse(0L);
    }
}
