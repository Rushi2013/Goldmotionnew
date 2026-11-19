package ai.jarvis

import android.app.Application
import androidx.work.Configuration
import ai.jarvis.core.AppMode
import ai.jarvis.di.AppContainer

class JarvisApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // DEMO is the default truth until the user configures a live gateway.
        if (container.settings.settings.value.mode == AppMode.DEMO) {
            container.sessionManager.markDemo()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
