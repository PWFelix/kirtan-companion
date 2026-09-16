package com.kirtan.companion.data

/**
 * Turning an inbound Android link into a share payload, EXACTLY ONCE.
 *
 * The web app reads its share link at module scope and — this is the important
 * half — clears `location.hash` BEFORE decoding. `shareCodec.js` explains why:
 * a hostile payload that somehow got past the decoder and wedged the app would
 * otherwise re-wedge it on every refresh, because the payload lives in the URL.
 * Clearing first means a bad link gets exactly one chance and is then gone.
 *
 * Android has the same hazard with a different mechanism. An `Intent` is
 * redelivered: `onNewIntent` fires on every re-tap of the same notification or
 * link, and a `singleTask` activity replays its launching intent across a
 * process restart. So "read the intent in `onCreate`" is not enough — without an
 * explicit consume-and-clear, one poisoned link wedges the app on every launch,
 * permanently. [ShareIntake.consume] is that clear: it returns the payload and
 * drops the reference, so a second read gets nothing.
 *
 * This file is split in two on purpose:
 *  - [ShareIntake] is PURE and holds no Android types, so the link grammar and
 *    the once-only rule are ordinary JVM unit tests.
 *  - [shareTextFrom] is the thin, untestable edge that pulls a string out of an
 *    `Intent`. It does no parsing, so nothing interesting lives in it.
 */

/**
 * The accepted link forms, in the order they are tried.
 *
 * Three different shapes arrive here and all must work, because links outlive
 * the client that made them: the web app's `#b=<code>` fragment (already in
 * circulation), this app's own `kirtan://beat?c=<code>` deep link, and a bare
 * code pasted into the import field. A `?c=` query parameter is used rather than
 * a fragment for the native scheme because a fragment survives neither
 * `Intent.getData()` round-tripping on every launcher nor the Play Store's
 * redirect handling, whereas a query parameter does.
 */
object ShareIntake {

    /** The query parameter our own deep links carry. */
    const val PARAM_CODE = "c"

    /** The fragment marker the web app uses. */
    const val FRAGMENT_MARKER = "#b="

    private var pending: ShareCodec.SharePayload? = null

    /** Is a decoded payload waiting to be collected? */
    val hasPending: Boolean get() = pending != null

    /**
     * Decode [text] and hold the result.
     *
     * A link that carries a `#b=` or `?c=` marker but decodes to nothing is held
     * as [ShareCodec.SharePayload.Invalid] rather than dropped, so the UI can say
     * "that link didn't work" instead of silently ignoring a tap. Text with no
     * marker at all is not a share link and is ignored — that is how an ordinary
     * launch intent avoids producing a spurious error.
     *
     * Never throws; [ShareCodec.decodeShare] already guarantees that.
     */
    fun offer(text: String?) {
        val code = codeFrom(text) ?: return
        val decoded = ShareCodec.decodeShare(code)
        pending = decoded ?: ShareCodec.SharePayload.Invalid
    }

    /**
     * Take the pending payload and clear it. The SECOND call returns null — that
     * is the whole point, and the property the web app gets by clearing the hash.
     */
    fun consume(): ShareCodec.SharePayload? {
        val payload = pending
        pending = null
        return payload
    }

    /** Drop anything pending without reporting it — e.g. the user dismissed it. */
    fun clear() {
        pending = null
    }

    /**
     * Extract a share code from a link, a pasted URL, or a bare code.
     *
     * Returns null when [text] carries no share code, which is what distinguishes
     * "an ordinary launch" from "a shared beat arrived".
     */
    fun codeFrom(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        // The web app's fragment form. `lastIndexOf` so a link whose own query
        // happens to contain the marker still yields the intended payload — the
        // same rule ShareCodec.codeFromInput applies.
        val fragmentAt = trimmed.lastIndexOf(FRAGMENT_MARKER)
        if (fragmentAt != -1) return trimmed.substring(fragmentAt + FRAGMENT_MARKER.length).ifBlank { null }

        // Our own deep link's query parameter.
        if (trimmed.contains("://")) {
            val value = queryParam(trimmed, PARAM_CODE)
            if (!value.isNullOrBlank()) return value
        }

        // A bare code pasted into the import field: accept it only if it is
        // shaped like one, so an arbitrary string doesn't become an "invalid
        // share link" error.
        return if (BARE_CODE.matches(trimmed)) trimmed else null
    }

    private val BARE_CODE = Regex("^[A-Za-z0-9_-]{8,}$")

    /**
     * Read one query parameter without constructing an [android.net.Uri], so this
     * stays a pure function testable on the JVM. Only the first `?` starts the
     * query and only `&` separates pairs, which is all a share link needs.
     */
    private fun queryParam(url: String, name: String): String? {
        val question = url.indexOf('?')
        if (question == -1) return null
        val query = url.substring(question + 1).substringBefore('#')
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == name) return pair.substring(eq + 1)
        }
        return null
    }
}

/**
 * The thin Android edge: pull whatever text an inbound [android.content.Intent]
 * carries that might be a share link.
 *
 * Deliberately dumb. All the grammar lives in [ShareIntake.codeFrom], which is
 * pure; this only knows the two places Android puts a link — `intent.data` for a
 * VIEW intent, and `EXTRA_TEXT` for a plain share-sheet "send to Kirtan
 * Companion". Both are tried because a user who long-presses a link in a chat app
 * gets the first, and a user who hits Share inside the web app gets the second.
 */
fun shareTextFrom(intent: android.content.Intent?): String? {
    if (intent == null) return null

    val data = intent.dataString
    if (!data.isNullOrBlank()) return data

    val extra = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)
    if (!extra.isNullOrBlank()) return extra.toString()

    return null
}
