package io.github.selectionmenucontrol

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps pending saves alive across page changes and Activity recreation. */
internal class RuleEditorViewModel : ViewModel() {
    private lateinit var appContext: Context
    private val queue = RuleSaveQueue<SystemRuleStore.Snapshot>(
        scope = viewModelScope,
        persist = { next -> withContext(Dispatchers.IO) { SystemRuleStore.save(appContext, next) } },
        read = { withContext(Dispatchers.IO) { SystemRuleStore.readApp(appContext) } },
        onError = { Log.e("SelectionMenuControl", "Cannot save menu rules", it) },
    )
    val state = queue.state
    private var notifiedFailureVersion = 0
    private var notifiedSuccessVersion = 0

    fun initialize(context: Context, snapshot: SystemRuleStore.Snapshot, expected: RuleSaveState<SystemRuleStore.Snapshot>?) {
        appContext = context.applicationContext
        queue.refresh(snapshot, expected)
    }

    fun save(snapshot: SystemRuleStore.Snapshot) {
        // A new edit supersedes a success bubble that has not yet been displayed.
        notifiedSuccessVersion = state.value?.successVersion ?: notifiedSuccessVersion
        queue.submit(snapshot)
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
