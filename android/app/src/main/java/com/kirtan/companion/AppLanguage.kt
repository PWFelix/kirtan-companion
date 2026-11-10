package com.kirtan.companion

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * AppLanguage — the language picked in Settings, overriding the phone's.
 *
 * `null` means "follow the system", which is the default and what most people
 * want. A tag ("en", "es") pins the app to that language.
 *
 * Android 13+ has this built in: [LocaleManager] stores the per-app locale,
 * recreates our activity, and the same choice shows under the system's
 * Settings › Apps › Language (see res/xml/locales_config.xml) — one source of
 * truth for both doors. Older phones have no such thing, so there the tag lives
 * in a SharedPreferences file and [wrap] applies it to each activity context
 * in attachBaseContext.
 *
 * Code below the UI reads text through [Strings], bound to Application
 * resources at startup; [MainActivity] rebinds it to its own (already
 * localised) configuration in onCreate so storage errors follow the choice too.
 */
object AppLanguage {
    /** Languages with a full translation, in picker order. Keep in step with locales_config.xml. */
    val SUPPORTED = listOf("en", "es")

    /** Each language named in itself, so whoever needs the picker can read it. */
    fun nativeName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
    }

    private const val PREFS = "kirtan-language"
    private const val KEY = "tag"

    fun get(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                .applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }

    /** Choose [tag] (null = system) and re-render [activity] in it. */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system persists it and recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** Pre-13 only: [base] with the chosen locale applied. A no-op when following the system. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = get(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
