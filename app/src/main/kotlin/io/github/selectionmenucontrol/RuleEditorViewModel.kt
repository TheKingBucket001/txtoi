package io.github.selectionmenucontrol

import android.content.Context
import androidx.lifecycle.ViewModel

/** Keeps pending saves alive across page changes and Activity recreation. */
internal class RuleEditorViewModel : ViewModel() {
    val state = RuleSaveCoordinator.state
    val checking = RuleSaveCoordinator.checking
    private var notifiedFailureVersion = 0
    private var notifiedSuccessVersion = 0

    fun initialize(context: Context, snapshot: SystemRuleStore.Snapshot, expected: RuleSaveState<SystemRuleStore.Snapshot>?) {
        RuleSaveCoordinator.initialize(context, snapshot, expected)
    }

    fun save(snapshot: SystemRuleStore.Snapshot) {
        // A new edit supersedes a success bubble that has not yet been displayed.
        notifiedSuccessVersion = state.value?.successVersion ?: notifiedSuccessVersion
        RuleSaveCoordinator.submit(snapshot)
    }

    fun discardUnsaved() {
        notifiedSuccessVersion = state.value?.successVersion ?: notifiedSuccessVersion
        RuleSaveCoordinator.discardUnsaved()
    }

    fun consumeFailure(): Boolean {
        val version = state.value?.failureVersion ?: 0
        if (version <= notifiedFailureVersion) return false
        notifiedFailureVersion = version
        notifiedSuccessVersion = state.value?.successVersion ?: notifiedSuccessVersion
        return true
    }

    fun consumeSuccess(): Boolean {
        val latest = state.value ?: return false
        if (latest.pending || latest.failureVersion > notifiedFailureVersion) return false
        val version = latest.successVersion
        if (version <= notifiedSuccessVersion) return false
        notifiedSuccessVersion = version
        return true
    }
}
