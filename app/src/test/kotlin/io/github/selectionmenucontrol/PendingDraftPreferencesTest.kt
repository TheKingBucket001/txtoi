package io.github.selectionmenucontrol

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PendingDraftPreferencesTest {
    private val empty = RuleConfig(emptySet(), emptyList()).encode()
    private val record = PendingJournalRecord("pending", empty, empty, empty)

    private class Preferences {
        val memory = mutableMapOf<String, Any>()
        val disk = mutableMapOf<String, Any>()
        var failures = 0
        var editors = 0
        var commits = 0
        val batches = mutableListOf<Map<String, Any?>>()
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> memory[args!![0]] ?: args[1]
                "edit" -> {
                    editors++
                    val changes = mutableMapOf<String, Any?>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { editor, call, values ->
                        when (call.name) {
                            "putString", "putBoolean" -> {
                                changes[values!![0] as String] = values[1]
                                editor
                            }
                            "commit" -> {
                                commits++
                                batches.add(changes.toMap())
                                changes.forEach { (key, value) ->
                                    if (value == null) memory.remove(key) else memory[key] = value
                                }
                                if (failures > 0) { failures--; false }
                                else { disk.clear(); disk.putAll(memory); true }
                            }
                            else -> error("Unexpected Editor method ${call.name}")
                        }
                    }
                }
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
    }

    private fun prepare(preferences: Preferences) {
        preferences.memory.putAll(mapOf("pending_id" to record.id, "pending_baseline" to empty,
            "pending_target" to empty, "pending_inflight" to empty, "backup" to "keep", "global_established" to false))
        preferences.disk.putAll(preferences.memory)
    }

    @Test fun explicitDiscardRemovesPendingAndEstablishesMissingGlobalInOneReceipt() {
        val preferences = Preferences()
        prepare(preferences)
        assertTrue(discardPendingJournal(discardJournalStorage(preferences.instance), PendingJournalRead.Found(record)))
        assertEquals(1, preferences.editors)
        assertEquals(1, preferences.commits)
        assertEquals(mapOf("backup" to "keep", "global_established" to true), preferences.disk)
        assertEquals(setOf("pending_id", "pending_baseline", "pending_target", "pending_inflight", "global_established"),
            preferences.batches.single().keys)
    }

    @Test fun failedCombinedReceiptKeepsDiskPendingAndDoesNotReportDiscardComplete() {
        val preferences = Preferences()
        prepare(preferences)
        preferences.failures = 2
        assertFalse(discardPendingJournal(discardJournalStorage(preferences.instance), PendingJournalRead.Found(record)))
        assertEquals(false, preferences.disk["global_established"])
        assertEquals(record.id, preferences.disk["pending_id"])
        assertEquals(true, preferences.memory["global_established"])
        assertFalse(preferences.memory.containsKey("pending_id"))
        assertEquals(2, preferences.commits)
        assertTrue(discardPendingJournal(discardJournalStorage(preferences.instance), PendingJournalRead.Missing))
        assertEquals(mapOf("backup" to "keep", "global_established" to true), preferences.disk)
        assertEquals(3, preferences.commits)
    }

    @Test fun aNewerRecordPreventsBothMarkerAndPendingMutation() {
        val preferences = Preferences()
        prepare(preferences)
        preferences.memory["pending_id"] = "newer"
        assertFalse(discardPendingJournal(discardJournalStorage(preferences.instance), PendingJournalRead.Found(record)))
        assertEquals(0, preferences.editors)
        assertEquals(0, preferences.commits)
        assertEquals(false, preferences.memory["global_established"])
    }
}
