package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val MAX_PROBE_ATTEMPTS = 3
private val PROBE_RETRY_DELAYS_MS = longArrayOf(120L, 240L)

/**
 * The trusted PROCESS_TEXT query writes its response inside the synchronous server intercept,
 * before PackageManager returns. Waiting again for an observer cannot complete this query.
 * Keep the query's real completion as the boundary, without imposing a shorter timeout on it.
 */
internal suspend fun querySystemHook(
    query: suspend () -> Unit,
    readResponse: suspend () -> SystemRuleStore.HookStatus,
): SystemRuleStore.HookStatus {
    var status = SystemRuleStore.probeStatus(ProbeProtocol.Status.NO_RESPONSE)
    repeat(MAX_PROBE_ATTEMPTS) { attempt ->
        currentCoroutineContext().ensureActive()
        status = try {
            query()
            currentCoroutineContext().ensureActive()
            try {
                readResponse()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                SystemRuleStore.probeStatus(ProbeProtocol.Status.READ_FAILED)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.PROBE_FAILED)
            SystemRuleStore.probeStatus(ProbeProtocol.Status.QUERY_FAILED)
        }
        if (status.status !in setOf(
                ProbeProtocol.Status.NO_RESPONSE,
                ProbeProtocol.Status.QUERY_FAILED,
                ProbeProtocol.Status.READ_FAILED,
            ) || attempt == MAX_PROBE_ATTEMPTS - 1
        ) {
            return status
        }
        delay(PROBE_RETRY_DELAYS_MS[attempt])
    }
    return status
}
