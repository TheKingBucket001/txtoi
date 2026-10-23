package io.github.selectionmenucontrol

import android.content.Context
import android.app.Application
import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Process lifetime, not Activity lifetime. IO and Root writes remain ordered and bounded. */
internal object RuleSaveCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val journalMutex = Mutex()
    private val staging = OrderedStageWriter(scope)
    private lateinit var context: Application
    private var stage: Deferred<Boolean>? = null
    private val recoveryChecking = MutableStateFlow(false)
    val checking = recoveryChecking.asStateFlow()
    private var requiresReconciliation = false
    @Volatile private var confirmedGlobal: SystemRuleStore.RawValue? = null
    private var acceptedDraft: SystemRuleStore.Snapshot? = null
    @Volatile private var stagedRecord: PendingJournalRecord? = null
    private var pendingCleanup: PendingJournalRecord? = null
    private var discardRequested = false
    private var operation = 0L
    private val queue = RuleSaveQueue<SystemRuleStore.Snapshot>(
        scope = scope,
        persist = { next -> persist(next) },
        read = { withContext(Dispatchers.IO) { SystemRuleStore.readGlobal(context) } },
        onError = { ModuleDiagnostics.fault(ModuleDiagnostics.Code.SAVE_FAILED) },
        isAuthoritative = { it.valid },
        keepDraftOnFailure = { requiresReconciliation },
    )
    val state = queue.state

    fun initialize(appContext: Context, snapshot: SystemRuleStore.Snapshot, expected: RuleSaveState<SystemRuleStore.Snapshot>?) {
        context = appContext.applicationContext as Application
        if (discardRequested) { discardUnsaved(); return }
        if (recoveryChecking.value) return
        if (queue.refresh(snapshot, expected)) confirmedGlobal = snapshot.sourceGlobal
        if (state.value?.pending == true) return
        recoveryChecking.value = true
        val observedState = state.value
        val observedDraft = acceptedDraft
        val latestStage = stage
        scope.launch {
            try {
                // A later accepted draft may still be staging after an earlier Root timeout.
                // Wait for that writer before deciding which durable target to replay.
                latestStage?.await()
                if (state.value !== observedState || state.value?.pending == true) return@launch
                val diskJournal = journalMutex.withLock { withContext(Dispatchers.IO) { readJournal() } }
                val journal = pendingRecoveryRecord(diskJournal, stagedRecord, observedDraft?.encode())
                if (journal === PendingJournalRead.Failed) {
                    queue.markUnresolved(observedState)
                    return@launch
                }
                if (journal === PendingJournalRead.Missing && observedState?.verificationPending != true) return@launch
                val record = (journal as? PendingJournalRead.Found)?.record
                val current = withContext(Dispatchers.IO) { SystemRuleStore.readRawGlobal(context) }
                if (state.value !== observedState || state.value?.pending == true) return@launch
                if (!current.valid) {
                    ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                    queue.markUnresolved(observedState)
                    return@launch
                }
                // A missing journal after a failed commit is not evidence that remote IO stopped.
                if (record?.inFlight != null || observedState?.verificationPending == true) {
                    val barrier = withContext(Dispatchers.IO) {
                        RootAccess.verifyGlobalStableResult(SystemRuleStore.SETTING_KEY, current.value)
                    }
                    if (!barrier.success) {
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                        queue.markUnresolved(observedState)
                        return@launch
                    }
                    if (state.value !== observedState || state.value?.pending == true) return@launch
                }
                val cleanup = pendingCleanup
                if (cleanup != null) {
                    // A previous clear changed preferences memory but did not reach disk.
                    // Retire that transaction; do not reconsider its target for replay.
                    if (record != null && record.id != cleanup.id) {
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_CONFLICT)
                        queue.markUnresolved(observedState)
                        return@launch
                    }
                    val cleared = journalMutex.withLock { withContext(Dispatchers.IO) {
                        finishPendingJournalCleanup(journalStore(), cleanup)
                    } }
                    if (!cleared) {
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                        queue.markUnresolved(observedState)
                        return@launch
                    }
                    pendingCleanup = null
                    ModuleDiagnostics.recover(ModuleDiagnostics.Code.JOURNAL_FAILED)
                    stagedRecord = null
                    acceptedDraft = null
                    resolveCurrent(current.value, observedState)
                    withContext(Dispatchers.IO) { SystemRuleStore.repairBackup(context) }
                    return@launch
                }
                if (record == null) {
                    // This process has accepted no edit. A previous read fault must not
                    // turn an old page snapshot into a new write after the record disappears.
                    resolveCurrent(current.value, observedState)
                    return@launch
                }
                if (observedDraft != null) {
                    // commit=false may leave either the old or new in-memory preferences.
                    // Restage the latest accepted target using its original baseline before Root IO.
                    val retry = checkNotNull(record)
                    val written = journalMutex.withLock { withContext(Dispatchers.IO) { writeJournal(retry) } }
                    if (!written || state.value !== observedState) {
                        queue.markUnresolved(observedState)
                        return@launch
                    }
                }
                val recovered = checkNotNull(record)
                when (PendingRuleRecovery.decide(recovered.baseline, recovered.target, recovered.inFlight, current.value, current.valid)) {
                    PendingRuleRecovery.Action.WAIT -> {
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                        queue.markUnresolved(observedState)
                    }
                    PendingRuleRecovery.Action.COMPLETE -> {
                        val cleared = journalMutex.withLock { withContext(Dispatchers.IO) { clearJournal(recovered.id) } }
                        if (!cleared) { pendingCleanup = recovered; queue.markUnresolved(observedState); return@launch }
                        if (state.value === observedState) { acceptedDraft = null; resolveCurrent(current.value, observedState) }
                        withContext(Dispatchers.IO) { SystemRuleStore.repairBackup(context) }
                    }
                    PendingRuleRecovery.Action.CONFLICT -> {
                        val cleared = journalMutex.withLock { withContext(Dispatchers.IO) { clearJournal(recovered.id) } }
                        if (!cleared) { pendingCleanup = recovered; queue.markUnresolved(observedState); return@launch }
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_CONFLICT)
                        if (state.value === observedState) { acceptedDraft = null; resolveCurrent(current.value, observedState) }
                        // The external value is authoritative. Keep the private backup aligned
                        // with that value before declaring reconciliation complete.
                        withContext(Dispatchers.IO) { SystemRuleStore.repairBackup(context) }
                    }
                    PendingRuleRecovery.Action.REPLAY -> {
                        val target = SystemRuleStore.decode(recovered.target)
                        if (target.valid && state.value === observedState) {
                            // A fenced, known in-flight value is now our acknowledged baseline.
                            // Persist that rebase before preparing the next conditional writer.
                            val rebased = recovered.copy(baseline = current.value, inFlight = null)
                            val written = journalMutex.withLock { withContext(Dispatchers.IO) { writeJournal(rebased) } }
                            if (!written || state.value !== observedState) {
                                queue.markUnresolved(observedState)
                                return@launch
                            }
                            confirmedGlobal = current
                            acceptedDraft = target
                            stage = null
                            queue.submit(target)
                        }
                    }
                    null -> Unit
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                queue.markUnresolved(observedState)
            } finally { recoveryChecking.value = false }
        }
    }

    /** Explicit user confirmation only. This operation never writes Global or replays the draft. */
    fun discardUnsaved() {
        if (recoveryChecking.value) return
        val observedState = state.value?.takeIf { !it.pending && it.verificationPending } ?: return
        val latestStage = stage
        discardRequested = true
        recoveryChecking.value = true
        scope.launch {
            try {
                // Join accepts a completed failed stage too; abandoning it needs no write receipt.
                latestStage?.join()
                if (state.value !== observedState || state.value?.pending == true) return@launch
                val result = journalMutex.withLock {
                    withContext(Dispatchers.IO) {
                        discardPendingDraft(
                            readGlobal = {
                                val raw = SystemRuleStore.readRawGlobal(context)
                                DiscardGlobalValue(raw.value, raw.valid)
                            },
                            readJournal = ::readJournal,
                            verifyStable = { value ->
                                RootAccess.verifyGlobalStableResult(SystemRuleStore.SETTING_KEY, value).success
                            },
                            clearJournal = { expected ->
                                discardPendingJournal(discardJournalStorage(preferences()), expected)
                            },
                        )
                    }
                }
                if (result !is DraftDiscardResult.Complete || state.value !== observedState) {
                    ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                    queue.markUnresolved(observedState)
                    return@launch
                }
                discardRequested = false
                acceptedDraft = null
                stagedRecord = null
                pendingCleanup = null
                stage = null
                requiresReconciliation = false
                resolveCurrent(result.current, observedState)
                ModuleDiagnostics.recover(ModuleDiagnostics.Code.JOURNAL_FAILED)
                withContext(Dispatchers.IO) { SystemRuleStore.repairBackup(context) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
                queue.markUnresolved(observedState)
            } finally { recoveryChecking.value = false }
        }
    }

    fun submit(snapshot: SystemRuleStore.Snapshot) {
        if (recoveryChecking.value || state.value?.verificationPending == true) return
        val encoded = try { snapshot.encode() } catch (_: IllegalArgumentException) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_LIMIT_EXCEEDED)
            return
        }
        // A definite-failure readback may have been adopted by the FIFO since the last save.
        state.value?.value?.sourceGlobal?.let { confirmedGlobal = it }
        acceptedDraft = snapshot
        // Stage the latest draft independently of a slow Root call. Never do disk IO on the UI thread.
        stage = staging.submit {
            journalMutex.withLock {
                val read = readJournal()
                if (read === PendingJournalRead.Failed) return@withLock false
                val previous = (read as? PendingJournalRead.Found)?.record
                val current = if (previous == null) SystemRuleStore.readRawGlobal(context) else null
                val observed = confirmedGlobal
                if (previous == null && stagedRecord == null && observed?.valid != true) return@withLock false
                // Never rebase a stale page draft onto an external value read at submission time.
                val baseline = if (previous != null) previous.baseline else observed?.value
                val record = nextPendingJournalRecord(UUID.randomUUID().toString(), previous, stagedRecord, baseline, encoded)
                stagedRecord = record
                // Remembering a baseline is not a receipt: the first durable stage still needs a readable Global.
                if (previous == null && current?.valid != true) return@withLock false
                writeJournal(record)
            }
        }
        queue.submit(snapshot)
    }

    private suspend fun persist(next: SystemRuleStore.Snapshot): Boolean {
        val id = ++operation
        val started = android.os.SystemClock.elapsedRealtime()
        ModuleDiagnostics.event(ModuleDiagnostics.Code.SAVE_STARTED, id, 0)
        val staged = stage?.await() ?: true
        requiresReconciliation = false
        if (!staged) { requiresReconciliation = true; ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED); return false }
        val prepared = journalMutex.withLock {
            withContext(Dispatchers.IO) {
                val record = (readJournal() as? PendingJournalRead.Found)?.record ?: return@withContext null
                val prepared = record.copy(inFlight = next.encode())
                if (writeJournal(prepared)) prepared else null
            }
        }
        if (prepared == null) { requiresReconciliation = true; ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED); return false }
        // An unexpected exception after launch does not prove that the remote write stopped.
        requiresReconciliation = true
        val result = withContext(Dispatchers.IO) { SystemRuleStore.saveDetailed(context, next, prepared.baseline) }
        if (result.verified && !result.backupCurrent) ModuleDiagnostics.event(ModuleDiagnostics.Code.BACKUP_FAILED, id, 0)
        requiresReconciliation = result.requiresReconciliation()
        val encoded = next.encode()
        val settled = journalMutex.withLock {
            // Read edit identity on the UI dispatcher while staging is excluded by this lock.
            // An edit accepted during the following IO will stage after this settlement.
            val latestAcceptedEdit = acceptedDraft === next
            val saved = withContext(Dispatchers.IO) {
                settlePendingRuleWrite(readJournal(), encoded, latestAcceptedEdit,
                    result.verified, requiresReconciliation, ::writeJournal, ::clearJournal)
            }
            if (saved && result.verified) confirmedGlobal = SystemRuleStore.RawValue(encoded, true)
            saved
        }
        if (!settled) {
            requiresReconciliation = true
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
            return false
        }
        if ((result.verified || !requiresReconciliation) && acceptedDraft === next) acceptedDraft = null
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.SAVE_MS, elapsed)
        if (result.verified) ModuleDiagnostics.recover(ModuleDiagnostics.Code.SAVE_FAILED)
        ModuleDiagnostics.event(if (result.verified) ModuleDiagnostics.Code.SAVE_VERIFIED else ModuleDiagnostics.Code.SAVE_FAILED, id, elapsed)
        return result.verified
    }

    private fun resolveCurrent(raw: String?, expected: RuleSaveState<SystemRuleStore.Snapshot>?) {
        val actual = SystemRuleStore.decodeGlobal(raw)
        if (queue.resolve(actual, expected)) confirmedGlobal = actual.sourceGlobal
    }

    private fun preferences() = context.getSharedPreferences(SystemRuleStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private fun journalStorage() = object : PendingJournalStorage {
        override fun readString(key: String): String? = preferences().getString(key, null)
        @SuppressLint("UseKtx") // KTX edit returns Unit; this path needs the actual commit receipt.
        override fun commit(values: Map<String, String?>): Boolean {
            val editor = preferences().edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            return editor.commit()
        }
    }
    private fun journalStore() = PendingJournalStore(journalStorage())
    private fun readJournal(): PendingJournalRead {
        val result = journalStore().read()
        if (result === PendingJournalRead.Failed) ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
        return result
    }
    private fun writeJournal(record: PendingJournalRecord): Boolean {
        stagedRecord = record
        val saved = journalStore().write(record)
        if (!saved) ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
        else ModuleDiagnostics.recover(ModuleDiagnostics.Code.JOURNAL_FAILED)
        return saved
    }
    private fun clearJournal(expectedId: String): Boolean {
        val saved = journalStore().clear(expectedId)
        if (!saved) ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
        else stagedRecord = null
        return saved
    }
}
