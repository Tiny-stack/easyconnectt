package us.easyconnect.pcremote

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import us.easyconnect.pcremote.net.ConnState
import us.easyconnect.pcremote.net.PairInfo
import us.easyconnect.pcremote.net.Protocol
import us.easyconnect.pcremote.net.RemoteClient
import us.easyconnect.pcremote.settings.Settings
import us.easyconnect.pcremote.settings.SettingsRepository
import us.easyconnect.pcremote.ui.PcRemoteTheme

class MainActivity : ComponentActivity() {

    private val client: RemoteClient by lazy { (application as PcRemoteApp).client }
    private val settings: SettingsRepository by lazy { (application as PcRemoteApp).settings }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // On Android 13+ the keep-alive notification needs this permission to be
        // visible. The service still runs without it, so this is best-effort.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        setContent {
            PcRemoteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootScreen(client, settings)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The connection is app-scoped and kept alive by ConnectionService; only
        // tear it down if the user isn't actually connected (e.g. still pairing).
        if (client.state.value !is ConnState.Connected) client.shutdown()
    }
}

// NOTE the `L`: with plain Int literals this silently overflows and yields a
// nonsense cap. The upload streams in chunks, so this is just a sanity guard —
// the real limits are disk space and time, not the app's heap.
private const val MAX_FILE_BYTES = 5500L * 1024 * 1024

// Two-finger scroll: pixels of vertical travel per wheel tick. Direction
// (invertScroll) and pointer speed (sensitivity) are user settings now.
private const val SCROLL_STEP = 32f

@Composable
private fun RootScreen(client: RemoteClient, settingsRepo: SettingsRepository) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Settings are reachable both before and after pairing, so this lives above
    // the pair/touchpad split.
    var showSettings by remember { mutableStateOf(false) }

    // Run the keep-alive foreground service exactly while connected.
    val connected = state is ConnState.Connected
    LaunchedEffect(connected) {
        if (connected) ConnectionService.start(context) else ConnectionService.stop(context)
    }

    // Toast when the PC pushes a file to us (it's saved to Downloads).
    LaunchedEffect(Unit) {
        client.received.collect { name ->
            Toast.makeText(context, "Saved to Downloads: $name", Toast.LENGTH_LONG).show()
        }
    }

    if (showSettings) {
        SettingsScreen(settingsRepo, onBack = { showSettings = false })
        return
    }

    when (val s = state) {
        is ConnState.Connected -> TouchpadScreen(
            client = client,
            settingsRepo = settingsRepo,
            onOpenSettings = { showSettings = true }
        )
        else -> PairScreen(
            state = s,
            onPaired = { info -> scope.launch { client.connect(info) } },
            onOpenSettings = { showSettings = true }
        )
    }
}

/** Touchpad preferences. Reachable before pairing and while connected. */
@Composable
private fun SettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val s by repo.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = onBack) { Text("Done") }
        }

        Spacer(Modifier.height(24.dp))

        Text("Pointer speed", fontSize = 16.sp)
        Text(
            String.format("%.1f×", s.sensitivity),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = s.sensitivity,
            onValueChange = { repo.setSensitivity(it) },
            valueRange = Settings.MIN_SENSITIVITY..Settings.MAX_SENSITIVITY
        )

        SettingSwitch(
            title = "Invert scrolling",
            subtitle = "Content follows your fingers, phone-style",
            checked = s.invertScroll,
            onChange = { repo.setInvertScroll(it) }
        )

        SettingSwitch(
            title = "Two-finger tap = right-click",
            subtitle = "Extra option — holding one finger still right-clicks too",
            checked = s.twoFingerRightClick,
            onChange = { repo.setTwoFingerRightClick(it) }
        )

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { repo.reset() }) { Text("Reset to defaults") }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PairScreen(state: ConnState, onPaired: (PairInfo) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val info = PairInfo.parse(contents)
        if (info == null) {
            Toast.makeText(context, "Not a PC Remote code", Toast.LENGTH_SHORT).show()
        } else {
            onPaired(info)
        }
    }

    fun launchScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Point at the PC's QR code")
            .setBeepEnabled(false)
            // Let our portrait-locked activity's manifest orientation win instead
            // of the library forcing the sensor (landscape) orientation.
            .setOrientationLocked(false)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
        scanLauncher.launch(options)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScan()
        else Toast.makeText(context, "Camera permission is required to scan", Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("PC Remote", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Scan the QR code shown on your PC to connect. Only your phone can control the PC — others on the network can't.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        when (state) {
            is ConnState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Connecting…")
            }

            is ConnState.Denied -> StatusLine("Code rejected — scan a fresh QR from the PC.", true)
            is ConnState.Failed -> StatusLine("Couldn't connect: ${state.reason}", true)
            else -> {}
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) launchScan() else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Scan QR to connect", fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenSettings) { Text("⚙  Settings") }
    }
}

@Composable
private fun TouchpadScreen(
    client: RemoteClient,
    settingsRepo: SettingsRepository,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var lastTapTime by remember { mutableStateOf(0L) }
    // Held as a State (not unwrapped with `by`) so the long-lived pointerInput
    // loop below reads fresh values live, instead of capturing them once and
    // needing a restart whenever a setting changes.
    val settingsState = settingsRepo.settings.collectAsStateWithLifecycle()
    // Non-null while an upload is in flight; drives the progress bar.
    var upload by remember { mutableStateOf<Upload?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, size) = queryNameAndSize(context, uri)
            when {
                // The wire format is length-prefixed, so an exact size is required.
                size < 0 -> toast(context, "Couldn't read the file's size")
                size > MAX_FILE_BYTES ->
                    toast(context, "File too large (max ${MAX_FILE_BYTES / 1024 / 1024} MB)")
                else -> {
                    upload = Upload(name, 0f, 0L, size)
                    // Streamed: only the file's size limits us, not the app's heap.
                    val ok = client.sendFile(
                        name = name,
                        size = size,
                        open = { context.contentResolver.openInputStream(uri) },
                        onProgress = { sent, total ->
                            // Compose snapshot state is safe to write off the main
                            // thread; this callback runs on the IO dispatcher.
                            upload = Upload(name, sent.toFloat() / total, sent, total)
                        }
                    )
                    upload = null
                    toast(context, if (ok) "Sent: $name" else "Send failed")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Connected", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings) { Text("⚙") }
                OutlinedButton(onClick = { client.disconnect() }) { Text("Disconnect") }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Trackpad surface.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                // Single detector for move + tap + double-tap + long-press. Two
                // separate pointerInput blocks conflict (the tap detector eats the
                // down event so drag never fires), so we hand-roll one gesture loop.
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                    // Carry sub-pixel remainder so slow drags aren't truncated to 0.
                    var accX = 0f
                    var accY = 0f
                    fun emit(dx: Float, dy: Float) {
                        // Last line of defence: a non-finite sensitivity would make
                        // every delta round to 0 and silently freeze the pointer
                        // (see SettingsRepository.sanitizeSensitivity).
                        val raw = settingsState.value.sensitivity // live, per-event
                        val sens = if (raw.isFinite() && raw > 0f) raw else Settings.DEFAULT_SENSITIVITY
                        accX += dx * sens
                        accY += dy * sens
                        val mx = accX.toInt()
                        val my = accY.toInt()
                        if (mx != 0 || my != 0) {
                            client.move(mx, my)
                            accX -= mx
                            accY -= my
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        accX = 0f; accY = 0f
                        var travel = 0f
                        val outcome = withTimeoutOrNull(longPressMs) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                // A second finger touching down turns this into a
                                // two-finger scroll instead of a move/tap.
                                if (ev.changes.count { it.pressed } >= 2) {
                                    return@withTimeoutOrNull "scroll"
                                }
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                                if (!c.pressed) return@withTimeoutOrNull "tap"
                                val d = c.positionChange()
                                travel += abs(d.x) + abs(d.y)
                                if (travel > slop) {
                                    emit(d.x, d.y)
                                    c.consume()
                                    return@withTimeoutOrNull "drag"
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") "tap"
                        }
                        when (outcome) {
                            "scroll" -> { // two fingers down -> trackpad scroll (or two-finger tap)
                                val hScroll = Protocol.CAP_HSCROLL in client.server.caps
                                var scrollAccY = 0f
                                var scrollAccX = 0f
                                // Total finger travel, so we can tell a two-finger TAP
                                // (barely moves) from a two-finger scroll (moves).
                                var twoFingerTravel = 0f
                                do {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        // Average the fingers' travel on each axis so the
                                        // gesture is steady even if they move unevenly.
                                        val dy = pressed.sumOf { it.positionChange().y.toDouble() }
                                            .toFloat() / pressed.size
                                        val dx = pressed.sumOf { it.positionChange().x.toDouble() }
                                            .toFloat() / pressed.size
                                        twoFingerTravel += abs(dx) + abs(dy)
                                        val invert = settingsState.value.invertScroll
                                        scrollAccY += dy
                                        val ticksY = (scrollAccY / SCROLL_STEP).toInt()
                                        if (ticksY != 0) {
                                            client.scroll(if (invert) -ticksY else ticksY)
                                            scrollAccY -= ticksY * SCROLL_STEP
                                        }
                                        // Horizontal only when the server can inject it.
                                        if (hScroll) {
                                            scrollAccX += dx
                                            val ticksX = (scrollAccX / SCROLL_STEP).toInt()
                                            if (ticksX != 0) {
                                                client.scrollH(if (invert) -ticksX else ticksX)
                                                scrollAccX -= ticksX * SCROLL_STEP
                                            }
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })

                                // Fingers lifted without really moving = two-finger tap.
                                // Opt-in; hold-to-right-click keeps working regardless.
                                if (settingsState.value.twoFingerRightClick && twoFingerTravel < slop) {
                                    client.click(Protocol.RIGHT)
                                }
                            }
                            null -> { // held without moving -> right click
                                client.click(Protocol.RIGHT)
                                do {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { it.consume() }
                                } while (e.changes.any { it.pressed })
                            }
                            "tap" -> {
                                val now = down.uptimeMillis
                                if (now - lastTapTime <= doubleTapMs) {
                                    client.doubleClick(Protocol.LEFT)
                                    lastTapTime = 0L
                                } else {
                                    client.click(Protocol.LEFT)
                                    lastTapTime = now
                                }
                            }
                            "drag" -> {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                                    if (!c.pressed) break
                                    emit(c.positionChange().x, c.positionChange().y)
                                    c.consume()
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Drag to move  ·  tap = click  ·  double-tap = double-click\n" +
                    "hold = right-click  ·  two fingers = scroll" +
                    if (settingsState.value.twoFingerRightClick) {
                        "\ntwo-finger tap = right-click"
                    } else {
                        ""
                    },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.weight(1f),
                label = { Text("Type on PC") },
                singleLine = true
            )
            Button(
                onClick = {
                    if (typed.isNotEmpty()) {
                        client.type(typed)
                        typed = ""
                    }
                }
            ) { Text("Send") }
        }

        Spacer(Modifier.height(8.dp))
        val up = upload
        if (up != null) {
            Text(
                "Sending ${up.name} — ${(up.fraction * 100).toInt()}%  " +
                    "(${formatBytes(up.sent)} / ${formatBytes(up.total)})",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { up.fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { fileLauncher.launch("*/*") },
            enabled = up == null, // one transfer at a time
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (up == null) "Send file to PC" else "Sending…")
        }
    }
}

/** An upload in flight: what's being sent and how far along it is. */
private data class Upload(
    val name: String,
    val fraction: Float,
    val sent: Long,
    val total: Long
)

/** 1.2 KB / 3.4 MB / 1.1 GB — keeps the progress line readable. */
private fun formatBytes(n: Long): String = when {
    n >= 1_000_000_000 -> String.format("%.1f GB", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format("%.1f MB", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1f KB", n / 1_000.0)
    else -> "$n B"
}

@Composable
private fun StatusLine(text: String, error: Boolean) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Resolves a picked file's display name and exact byte count. The size frames
 * the length-prefixed upload, so a provider that doesn't report one yields -1
 * and the caller refuses the send rather than desyncing the stream.
 */
private fun queryNameAndSize(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = "file.bin"
    var size = -1L
    context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
    )?.use { c ->
        if (c.moveToFirst()) {
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
        }
    }
    return Pair(name, size)
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
