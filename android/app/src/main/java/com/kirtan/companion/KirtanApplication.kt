package com.kirtan.companion

import android.app.Application
import android.content.Context

/**
 * Process-wide singletons live here, hung off the Application so there is one
 * of each per process and no DI framework to learn.
 *
 * Deliberately tiny: [AppContainer] decides what is genuinely process-scoped.
 * The audio engine is held there, but its output DEVICE is opened and released
 * by [com.kirtan.companion.playback.PlaybackService] — see the note in
 * AppContainer for why that split matters.
 */
class KirtanApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience for the service and activity, which both need the graph. */
val Context.container: AppContainer
    get() = (applicationContext as KirtanApplication).container
