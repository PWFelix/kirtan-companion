package com.kirtan.companion.data

/**
 * The one length limit for anything a person names: beats and lists.
 *
 * 255 is generous for a name and matches the web and iOS apps, so a beat named
 * on one device never arrives somewhere it can't be saved. The editor's field
 * stops typing at the limit; [clampName] is the backstop for names that arrive
 * any other way (imports, the " (2)" de-duplication suffix).
 */
object Names {
    const val MAX_NAME_LEN = 255

    /**
     * Cut [name] to at most [max] UTF-16 units without leaving half of a
     * surrogate pair (half an emoji, say) dangling at the end.
     */
    fun clampName(name: String, max: Int = MAX_NAME_LEN): String {
        if (name.length <= max) return name
        val cut = name.take(max)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}
