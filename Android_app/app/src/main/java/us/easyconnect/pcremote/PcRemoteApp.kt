package us.easyconnect.pcremote

import android.app.Application
import us.easyconnect.pcremote.net.RemoteClient

/**
 * Holds the single app-scoped [RemoteClient] so the connection outlives any one
 * Activity instance and is shared with [ConnectionService], which keeps the
 * process alive (and its socket connected) while the app is minimized.
 */
class PcRemoteApp : Application() {
    val client: RemoteClient by lazy { RemoteClient() }
}
