package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The trusted PROCESS_TEXT query writes its response inside the synchronous server intercept,
 * before PackageManager returns. Waiting again for an observer cannot complete this query.
 * Keep the query's real completion as the boundary, without imposing a shorter timeout on it.
 */
internal suspend fun querySystemHook(
    query: suspend () -> Unit,
    readResponse: suspend () -> SystemRuleStore.HookStatus,
): SystemRuleStore.HookStatus {
    currentCoroutineContext().ensureActive()
    try {
        query()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        currentCoroutineContext().ensureActive()
        ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED)
        return SystemRuleStore.probeStatus(ProbeProtocol.Status.QUERY_FAILED)
    }
    currentCoroutineContext().ensureActive()
    val status = try {
        readResponse()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        SystemRuleStore.probeStatus(ProbeProtocol.Status.READ_FAILED)
    }
    currentCoroutineContext().ensureActive()
    return status
}
