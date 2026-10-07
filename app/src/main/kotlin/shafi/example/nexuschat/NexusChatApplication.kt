package shafi.example.nexuschat

import android.app.Application

/** Owns the composition root for the whole process. */
class NexusChatApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
