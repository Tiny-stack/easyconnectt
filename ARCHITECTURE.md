# PC Remote — Architecture & Code Flow

How the two halves fit together and how a phone ends up moving the PC's cursor.
For the exact bytes on the wire, see [PROTOCOL.md](PROTOCOL.md); this doc is the
**code-flow** companion — which class calls what, and why.

- **PC server** — Java, [PC_Server/](PC_Server/), package `com.easyconnect.pcserver`
- **Android app** — Kotlin/Compose, [Android_app/](Android_app/), package `us.easyconnect.pcremote`

---

## 1. The 10,000-foot view

```
   ANDROID PHONE                                 PC (server)
   ─────────────                                 ───────────
   MainActivity (Compose UI)                     App.main  (entry / mode picker)
        │ gestures                                    │
        ▼                                             ▼
   RemoteClient  ──────  TCP socket  ──────►   ControlServer (accept + dispatch)
        │  (net/Protocol.kt builds lines)       │  (net/Protocol.java parses lines)
        │                                        ├──► InputController ──► uinput / Robot
   ConnectionService                             └──► FileReceiver   ──► ~/PC Remote Files/
   (keeps socket alive in background)
```

One phone, one PC, one **plain-TCP** socket on the LAN. The phone is the client;
the PC is the server. Everything the phone does (move, click, scroll, type, send
a file) is a short UTF-8 text line written to that socket. The only exception is
file **payload** bytes, which follow their header line raw.

The pairing that bootstraps the connection is a **QR code** the PC shows and the
phone scans — it carries the PC's address *and* a one-time secret token.

---

## 2. The wire, in one paragraph

Transport is plain TCP. Control messages are UTF-8 **lines terminated by `\n`**
(e.g. `M 4 -2\n` = move mouse +4,-2). Both sides hand-roll a byte-at-a-time
`readLine` ([Protocol.java:81](PC_Server/src/main/java/com/easyconnect/pcserver/net/Protocol.java#L81),
[Protocol.kt:74](Android_app/app/src/main/java/us/easyconnect/pcremote/net/Protocol.kt#L74))
instead of a `BufferedReader` **on purpose**: a buffering reader would greedily
swallow the raw file bytes that follow a `FILE` header. File transfer drops to
raw mode for exactly the declared byte count, then the same socket returns to
line mode. That single design decision explains a lot of the code.

---

## 3. Pairing — how the two ends find and trust each other

### PC side — mint a secret, advertise an address

At startup [App.java:79](PC_Server/src/main/java/com/easyconnect/pcserver/App.java#L79):

1. `Pairing.randomToken()` generates a fresh **128-bit** secret as hex via
   `SecureRandom` — regenerated **every launch**
   ([Pairing.java:35](PC_Server/src/main/java/com/easyconnect/pcserver/pairing/Pairing.java#L35)).
2. `ControlServer.start(port)` binds TCP `5555` (or an ephemeral port if taken)
   and starts the accept loop.
3. `Pairing.of(token, port)` detects a **site-local IPv4** the phone can reach
   ([Pairing.java:113](PC_Server/src/main/java/com/easyconnect/pcserver/pairing/Pairing.java#L113))
   and builds the payload:

   ```
   tcr://<host>:<port>/<tokenHex>
   ```

4. That string is rendered as a QR — as an image in the Swing window
   (`qrImage`) or as Unicode blocks in the terminal (`qrAscii`) for headless runs.

### Phone side — scan, parse, connect, prove

1. `PairScreen` launches the ZXing scanner (locked to portrait via
   `PortraitCaptureActivity`).
2. `PairInfo.parse` validates the `tcr://host:port/token` payload
   ([Protocol.kt:9](Android_app/app/src/main/java/us/easyconnect/pcremote/net/Protocol.kt#L9)).
   A non-matching QR is rejected with a toast.
3. `RemoteClient.connect(info)` opens the socket and runs the handshake.

### The handshake (token auth + version negotiation)

```
phone  ── HELLO <token> v1 ─────────────►  server
                                           ControlServer.authorized():
                                             token is 2nd field, compared
                                             constant-time against its own
phone  ◄──── OK v1 caps=input,file,hscroll ─  (match)   → session continues
phone  ◄──── DENIED ─────────────────────────  (mismatch) → socket closed
```

- Auth: [ControlServer.authorized():141](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L141)
  — token is the **second whitespace field**; trailing `v<n>`/`caps=` are ignored,
  so newer clients can announce more without breaking auth. Comparison is
  **constant-time** ([:218](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L218)).
- The token is what locks other devices out: anyone on the Wi-Fi can *reach* the
  port, but only the phone that scanned the QR knows the secret.
- **Forward-compat rule:** the client treats *any* reply starting with `OK` as
  success and parses the version + capabilities that follow
  ([RemoteClient.kt:67](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L67),
  `parseServerHello`). A newer server advertising `video`/`gamepad` never looks
  "denied" to an older phone.
- **Capabilities** are gated by what the PC can actually do: `hscroll` is only
  advertised when the uinput backend is live, because `java.awt.Robot` has no
  horizontal wheel ([ControlServer.capabilities():131](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L131)).
  The phone checks `CAP_HSCROLL in client.server.caps` before ever sending `SCH`.

---

## 4. The steady state — input command flow

Once connected, the phone shows `TouchpadScreen` and the PC's `ControlServer` is
looping over lines. A single mouse-move round trip:

```
finger drags on trackpad Box
  └─ MainActivity pointerInput gesture loop  (accumulates sub-pixel travel)
       └─ client.move(dx, dy)
            └─ RemoteClient.send("M 4 -2")           // fire-and-forget
                 └─ writeLock.withLock { out.write("M 4 -2\n") }   // serialized
   ══════════════════════ TCP ══════════════════════
                 ControlServer.handle() loop reads a line
                   └─ dispatch("M 4 -2")             // switch on first token
                        └─ input.moveRelative(4, -2)
                             └─ uinput.moveRelative   (or Robot fallback)
                                  └─ kernel /dev/uinput → cursor moves
```

### Phone: gestures → commands

All gesture handling is one hand-rolled loop in `TouchpadScreen`
([MainActivity.kt:254](Android_app/app/src/main/java/us/easyconnect/pcremote/MainActivity.kt#L254)).
Two separate detectors would conflict (a tap detector eats the `down` event, so
drag never fires), so it's a single `awaitEachGesture`:

| Gesture                    | Resolved outcome | Command sent          |
|----------------------------|------------------|-----------------------|
| Drag                       | `"drag"`         | `M dx dy` (repeated)  |
| Quick tap                  | `"tap"`          | `C L`                 |
| Second tap within timeout  | double-tap       | `DC L`                |
| Hold without moving        | `null` (timeout) | `C R` (right-click)   |
| Second finger down         | `"scroll"`       | `SC ±n` / `SCH ±n`    |
| Buttons / text field       | direct           | `C L`/`C R`, `K text` |

Notes worth knowing:
- **Sub-pixel accumulation** (`accX/accY`) means slow drags aren't truncated to
  zero — remainder carries between events, scaled by `SENSITIVITY = 1.6`.
- Two-finger scroll averages both fingers' travel and only emits a tick every
  `SCROLL_STEP = 32px`. Horizontal ticks are suppressed unless the server
  advertised `hscroll`.

### Phone: `RemoteClient` — the socket owner

[RemoteClient.kt](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt)
owns the one socket and exposes a `StateFlow<ConnState>` the UI observes
(`Disconnected → Connecting → Connected/Denied/Failed`).

- `send()` is **fire-and-forget** on a private IO coroutine scope. A write
  failure flips state to `Failed` and closes — mouse spam never blocks the UI.
- All writes go through a **`Mutex` (`writeLock`)** so high-frequency moves and
  an occasional file transfer never interleave on the stream
  ([:86](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L86)).
- The socket sets `tcpNoDelay` (latency) and `keepAlive`. Keepalive is
  deliberately OS-level rather than an app `PING`: an app-level `PING`'s `PONG`
  reply could be misread by `sendFile` as its `FILEOK`
  ([:59](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L59)).

### PC: `ControlServer` — accept, auth, dispatch

[ControlServer.java](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java):
one accept thread, then **one handler thread per connection**
([:103](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L103)).
After the handshake, `handle()` loops `readLine → dispatch` until the socket
closes. `dispatch()` ([:151](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L151))
splits on whitespace and switches on the first token, mapping each to an
`InputController` call. Unknown commands are ignored (forward-compat). `PING`
is the only input command that gets a reply (`PONG`).

### PC: `InputController` — commands → real OS input

[InputController.java](PC_Server/src/main/java/com/easyconnect/pcserver/input/InputController.java)
has **two backends** and picks lazily:

1. **`UinputMouse`** (preferred on Linux/Wayland) — writes directly to
   `/dev/uinput` via the Java Foreign Function & Memory API, no native lib. Under
   Wayland, `Robot.mouseMove` only warps the invisible XWayland pointer; injecting
   *relative* motion through uinput looks like a real mouse so the compositor
   moves the visible cursor ([UinputMouse.java:14](PC_Server/src/main/java/com/easyconnect/pcserver/input/UinputMouse.java#L14)).
2. **`java.awt.Robot`** — fallback, created **lazily** only if needed (e.g. the
   rare absolute-move `MA`, or when uinput is unavailable). This matters for
   memory: building `Robot` loads ~22 MB of AWT and blocks GraalVM, so on a
   working uinput box AWT never loads at all
   ([InputController.java:27](PC_Server/src/main/java/com/easyconnect/pcserver/input/InputController.java#L27)).

`supportsHorizontalScroll()` (true only for uinput) is what drives the `hscroll`
capability advertised in the handshake — closing the loop back to §3.

---

## 5. File transfer — the one time the socket leaves line mode

```
phone: user picks a file (GetContent), reads bytes (≤ 50 MB guard)
  └─ RemoteClient.sendFile(name, bytes)      // suspending, under writeLock
       out.write("FILE <size> <nameUrlEncoded>\n")
       out.write(<raw bytes>)                // exactly <size> bytes
       reply = readLine()  → true iff "FILEOK…"
   ══════════════════════ TCP ══════════════════════
  ControlServer.dispatch sees "FILE" → receiveFile()
    └─ FileReceiver.receive(in, name, size)
         reads exactly <size> bytes into a temp .part file,
         then ATOMIC_MOVE into ~/PC Remote Files/<uniqueName>
    └─ reply "FILEOK <savedName>"   (or "FILEERR <reason>" then drop socket)
```

Key points:
- **`sendFile` is the only phone→PC call that awaits a reply**, and it holds the
  `writeLock` across write+read so no stray mouse command lands between the raw
  bytes and the `FILEOK` line ([RemoteClient.kt:112](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L112)).
- The filename is URL-encoded on the wire and **sanitised + de-duplicated** on
  the PC (`received.bin` fallback, ` (1)`, ` (2)`… on collision)
  ([FileReceiver.java:60](PC_Server/src/main/java/com/easyconnect/pcserver/transfer/FileReceiver.java#L60)).
- Write goes to a temp `.part` then `ATOMIC_MOVE` — a partial transfer never
  leaves a half file under the real name.
- On any read error the server sends `FILEERR` and **drops the connection**,
  because the stream position is no longer trustworthy
  ([ControlServer.java:196](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L196)).

---

## 6. Staying connected in the background (Android)

Modern Android freezes a backgrounded process, which would kill the socket the
moment the app is minimized. The fix:

- The `RemoteClient` is **app-scoped**, held by `PcRemoteApp`
  ([PcRemoteApp.kt](Android_app/app/src/main/java/us/easyconnect/pcremote/PcRemoteApp.kt)),
  so it outlives any single `Activity`.
- `ConnectionService` is a **foreground service** whose only job is to keep the
  process alive while connected
  ([ConnectionService.kt](Android_app/app/src/main/java/us/easyconnect/pcremote/ConnectionService.kt)).
  It owns no socket — it just watches `client.state` and `stopSelf()`s the moment
  the connection ends, so the "PC Remote connected" notification never lingers.
- `RootScreen` starts/stops the service exactly while state is `Connected`
  ([MainActivity.kt:113](Android_app/app/src/main/java/us/easyconnect/pcremote/MainActivity.kt#L113)).
  `MainActivity.onDestroy` only tears the client down if *not* connected (still
  pairing) — a live connection survives the Activity.

---

## 7. PC process model (why `App.main` is a mode-picker)

`App.main` ([App.java:47](PC_Server/src/main/java/com/easyconnect/pcserver/App.java#L47))
routes to one of several roles. This split exists purely to keep idle RAM low —
Swing/AWT alone costs ~25 MB, so it's isolated in a throwaway subprocess.

| Invocation           | Role                                                          |
|----------------------|--------------------------------------------------------------|
| *(default, desktop)* | **daemon** — lean AWT-free server + notifications; spawns the GUI as a child |
| `gui` / `--gui`      | Swing pairing window subprocess; the **only** process loading AWT |
| `toggle`             | tell a running daemon to show/hide its window, then exit      |
| `--nogui`            | pure headless server (prints the QR as text); auto-fallback with no display |
| `--selftest`         | pointer self-check                                            |

The daemon and its GUI/tray talk over a **UNIX-domain socket** (`DaemonIpc` ⇄
`GuiIpcClient`), entirely separate from the TCP channel to the phone. Relaunching
the app when a daemon is already running just **toggles the window** instead of
starting a duplicate server ([App.java:70](PC_Server/src/main/java/com/easyconnect/pcserver/App.java#L70)).
Server lifecycle events (`onClientConnected`, `onFileReceived`, …) flow through
the `ServerListener` interface to whatever's listening — the IPC channel and the
desktop notifier, combined via `CompositeListener`.

> This process model matters for RAM but is **orthogonal to the phone protocol**:
> the phone always talks to the same `ControlServer` regardless of which PC mode
> is running.

---

## 8. Where to look when…

| You want to change…                    | Start in |
|----------------------------------------|----------|
| A new command / the wire format        | both `net/Protocol.*` + [PROTOCOL.md](PROTOCOL.md), then `ControlServer.dispatch` |
| How a gesture maps to a command        | `MainActivity.TouchpadScreen` gesture loop |
| How the PC actually moves the cursor   | `InputController` → `UinputMouse` |
| Pairing / QR / token                   | `Pairing` (PC) + `PairInfo.parse` (phone) |
| Auth / capabilities                    | `ControlServer.authorized` / `capabilities` |
| File saving rules                      | `FileReceiver` |
| Background survival                    | `ConnectionService` + `PcRemoteApp` |
| PC tray / window / process modes       | `App` + `ipc/` package |

---

### Invariants worth not breaking

1. **Keep `net/Protocol.java` and `net/Protocol.kt` in lockstep** with each other
   and with [PROTOCOL.md](PROTOCOL.md). They are the contract.
2. **Don't introduce a `BufferedReader`** on either socket — it breaks raw file
   bytes. Use the byte-wise `readLine`.
3. **Add capabilities, don't change existing lines.** Older peers must keep
   working: gate new features behind a `caps` token.
4. **Only `PING` and `FILE` get replies** on the control channel. Adding a new
   reply-bearing command risks the same `PONG`/`FILEOK` confusion the keepalive
   comment warns about — think about interleaving under `writeLock`.
