package com.kirtan.companion

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Strings — interface text for code that has no Compose context.
 *
 * Screens read their text with `stringResource(R.string.…)`, which follows the
 * device language. But some sentences are born below the UI: a storage
 * failure, a community action's result, a route name from the audio stack.
 * Those layers are plain Kotlin and must stay so — the JVM unit tests run them
 * without Android — so they can't hold a Context.
 *
 * This object is the narrow bridge: [KirtanApplication] binds the app's
 * Resources at startup, and anything below the UI resolves a resource id
 * through it. Android picks `values-es/strings.xml` for a Spanish device and
 * falls back to `values/` per string, so a missing translation shows English.
 *
 * Unit tests [install] a [Source] that reads `values/strings.xml` directly, so
 * they keep asserting the real English sentences. With nothing bound at all it
 * returns a stable token, `#<id>[args]`, rather than throwing.
 */
object Strings {
    /** Where the words come from: Android resources in the app, the XML in tests. */
    interface Source {
        fun string(@StringRes id: Int, args: Array<out Any>): String
        fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any>): String
    }

    @Volatile
    private var source: Source? = null

    fun bind(resources: Resources) = install(object : Source {
        override fun string(id: Int, args: Array<out Any>) = resources.getString(id, *args)
        override fun plural(id: Int, count: Int, args: Array<out Any>) =
            resources.getQuantityString(id, count, *args)
    })

    fun install(source: Source?) {
        this.source = source
    }

    fun get(@StringRes id: Int, vararg args: Any): String =
        source?.string(id, args) ?: token(id, args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        source?.plural(id, count, args) ?: token(id, arrayOf(count, *args))

    private fun token(id: Int, args: Array<out Any>): String =
        if (args.isEmpty()) "#$id" else "#$id${args.toList()}"
}
