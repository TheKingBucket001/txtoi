package io.github.selectionmenucontrol

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal object UpdateSettings {
    private fun preferences(context: Context) = context.getSharedPreferences("update_settings", Context.MODE_PRIVATE)
    fun isAutoCheckEnabled(context: Context): Boolean = preferences(context).getBoolean("auto_check", true)
    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean("auto_check", enabled) }
    }
}

/** Owns one request across rotation. Backgrounding or disabling automatic checks cancels its IO. */
internal class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = UpdateCheckController(viewModelScope) { manual ->
        UpdateRepository.check(getApplication(), manual)
    }
    val state = controller.state

    fun automaticCheck() {
        if (!UpdateSettings.isAutoCheckEnabled(getApplication())) return
        controller.check(manual = false)
    }

    fun manualCheck() = controller.check(manual = true)
    fun cancelCheck(automaticOnly: Boolean = false) = controller.cancel(automaticOnly)
    fun dismissUpdate() = controller.dismissUpdate()
    fun consumeNotice(): String? = controller.consumeNotice()
}

private object UpdateRepository {
    private const val CACHE_VALIDITY_MS = 15 * 60_000L
    private const val FAILURE_VALIDITY_MS = 60_000L
    private const val TOTAL_TIMEOUT_MS = 15_000L
    private val mutex = Mutex()
    private val transportMutex = Mutex()
    private var lastResult: UpdateCheckResult? = null
    private var completedAt = -1L

    suspend fun check(context: Context, manual: Boolean): UpdateCheckResult = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val previous = lastResult
        val validity = if (previous == UpdateCheckResult.Failed) FAILURE_VALIDITY_MS else CACHE_VALIDITY_MS
        if (!manual && previous != null && UpdatePolicy.fresh(completedAt, now, validity)) return@withLock previous
        val deadline = now + TOTAL_TIMEOUT_MS
        val result = try {
            if (!waitForNetwork(context, deadline)) UpdateCheckResult.Failed else {
                var release: UpdateInfo? = null
                repeat(2) { attempt ->
                    if (release != null) return@repeat
                    currentCoroutineContext().ensureActive()
                    try { release = requestLatestRelease(deadline) }
                    catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        if (attempt == 0 && SystemClock.elapsedRealtime() + 1_000 < deadline) delay(1_000)
                    }
                }
                release?.let {
                    if (UpdatePolicy.isVersionNewer(it.version, BuildConfig.VERSION_NAME)) UpdateCheckResult.Available(it)
                    else UpdateCheckResult.UpToDate
                } ?: UpdateCheckResult.Failed
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            UpdateCheckResult.Failed
        }
        completedAt = SystemClock.elapsedRealtime()
        lastResult = result
        if (result == UpdateCheckResult.Failed) ModuleDiagnostics.fault(ModuleDiagnostics.Code.UPDATE_FAILED)
        else ModuleDiagnostics.recover(ModuleDiagnostics.Code.UPDATE_FAILED)
        result
    }

    private suspend fun waitForNetwork(context: Context, deadline: Long): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val networkDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 5_000L)
        do {
            currentCoroutineContext().ensureActive()
            val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
            delay(250)
        } while (SystemClock.elapsedRealtime() < networkDeadline)
        return false
    }

    /** Disconnect on cancellation; no response body is downloaded. Every redirect stays in our repository. */
    private suspend fun requestLatestRelease(deadline: Long): UpdateInfo = withTimeout(
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
    ) {
        awaitLatestRelease(deadline)
    }

    private suspend fun awaitLatestRelease(deadline: Long): UpdateInfo = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = CoroutineScope(Dispatchers.IO).launch {
            try {
                transportMutex.withLock {
                    var address = UpdatePolicy.LATEST_RELEASE_URL
                    var release: UpdateInfo? = null
                    repeat(4) {
                        if (release != null) return@repeat
                        ensureActive()
                        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(5_000L)
                        check(remaining > 0) { "Update deadline expired" }
                        check(UpdatePolicy.allowedEndpoint(address, allowLatest = true)) { "Unexpected update destination" }
                        val active = (URL(address).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            instanceFollowRedirects = false
                            connectTimeout = remaining.toInt()
                            readTimeout = remaining.toInt()
                            setRequestProperty("User-Agent", "txtoi-android-update-check")
                        }
                        connection.set(active)
                        try {
                            ensureActive()
                            when (active.responseCode) {
                                HttpURLConnection.HTTP_OK -> release = checkNotNull(UpdatePolicy.release(address))
                                301, 302, 303, 307, 308 -> {
                                    val location = checkNotNull(active.getHeaderField("Location"))
                                    address = URI(address).resolve(location).toString()
                                }
                                else -> error("Update response rejected")
                            }
                        } finally {
                            connection.compareAndSet(active, null)
                            active.disconnect()
                        }
                    }
                    val value = checkNotNull(release) { "Too many update redirects" }
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            worker.cancel()
            connection.getAndSet(null)?.let { active ->
                CoroutineScope(Dispatchers.IO).launch { runCatching { active.disconnect() } }
            }
        }
    }
}
