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
    private var lastUncertain = false
    private var recoveryTarget: SystemRuleStore.Snapshot? = null
    private var recoveryBaseline: String? = null
    private var acceptedDraft: SystemRuleStore.Snapshot? = null
    @Volatile private var stagedRecord: PendingJournalRecord? = null
    private var pendingCleanup: PendingJournalRecord? = null
    private var operation = 0L
    private val queue = RuleSaveQueue<SystemRuleStore.Snapshot>(
        scope = scope,
        persist = { next -> persist(next) },
        read = { withContext(Dispatchers.IO) { SystemRuleStore.readGlobal(context) } },
        onError = { ModuleDiagnostics.fault(ModuleDiagnostics.Code.SAVE_FAILED) },
        isAuthoritative = { it.valid },
        keepDraftOnFailure = { lastUncertain },
    )
    val state = queue.state

    fun initialize(appContext: Context, snapshot: SystemRuleStore.Snapshot, expected: RuleSaveState<SystemRuleStore.Snapshot>?) {
        context = appContext.applicationContext as Application
        if (recoveryChecking.value) return
        queue.refresh(snapshot, expected)
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
                    if (state.value === observedState) queue.resolve(SystemRuleStore.decode(current.value), observedState)
                    withContext(Dispatchers.IO) { SystemRuleStore.repairBackup(context) }
                    return@launch
                }
                if (record == null) {
                    // This process has accepted no edit. A previous read fault must not
                    // turn an old page snapshot into a new write after the record disappears.
                    if (state.value === observedState) queue.resolve(SystemRuleStore.decode(current.value), observedState)
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
                        val actual = SystemRuleStore.decode(current.value)
                        if (state.value === observedState) { acceptedDraft = null; queue.resolve(actual, observedState) }
                        withContext(Dispatchers.IO) { SystemRuleStore.repairBackup(context) }
                    }
                    PendingRuleRecovery.Action.CONFLICT -> {
                        val cleared = journalMutex.withLock { withContext(Dispatchers.IO) { clearJournal(recovered.id) } }
                        if (!cleared) { pendingCleanup = recovered; queue.markUnresolved(observedState); return@launch }
                        ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_CONFLICT)
                        if (state.value === observedState) { acceptedDraft = null; queue.resolve(SystemRuleStore.decode(current.value), observedState) }
                    }
                    PendingRuleRecovery.Action.REPLAY -> {
                        val target = SystemRuleStore.decode(recovered.target)
                        if (target.valid && state.value === observedState) {
                            acceptedDraft = target
                            recoveryTarget = target
                            recoveryBaseline = current.value
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

    fun submit(snapshot: SystemRuleStore.Snapshot) {
        if (recoveryChecking.value || state.value?.verificationPending == true) return
        val encoded = try { snapshot.encode() } catch (_: IllegalArgumentException) {
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.RULES_LIMIT_EXCEEDED)
            return
        }
        val previousSnapshot = state.value?.value
        acceptedDraft = snapshot
        // Stage the latest draft independently of a slow Root call. Never do disk IO on the UI thread.
        stage = staging.submit {
            journalMutex.withLock {
                val read = readJournal()
                if (read === PendingJournalRead.Failed) return@withLock false
                val previous = (read as? PendingJournalRead.Found)?.record
                val current = if (previous == null) SystemRuleStore.readRawGlobal(context) else null
                val fallback = previousSnapshot?.takeIf { it.valid }
                if (previous == null && current?.valid != true && fallback == null) return@withLock false
                val baseline = if (previous != null) previous.baseline
                    else if (current?.valid == true) current.value
                    else if (fallback?.status == SystemRuleStore.ReadStatus.MISSING) null else fallback?.encode()
                val record = PendingJournalRecord(UUID.randomUUID().toString(), baseline, encoded, previous?.inFlight)
                stagedRecord = record
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
        lastUncertain = false
        if (!staged) { lastUncertain = true; ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED); return false }
        val prepared = journalMutex.withLock {
            withContext(Dispatchers.IO) {
                val record = (readJournal() as? PendingJournalRead.Found)?.record ?: return@withContext false
                writeJournal(record.copy(inFlight = next.encode()))
            }
        }
        if (!prepared) { lastUncertain = true; ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED); return false }
        val conditional = recoveryTarget === next
        val expected = recoveryBaseline
        if (conditional) { recoveryTarget = null; recoveryBaseline = null }
        // An unexpected exception after launch does not prove that the remote write stopped.
        lastUncertain = true
        val result = withContext(Dispatchers.IO) { SystemRuleStore.saveDetailed(context, next, conditional, expected) }
        if (result.verified && !result.backupCurrent) ModuleDiagnostics.event(ModuleDiagnostics.Code.BACKUP_FAILED, id, 0)
        lastUncertain = result.uncertain
        val encoded = next.encode()
        val settled = journalMutex.withLock {
            withContext(Dispatchers.IO) {
                val journal = readJournal()
                if (journal === PendingJournalRead.Failed) return@withContext false
                val record = (journal as? PendingJournalRead.Found)?.record
                if (result.verified && record != null) {
                    if (record.target == encoded) clearJournal(record.id)
                    else writeJournal(record.copy(baseline = encoded, inFlight = null))
                } else if (!result.uncertain && record?.target == encoded) {
                    clearJournal(record.id)
                } else !result.verified || record != null
            }
        }
        if (!settled) {
            lastUncertain = true
            ModuleDiagnostics.fault(ModuleDiagnostics.Code.JOURNAL_FAILED)
            return false
        }
        if ((result.verified || !result.uncertain) && acceptedDraft === next) acceptedDraft = null
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.SAVE_MS, elapsed)
        ModuleDiagnostics.event(if (result.verified) ModuleDiagnostics.Code.SAVE_VERIFIED else ModuleDiagnostics.Code.SAVE_FAILED, id, elapsed)
        return result.verified
    }

    private fun preferences() = context.getSharedPreferences(SystemRuleStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private fun journalStore() = PendingJournalStore(object : PendingJournalStorage {
        override fun readString(key: String): String? = preferences().getString(key, null)
        @SuppressLint("UseKtx") // KTX edit returns Unit; this path needs the actual commit receipt.
        override fun commit(values: Map<String, String?>): Boolean {
            val editor = preferences().edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            return editor.commit()
        }
    })
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
