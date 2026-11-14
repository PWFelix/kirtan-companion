package com.kirtan.companion.data

import com.kirtan.companion.data.model.Beat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The built-in beat set — which is no longer only the one compiled into this APK.
 *
 * [BEATS] is the FALLBACK: what the app plays with no network, on a first launch
 * before the server answers, and against a server that returns something unusable.
 * The canonical set lives in the `shipped_beats` table, so the two people who
 * maintain the beats can correct a pattern and have it reach every installed app
 * on its next launch, without a rebuild or a reinstall. That is the whole reason
 * this object exists.
 *
 * ── WHY A GLOBAL RATHER THAN A PARAMETER ──
 * [Beat.isBuiltIn] is a property ON THE MODEL, read from list rows, the editor's
 * fork-or-edit decision and the transport alike. It cannot be handed the current
 * set as an argument without every one of those call sites threading a list they
 * otherwise have no use for. It already consulted a global constant
 * (`BUILT_IN_ID_SET`) for exactly this reason; the only change here is that the
 * constant became a value that can be replaced at runtime.
 *
 * A server-sourced set means a built-in id this build has never seen — a beat
 * promoted after the APK was compiled. Such a beat MUST still read as built in,
 * or `readOnly` goes false with it and the editor offers to overwrite a beat that
 * is not the user's to overwrite. Answering from the live set rather than the
 * compiled one is what makes that impossible.
 *
 * ── ONE WAY IN ──
 * [install] is the only writer, and it refuses an empty set. The emptiness check
 * belongs here rather than in each caller because the consequence of getting it
 * wrong is app-wide — no default beat, an empty Built-in section, nothing for the
 * transport to load — while the cost of the check is one comparison. A caller that
 * validated its rows already loses nothing by it.
 */
object ShippedBeats {

    /** The beats compiled into this build. Never empty; never replaced. */
    val compiled: List<Beat> = BEATS

    private val _effective = MutableStateFlow(compiled)

    /**
     * The set the app is currently treating as built in: [compiled] until a
     * usable server set arrives, then that. Never empty.
     */
    val effective: StateFlow<List<Beat>> = _effective.asStateFlow()

    /**
     * Adopt a fetched (or cached) set as the built-ins.
     *
     * The caller — [com.kirtan.companion.storage.ShippedBeatsClient] — owns
     * validation, and validation is ALL OR NOTHING: either every row became a
     * beat or the set is rejected wholesale and this is not called. Keeping the
     * two halves apart is deliberate, so the rules that decide what a valid row
     * is can be tested without a DataStore or a network in the way.
     */
    fun install(beats: List<Beat>) {
        // An empty built-in set would leave the app with no default beat and an
        // empty Built-in section — a worse failure than showing yesterday's set,
        // which is why "the server had nothing" reads as "keep what we have".
        if (beats.isEmpty()) return
        _effective.value = beats
    }

    /** True when [id] names one of the CURRENT built-ins, compiled or fetched. */
    fun isShippedId(id: String?): Boolean =
        id != null && _effective.value.any { it.id == id }

    /**
     * The distinct section headings of the live set, in first-appearance order —
     * which, for a fetched set, is `ordinal` order.
     *
     * NOT contiguous, and the Beats screen depends on that being fine: it takes
     * these headings and then FILTERS the list by each, so a beat sitting between
     * two others of a different heading still lands in its own section.
     */
    val groups: List<String> get() = _effective.value.mapNotNull { it.group }.distinct()

    /** The beat the app loads before the user chooses one. Never null. */
    val default: Beat get() = _effective.value.firstOrNull() ?: compiled.first()
}
