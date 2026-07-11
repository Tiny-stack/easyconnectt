package us.easyconnect.pcremote.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Client connection states surfaced to the UI. */
sealed interface ConnState {
    data object Disconnected : ConnState
    data object Connecting : ConnState
    data class Connected(val info: PairInfo) : ConnState
    data object Denied : ConnState
    data class Failed(val reason: String) : ConnState
}

/**
 * Owns the TCP connection to the PC server. All socket IO runs on a private
 * IO scope; writes are serialised with a mutex so high-frequency mouse commands
 * and the occasional file transfer never interleave on the stream.
 */
class RemoteClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private var socket: Socket? = null
    private var out: OutputStream? = null

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    /** What the currently-connected server advertised in its handshake. */
    @Volatile var server: ServerHello = ServerHello(0, emptySet())
        private set

    /** Opens the socket and performs the token handshake. */
    suspend fun connect(info: PairInfo) {
        _state.value = ConnState.Connecting
        withContext(Dispatchers.IO) {
            try {
                closeQuietly()
                val s = Socket()
                s.tcpNoDelay = true
                // OS-level keepalive keeps the NAT mapping warm and detects a
                // dead peer while the app is backgrounded — without injecting any
                // bytes into the command stream (an app PING's PONG reply would be
                // misread later by sendFile as its FILEOK).
                s.keepAlive = true
                s.connect(InetSocketAddress(info.host, info.port), 5000)
                val o = s.getOutputStream()
                o.write((Protocol.hello(info.token) + "\n").toByteArray(StandardCharsets.UTF_8))
                o.flush()
                val reply = Protocol.readLine(s.getInputStream())
                // Any "OK…" line is success; parse the version/caps that follow so
                // a newer server advertising extra capabilities never looks denied.
                if (reply != null && reply.startsWith("OK")) {
                    server = Protocol.parseServerHello(reply)
                    socket = s
                    out = o
                    _state.value = ConnState.Connected(info)
                } else {
                    s.close()
                    _state.value = ConnState.Denied
                }
            } catch (e: Exception) {
                _state.value = ConnState.Failed(e.message ?: "connection failed")
            }
        }
    }

    /** Fire-and-forget control command. Drops the connection on write failure. */
    fun send(line: String) {
        val o = out ?: return
        scope.launch {
            writeLock.withLock {
                try {
                    o.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                    o.flush()
                } catch (e: Exception) {
                    _state.value = ConnState.Failed(e.message ?: "send failed")
                    closeQuietly()
                }
            }
        }
    }

    // Convenience wrappers.
    fun move(dx: Int, dy: Int) = send(Protocol.move(dx, dy))
    fun click(button: String) = send(Protocol.click(button))
    fun doubleClick(button: String) = send(Protocol.doubleClick(button))
    fun down(button: String) = send(Protocol.down(button))
    fun up(button: String) = send(Protocol.up(button))
    fun scroll(amount: Int) = send(Protocol.scroll(amount))
    fun scrollH(amount: Int) = send(Protocol.scrollH(amount))
    fun type(text: String) = send(Protocol.type(text))

    /**
     * Sends a file over the same connection and awaits the server's reply.
     * @return true if the server acknowledged with FILEOK.
     */
    suspend fun sendFile(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        writeLock.withLock {
            try {
                val safeName = java.net.URLEncoder.encode(name, "UTF-8")
                val o = s.getOutputStream()
                o.write("FILE ${bytes.size} $safeName\n".toByteArray(StandardCharsets.UTF_8))
                o.write(bytes)
                o.flush()
                val reply = Protocol.readLine(s.getInputStream())
                reply?.startsWith("FILEOK") == true
            } catch (e: Exception) {
                _state.value = ConnState.Failed(e.message ?: "file send failed")
                closeQuietly()
                false
            }
        }
    }

    fun disconnect() {
        closeQuietly()
        _state.value = ConnState.Disconnected
    }

    fun shutdown() {
        closeQuietly()
        scope.cancel()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
    }
}
