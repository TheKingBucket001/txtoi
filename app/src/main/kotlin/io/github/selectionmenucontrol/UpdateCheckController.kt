package io.github.selectionmenucontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface UpdateCheckResult {
    data class Available(val update: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

internal data class UpdateUiState(
    val checking: Boolean = false,
    val available: UpdateInfo? = null,
    val notice: String? = null,
    val noticeVersion: Int = 0,
)

/** UI dispatcher only. One active request; cancelled generations never change newer UI state. */
internal class UpdateCheckController(
    private val scope: CoroutineScope,
    private val checker: suspend (manual: Boolean) -> UpdateCheckResult,
) {
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var manualRequested = false
    private var consumedNotice = 0
    private var generation = 0
    private var dismissedVersion: String? = null

    fun check(manual: Boolean) {
        if (manual) mutableState.value = mutableState.value.copy(notice = null)
        manualRequested = manualRequested || manual
        if (request?.isActive == true) return
        val currentGeneration = ++generation
        mutableState.value = mutableState.value.copy(checking = true)
        request = scope.launch {
            try {
                val result = checker(manual)
                if (generation != currentGeneration) return@launch
                val previous = mutableState.value
                mutableState.value = previous.copy(
                    available = (result as? UpdateCheckResult.Available)?.update
                        ?.takeUnless { !manualRequested && it.version == dismissedVersion },
                    notice = if (manualRequested) when (result) {
                        UpdateCheckResult.UpToDate -> "暂无更新"
                        UpdateCheckResult.Failed -> "检查更新失败，请稍后重试"
                        is UpdateCheckResult.Available -> null
                    } else previous.notice,
                    noticeVersion = previous.noticeVersion + if (manualRequested) 1 else 0,
                )
            } finally {
                if (generation == currentGeneration) {
                    manualRequested = false
                    mutableState.value = mutableState.value.copy(checking = false)
                }
            }
        }
    }

    fun cancel(automaticOnly: Boolean = false) {
        if (automaticOnly && manualRequested) return
        generation++
        request?.cancel()
        manualRequested = false
        mutableState.value = mutableState.value.copy(checking = false)
    }

    fun dismissUpdate() {
        dismissedVersion = state.value.available?.version
        mutableState.value = mutableState.value.copy(available = null)
    }

    fun consumeNotice(): String? {
        val current = state.value
        if (current.noticeVersion <= consumedNotice) return null
        consumedNotice = current.noticeVersion
        if (current.notice != null) mutableState.value = current.copy(notice = null)
        return current.notice
    }
}
