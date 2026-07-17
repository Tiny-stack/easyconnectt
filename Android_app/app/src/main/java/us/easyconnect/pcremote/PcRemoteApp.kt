package us.easyconnect.pcremote

import android.app.Application
import us.easyconnect.pcremote.net.RemoteClient
import us.easyconnect.pcremote.settings.SettingsRepository

/**
 * Holds the single app-scoped [RemoteClient] so the connection outlives any one
 * Activity instance and is shared with [ConnectionService], which keeps the
 * process alive (and its socket connected) while the app is minimized.
 */
class PcRemoteApp : Application() {
    // Application context lets the client save pushed files to Downloads.
    val client: RemoteClient by lazy { RemoteClient(this) }

    /** Touchpad preferences, app-scoped so they survive Activity recreation. */
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
}
