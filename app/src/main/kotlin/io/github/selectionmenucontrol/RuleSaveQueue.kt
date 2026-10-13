package io.github.selectionmenucontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RuleSaveState<T>(
    val value: T,
    val pending: Boolean = false,
    val failureVersion: Int = 0,
    val successVersion: Int = 0,
    val verificationPending: Boolean = false,
)

/** Confined to the UI dispatcher. Each accepted edit is written in submission order. */
internal class RuleSaveQueue<T>(
    private val scope: CoroutineScope,
    private val persist: suspend (T) -> Boolean,
    private val read: suspend () -> T,
    private val onError: (Exception) -> Unit = {},
    private val isAuthoritative: (T) -> Boolean = { true },
    private val keepDraftOnFailure: () -> Boolean = { false },
) {
    private val mutableState = MutableStateFlow<RuleSaveState<T>?>(null)
    val state: StateFlow<RuleSaveState<T>?> = mutableState.asStateFlow()
    private val pending = ArrayDeque<T>()
    private var running = false

    fun initialize(value: T) {
        check(!running)
        mutableState.value = RuleSaveState(
            value,
            failureVersion = state.value?.failureVersion ?: 0,
            successVersion = state.value?.successVersion ?: 0,
        )
    }

    fun refresh(value: T, expected: RuleSaveState<T>?) {
        // A load that started before another edit or write completion cannot replace it.
        if (state.value === expected && !running && state.value?.verificationPending != true) initialize(value)
    }
    fun resolve(value: T, expected: RuleSaveState<T>?) {
        if (state.value === expected && !running) initialize(value)
    }
    fun markUnresolved(expected: RuleSaveState<T>?) {
        if (state.value === expected && !running) mutableState.value = expected?.copy(verificationPending = true)
    }

    fun submit(value: T) {
        val current = checkNotNull(state.value)
        pending.addLast(value)
        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.QUEUE_DEPTH, pending.size.toLong())
        // Update the draft before doing IO; write completions never replace a newer edit.
        mutableState.value = current.copy(value = value, pending = true, verificationPending = false)
        if (running) return
        running = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (pending.isNotEmpty()) {
                    val next = pending.removeFirst()
                    val saved = try {
                        persist(next)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        onError(error)
                        false
                    }
                    if (!saved && keepDraftOnFailure()) {
                        // Later complete drafts are already staged; no new writer may race an uncertain one.
                        pending.clear()
                        val latest = checkNotNull(state.value)
                        mutableState.value = latest.copy(pending = false, verificationPending = true,
                            failureVersion = latest.failureVersion + 1)
                        break
                    }
                    if (pending.isNotEmpty()) continue
                    if (saved) {
                        val latest = checkNotNull(state.value)
                        mutableState.value = latest.copy(
                            pending = false,
                            successVersion = latest.successVersion + 1,
                            verificationPending = false,
                        )
                    } else {
                        // Even a failed verification may have written Global. Read its real value.
                        val actual = try { read() } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            onError(error)
                            null
                        }
                        // A new edit may arrive during the read; its complete draft takes precedence.
                        if (pending.isEmpty()) {
                            val latest = checkNotNull(state.value)
                            mutableState.value = latest.copy(
                                value = if (actual != null && isAuthoritative(actual) && !keepDraftOnFailure()) actual else latest.value,
                                pending = false,
                                failureVersion = latest.failureVersion + 1,
                                verificationPending = keepDraftOnFailure() || actual == null || !isAuthoritative(actual),
                            )
                        }
                    }
                }
            } finally {
                running = false
            }
        }
    }
}
