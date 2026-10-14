package io.github.selectionmenucontrol

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** Only explicit discard establishes even a missing Global as authoritative; no separate receipt. */
internal fun discardJournalStorage(preferences: SharedPreferences): PendingJournalStorage =
    object : PendingJournalStorage {
        override fun readString(key: String): String? = preferences.getString(key, null)
        @SuppressLint("UseKtx") // Both removal and the marker require one real commit receipt.
        override fun commit(values: Map<String, String?>): Boolean {
            val editor = preferences.edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            editor.putBoolean("global_established", true)
            return editor.commit()
        }
    }
