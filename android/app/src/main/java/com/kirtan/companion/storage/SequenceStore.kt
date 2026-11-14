package com.kirtan.companion.storage

import android.content.Context
import com.kirtan.companion.data.BeatSequence
import com.kirtan.companion.data.Sequences
import com.kirtan.companion.data.WorkingSequence

/**
 * Saved sequences and Home's working one, on this device. Local only for now:
 * a sequence refers to beats by id, and syncing it is a later step once the
 * shape has settled.
 */
class SequenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("kirtan_sequences", Context.MODE_PRIVATE)

    fun loadSaved(): List<BeatSequence> = Sequences.decodeSaved(prefs.getString(SAVED, null))
    fun saveSaved(list: List<BeatSequence>) = prefs.edit().putString(SAVED, Sequences.encodeSaved(list)).apply()

    fun loadWorking(): WorkingSequence = WorkingSequence.decode(prefs.getString(WORKING, null))
    fun saveWorking(w: WorkingSequence) = prefs.edit().putString(WORKING, w.encode()).apply()

    private companion object {
        const val SAVED = "saved"
        const val WORKING = "working"
    }
}
