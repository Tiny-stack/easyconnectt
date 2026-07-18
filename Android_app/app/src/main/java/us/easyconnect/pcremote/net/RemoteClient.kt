package us.easyconnect.pcremote.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
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
 * Owns the TCP connection to the PC server. All socket IO runs on a private IO
 * scope. Control commands are queued on [outbox] and written by one coroutine,
 * which keeps them strictly ordered (see that field for why that matters).
 *
 * A single background reader owns the control socket's input stream and handles
 * "PUSH <size> <name>" + bytes — saving to Downloads and replying PUSHOK/PUSHERR.
 * That's what lets the PC push files to the phone at any time (server-initiated),
 * rather than only replying to our requests.
 *
 * Uploads ([sendFile]) deliberately run on their own short-lived data socket, so
 * a large transfer never blocks mouse/keyboard on this connection.
 */
class RemoteClient(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // @Volatile: written on the connect coroutine, read by the reader/writer/
    // heartbeat coroutines on other threads.
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null

    /**
     * Ordered outbox for control commands, drained by a single writer coroutine.
     *
     * The previous design did `scope.launch { writeLock.withLock { write() } }`
     * per command. Dispatchers.IO is a multi-threaded pool, so two launches could
     * reach the lock in either order — mouse deltas could be written out of order
     * and the cursor would visibly stutter. A channel plus one writer guarantees
     * commands hit the socket in exactly the order the gestures produced them,
     * with no per-event coroutine allocation.
     */
    @Volatile private var outbox: Channel<String>? = null

    // Coordinates of the paired PC, kept so sendFile can dial a data connection.
    @Volatile private var pairInfo: PairInfo? = null

    // When we last heard anything from the PC — the heartbeat's liveness signal.
    @Volatile private var lastHeardNanos: Long = 0L

    // Keeps Wi-Fi out of power-save while connected. Without it the radio parks
    // between beacons and delivers our high-rate mouse stream in bursts (measured:
    // stalls up to ~1 s during a smooth drag), which feels like intermittent lag.
    private var wifiLock: WifiManager.WifiLock? = null

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    // Names of files the PC pushed to us (for a UI toast). replay=0, buffered.
    private val _received = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val received: SharedFlow<String> = _received.asSharedFlow()

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
                s.keepAlive = true
                s.connect(InetSocketAddress(info.host, info.port), 5000)
                val o = s.getOutputStream()
                o.write((Protocol.hello(info.token) + "\n").toByteArray(StandardCharsets.UTF_8))
                o.flush()
                val reply = Protocol.readLine(s.getInputStream())
                if (reply != null && reply.startsWith("OK")) {
                    server = Protocol.parseServerHello(reply)
                    socket = s
                    out = o
                    pairInfo = info // sendFile dials its own data connection with this
                    // Fresh outbox per connection; the old one is closed in closeQuietly.
                    val box = Channel<String>(Channel.UNLIMITED)
                    outbox = box
                    startWriter(o, box)
                    acquireWifiLock() // keep Wi-Fi out of power-save (see field)
                    lastHeardNanos = System.nanoTime() // the OK we just read counts
                    _state.value = ConnState.Connected(info)
                    startReader(s, s.getInputStream()) // handle server-initiated PUSH
                    startHeartbeat(box)                // prove the link stays alive
                } else {
                    s.close()
                    _state.value = ConnState.Denied
                }
            } catch (e: Exception) {
                _state.value = ConnState.Failed(e.message ?: "connection failed")
            }
        }
    }

    /**
     * Fire-and-forget control command. Queued, never blocks the caller.
     *
     * If there's no live outbox the command can't go anywhere — say so instead of
     * dropping it silently. A silent drop is what made "connected, but the cursor
     * is dead" so baffling to diagnose: the UI kept claiming Connected while every
     * command vanished. Surfacing it means the user sees "disconnected" and can
     * just re-pair.
     */
    fun send(line: String) {
        val box = outbox
        if (box == null || box.trySend(line).isFailure) {
            markDisconnected("connection lost")
        }
    }

    /**
     * Flips to a visible failed state and tears the socket down — but only if we
     * still believe we're connected. Guarding on the state stops a late caller
     * from re-closing (or worse, closing a connection that has since been
     * replaced by a reconnect).
     */
    private fun markDisconnected(reason: String) {
        if (_state.value is ConnState.Connected) {
            _state.value = ConnState.Failed(reason)
            closeQuietly()
        }
    }

    /**
     * The single writer. Takes one command, then coalesces whatever else is
     * already queued into the same write — a burst of mouse moves becomes one
     * syscall and one packet instead of a dozen, which is both faster and
     * smoother. Ordering is preserved because only this coroutine writes.
     */
    private fun startWriter(o: OutputStream, box: Channel<String>) {
        scope.launch {
            try {
                for (first in box) {
                    val batch = StringBuilder(first).append('\n')
                    while (true) {
                        val more = box.tryReceive().getOrNull() ?: break
                        batch.append(more).append('\n')
                    }
                    o.write(batch.toString().toByteArray(StandardCharsets.UTF_8))
                    o.flush()
                }
            } catch (e: Exception) {
                // Only our own connection's failure is ours to report; a reconnect
                // may already have replaced us.
                if (outbox === box) {
                    markDisconnected(e.message ?: "send failed")
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
     * Uploads a file to the PC over a **separate, short-lived data connection**.
     *
     * Why its own socket: a length-prefixed payload must be contiguous on the
     * wire, so an upload on the control socket would have to hold the write lock
     * for the whole transfer — freezing mouse and keyboard for the duration. A
     * second connection sidesteps that entirely: control traffic keeps flowing at
     * full responsiveness while bytes stream here. The server recognises the
     * "data" marker in the handshake and doesn't treat this as the phone
     * connecting (see Protocol.helloData).
     *
     * Bytes are copied in 64 KB chunks and never all held in memory, so the only
     * limits are disk and time — not the app's heap.
     *
     * @param size MUST be the exact byte count — it frames the transfer.
     * @param open supplies the source stream (called once, closed here).
     * @param onProgress bytes-sent callback, throttled to whole-percent changes
     *   (~100 calls max) so a multi-GB file can't spam the UI with recompositions.
     * @return true if the server acknowledged with FILEOK.
     */
    suspend fun sendFile(
        name: String,
        size: Long,
        open: () -> InputStream?,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean =
        withContext(Dispatchers.IO) {
            val info = pairInfo ?: return@withContext false
            var data: Socket? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(info.host, info.port), 5000)
                data = s
                val o = s.getOutputStream()
                // Authenticate this channel and flag it as bulk data, not control.
                o.write((Protocol.helloData(info.token) + "\n").toByteArray(StandardCharsets.UTF_8))
                o.flush()
                val ok = Protocol.readLine(s.getInputStream())
                if (ok == null || !ok.startsWith("OK")) return@withContext false

                val safeName = java.net.URLEncoder.encode(name, "UTF-8")
                o.write("FILE $size $safeName\n".toByteArray(StandardCharsets.UTF_8))
                val input = open() ?: throw IOException("couldn't open the file")
                input.use { src ->
                    val buf = ByteArray(64 * 1024)
                    var remaining = size
                    var sent = 0L
                    var lastPct = -1
                    onProgress(0L, size)
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = src.read(buf, 0, want)
                        // We already declared `size` in the header; a short source
                        // would leave the PC waiting forever, so fail loudly.
                        if (n < 0) throw IOException("file ended early (declared $size bytes)")
                        o.write(buf, 0, n)
                        remaining -= n
                        sent += n
                        // Report only on whole-percent changes (see @param onProgress).
                        val pct = if (size > 0) ((sent * 100) / size).toInt() else 100
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(sent, size)
                        }
                    }
                }
                o.flush()
                // Our own socket, so we read the reply directly — no routing needed.
                val reply = Protocol.readLine(s.getInputStream())
                reply?.startsWith("FILEOK") == true
            } catch (e: Exception) {
                // A failed upload must NOT kill the control connection — it's a
                // separate socket, so the mouse keeps working.
                false
            } finally {
                try {
                    data?.close()
                } catch (_: Exception) {
                }
            }
        }

    /**
     * Liveness watchdog. Sends a PING every [HEARTBEAT_MS] and, if the PC hasn't
     * said anything for [SILENCE_LIMIT_MS], declares the link dead.
     *
     * Why this exists: TCP will happily hold a socket "ESTABLISHED" long after the
     * peer is unreachable (that's why the app could sit there claiming Connected
     * while nothing actually flowed). The server answers PING with PONG, so a
     * missing reply is positive proof the link is gone rather than merely idle —
     * and the UI drops to the pair screen instead of a dead touchpad.
     */
    private fun startHeartbeat(box: Channel<String>) {
        scope.launch {
            var lastTick = System.nanoTime()
            while (true) {
                delay(HEARTBEAT_MS)
                // Stop if we're no longer connected, or if this heartbeat belongs
                // to a connection that has since been replaced (otherwise every
                // reconnect would leave another heartbeat running forever).
                if (_state.value !is ConnState.Connected || outbox !== box) return@launch
                val now = System.nanoTime()
                val tickGapMs = (now - lastTick) / 1_000_000
                lastTick = now

                // If our own loop was frozen far longer than the interval (Android
                // doze / process suspended), the observed "silence" says nothing
                // about the link — we simply weren't running to hear anything.
                // Re-baseline and probe rather than kill a healthy session.
                if (tickGapMs > SILENCE_LIMIT_MS) {
                    lastHeardNanos = now
                    send(Protocol.PING)
                    continue
                }

                val silentMs = (now - lastHeardNanos) / 1_000_000
                if (silentMs > SILENCE_LIMIT_MS) {
                    markDisconnected("no response from PC")
                    return@launch
                }
                send(Protocol.PING)
            }
        }
    }

    // --- Background reader: handles server-initiated messages ---

    private fun startReader(s: Socket, input: InputStream) {
        scope.launch {
            try {
                while (true) {
                    val line = Protocol.readLine(input) ?: break // EOF -> peer closed
                    // ANY line proves the peer is alive and talking — that's what
                    // the heartbeat checks against.
                    lastHeardNanos = System.nanoTime()
                    when {
                        // Uploads run on their own data socket and read their own
                        // replies, so the control channel only carries pushes now.
                        line.startsWith(Protocol.PUSH + " ") -> receivePush(line, input)
                        else -> { /* PONG or unknown — ignore */ }
                    }
                }
            } catch (_: Exception) {
                // fall through to disconnect handling
            }
            // Only tear down if this is still the live socket. A reconnect may have
            // replaced it while we were unwinding — closing then would kill the new,
            // healthy connection.
            if (socket === s) {
                markDisconnected("connection lost")
            }
        }
    }

    /** Handles a "PUSH <size> <nameUrlEncoded>" header + the raw bytes that follow. */
    private fun receivePush(header: String, input: InputStream) {
        val parts = header.split(" ", limit = 3)
        if (parts.size < 3) {
            send(Protocol.pushErr("bad-header")); return
        }
        val size = parts[1].toLongOrNull()
        if (size == null || size < 0) {
            send(Protocol.pushErr("bad-size")); return
        }
        val name = try {
            URLDecoder.decode(parts[2], "UTF-8")
        } catch (e: Exception) {
            parts[2]
        }
        try {
            // saveToDownloads ALWAYS consumes exactly `size` bytes, even on failure,
            // so the stream stays framed for the next command.
            val saved = saveToDownloads(name, input, size)
            _received.tryEmit(saved)
            send(Protocol.pushOk(saved))
        } catch (e: Exception) {
            send(Protocol.pushErr(e.message ?: "save-failed"))
        }
    }

    /**
     * Streams `size` bytes from [input] into the phone's public Downloads folder.
     * Reads all bytes regardless of write success so the socket doesn't desync.
     * @return the display name saved.
     */
    private fun saveToDownloads(name: String, input: InputStream, size: Long): String {
        var target: OutputStream? = null
        var uri: Uri? = null
        var legacyFile: File? = null
        var writeError: Exception? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                target = uri?.let { context.contentResolver.openOutputStream(it) }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir != null) {
                    dir.mkdirs()
                    legacyFile = uniqueFile(dir, name)
                    target = legacyFile.outputStream()
                }
            }

            // Read exactly `size` bytes off the socket NO MATTER WHAT, so the
            // stream stays framed for the next command. Writing to storage is
            // best-effort: if it fails we keep reading (draining) and remember the
            // error to report afterwards. Conflating "read from socket" with
            // "write to disk" was the bug — a mid-file write failure left the rest
            // of the payload in the socket and desynced (then killed) the whole
            // connection.
            val buf = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                // A real socket EOF can't be drained — this is a genuinely dead
                // connection, so let it propagate.
                if (n < 0) throw IOException("stream ended early")
                remaining -= n
                // Receiving bytes IS proof the PC is alive (the reader is stuck in
                // this loop, not reading lines, for the whole transfer).
                lastHeardNanos = System.nanoTime()
                if (writeError == null) {
                    try {
                        target?.write(buf, 0, n)
                    } catch (e: Exception) {
                        writeError = e // stop writing, keep draining
                    }
                }
            }
            if (writeError == null) target?.flush()
        } finally {
            try { target?.close() } catch (_: Exception) {}
        }

        // Socket is fully drained by here; now surface any problem as PUSHERR
        // (the connection is fine, just this one file didn't save).
        if (writeError != null) {
            cleanup(uri, legacyFile)
            throw writeError
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri == null) throw IOException("couldn't create Downloads entry")
            // Clear IS_PENDING so the file becomes visible to the user.
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        } else if (legacyFile == null) {
            throw IOException("no Downloads folder (storage permission?)")
        }
        return name
    }

    /** Removes a half-written Downloads entry after a failed save. */
    private fun cleanup(uri: Uri?, legacyFile: File?) {
        try {
            if (uri != null) context.contentResolver.delete(uri, null, null)
            legacyFile?.delete()
        } catch (_: Exception) {
        }
    }

    /** Avoids clobbering: file.ext, file (1).ext, file (2).ext, … (legacy path only). */
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    fun disconnect() {
        closeQuietly()
        _state.value = ConnState.Disconnected
    }

    fun shutdown() {
        closeQuietly()
        scope.cancel()
    }

    private companion object {
        /** How often to probe the PC while connected. */
        const val HEARTBEAT_MS = 5_000L

        /**
         * Declare the link dead after this much total silence. Comfortably more
         * than two heartbeats, so one dropped packet or a brief stall can't
         * disconnect a perfectly good session.
         */
        const val SILENCE_LIMIT_MS = 15_000L
    }

    /**
     * WIFI_MODE_FULL_LOW_LATENCY (API 29+) asks the OS to prioritise latency over
     * power for our traffic while we hold it and are foregrounded — which is
     * exactly the touchpad case. Falls back to FULL_HIGH_PERF on older devices.
     * No permission required.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "PCRemote:control").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            // best effort — a missing lock only costs smoothness, not function
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun closeQuietly() {
        releaseWifiLock()
        outbox?.close() // ends the writer coroutine's for-loop
        outbox = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
    }
}
