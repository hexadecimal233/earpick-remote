package moe.hexzii.earpick

import android.app.Application
import android.content.Context
import moe.hexzii.earpick.capture.SnapshotStore
import moe.hexzii.earpick.data.AppSettings

class EarpickApp : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}

/** Constructor injection by hand: this app is small enough that a DI framework is overhead. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings = AppSettings(appContext)
    val snapshots = SnapshotStore(appContext.contentResolver)
}
