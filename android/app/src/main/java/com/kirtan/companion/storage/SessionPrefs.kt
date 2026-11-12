package com.kirtan.companion.storage

import android.content.Context
import com.kirtan.companion.data.restoredBpm

/**
 * Where you left off: the last loaded beat and its tempo, so reopening the app
 * lands on the beat you were playing.
 *
 * SharedPreferences rather than the DataStore, like [PrivateTags]: it reads
 * SYNCHRONOUSLY, so both ViewModels can seed their first state from it in init
 * without racing the library's async load. Two tiny values; a lost one costs a
 * tap.
 *
 * The tempo comes back capped at 200 ([restoredBpm]): every launch starts
 * outside Brahmaloka time, which has to be earned again.
 */
class SessionPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val beatId: String? get() = prefs.getString(KEY_BEAT, null)

    val bpm: Int? get() = prefs.getInt(KEY_BPM, -1).takeIf { it > 0 }?.let(::restoredBpm)

    fun saveBeat(id: String) = prefs.edit().putString(KEY_BEAT, id).apply()

    fun saveBpm(bpm: Int) = prefs.edit().putInt(KEY_BPM, bpm).apply()

    private companion object {
        const val PREFS = "kirtan_session"
        const val KEY_BEAT = "beatId"
        const val KEY_BPM = "bpm"
    }
}
