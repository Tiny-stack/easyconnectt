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
   RemoteClient  ◄─────  TCP socket  ─────►    ControlServer (accept + dispatch)
        │  (net/Protocol.kt builds lines)       │  (net/Protocol.java parses lines)
        │  background reader routes PUSH/replies ├──► InputController ──► uinput / Robot
        │                                        ├──► FileReceiver   ──► ~/PC Remote Files/
   ConnectionService                             └──► ClientLink.pushFile ──► phone Downloads
   (keeps socket alive in background)
```

One phone, one PC, one **plain-TCP** socket on the LAN. The phone is the client;
the PC is the server. Everything the phone does (move, click, scroll, type, send
a file) is a short UTF-8 text line written to that socket. The only exception is
file **payload** bytes, which follow their header line raw.

The socket is **bidirectional**: the phone sends input commands and can push a
file *to* the PC (`FILE`), and the PC — while a phone is connected — can push a
file *back to* the phone (`PUSH`), saved to its Downloads. Both directions reuse
the same read-exactly-N-bytes framing.

The pairing that bootstraps the connection is a **QR code** the PC shows and the
phone scans — it carries the PC's address *and* a one-time secret token.

---

## 2. The wire, in one paragraph

Transport is plain TCP. Control messages are UTF-8 **lines terminated by `\n`**
(e.g. `M 4 -2\n` = move mouse +4,-2). Both sides hand-roll a byte-at-a-time
`readLine` ([Protocol.java](PC_Server/src/main/java/com/easyconnect/pcserver/net/Protocol.java),
[Protocol.kt](Android_app/app/src/main/java/us/easyconnect/pcremote/net/Protocol.kt))
instead of a `BufferedReader` **on purpose**: a buffering reader would greedily
swallow the raw file bytes that follow a `FILE` (or `PUSH`) header. File transfer
drops to raw mode for exactly the declared byte count, then the same socket
returns to line mode. That single design decision explains a lot of the code.

The wire protocol is currently **v2** (`Protocol.VERSION`), advertised in the
handshake. v2 added the `PUSH` direction (PC → phone) and the `push` capability;
v1 was phone → PC only. Version skew stays graceful (see the handshake rules
below), so a v2 server still talks to a v1 phone — it just never pushes.

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
phone  ── HELLO <token> v2 ──────────────────►  server
                                                ControlServer.authorized():
                                                  token is 2nd field, compared
                                                  constant-time against its own
phone  ◄─ OK v2 caps=input,file,push,hscroll ──  (match)   → session continues
phone  ◄─ DENIED ──────────────────────────────  (mismatch) → socket closed
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
- **Capabilities** are gated by what the PC can actually do
  ([ControlServer.capabilities()](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L143)):
  `input`/`file`/`push` are always advertised, while `hscroll` appears only when
  the uinput backend is live, because `java.awt.Robot` has no horizontal wheel.
  The phone checks membership in `client.server.caps` before using a feature —
  e.g. `CAP_HSCROLL` before sending `SCH`. (`push` tells the phone the PC *may*
  send it files; the phone's background reader is always ready for them anyway.)

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
  an occasional file transfer never interleave on the stream.
- **One background reader owns the input stream** (`startReader`,
  [:161](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L161)),
  launched right after the handshake. It's the single place anything is read from
  the socket, and it **routes by first token**:
  - `PUSH <size> <name>` → `receivePush` saves the bytes to Downloads, replies
    `PUSHOK`/`PUSHERR`, and emits the name on a `received` flow (for a toast).
  - `FILEOK` / `FILEERR` → completes the `CompletableDeferred` a pending
    `sendFile` is awaiting.
  - anything else (`PONG`, unknown) → ignored.

  This single-reader design is what lets the PC push a file **at any time**
  (server-initiated), not just reply to a request — the old code read a reply
  inline inside `sendFile`, which couldn't handle unsolicited messages.
- The socket sets `tcpNoDelay` (latency) and `keepAlive` (detect a dead peer,
  keep the NAT mapping warm while backgrounded).

### PC: `ControlServer` — accept, auth, dispatch

[ControlServer.java](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java):
one accept thread, then **one handler thread per connection**
([:103](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L103)).
After the handshake, `handle()` loops `readLine → dispatch` until the socket
closes. `dispatch()` ([:151](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L151))
splits on whitespace and switches on the first token, mapping each to an
`InputController` call. Unknown commands are ignored (forward-compat). `PING`
gets a `PONG`; `PUSHOK`/`PUSHERR` are acknowledgements for a push the PC sent.

The handler wraps its socket in a **`ClientLink`** — a small writer with its own
lock — and stores it in `active`
([ControlServer:123](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L123)).
That `active` link is how a PC-initiated push finds "the phone": `pushFile()`
writes through it. Because replies (on the handler thread) and pushes (on the
IPC/GUI thread) hit the same socket, `ClientLink` serialises **all** writes on
one lock — the server-side mirror of the phone's `writeLock`.

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

## 5. File transfer — bidirectional, the one time the socket leaves line mode

File transfer works **both ways**, and both directions share the exact same
framing: a header line, then exactly `<size>` raw bytes, then an ack line. The
difference is only who initiates and which keyword is used.

### 5a. Phone → PC  (`FILE`)

```
phone: user picks a file (GetContent), reads bytes (≤ 50 MB guard)
  └─ RemoteClient.sendFile(name, bytes)      // suspending, under writeLock
       pendingFileReply = CompletableDeferred()
       out.write("FILE <size> <nameUrlEncoded>\n")
       out.write(<raw bytes>)                // exactly <size> bytes
       await pendingFileReply (20 s timeout) → true iff "FILEOK…"
   ══════════════════════ TCP ══════════════════════
  ControlServer.dispatch sees "FILE" → receiveFile()
    └─ FileReceiver.receive(in, name, size)
         reads exactly <size> bytes into a temp .part file,
         then ATOMIC_MOVE into ~/PC Remote Files/<uniqueName>
    └─ ClientLink.send "FILEOK <savedName>"  (or "FILEERR <reason>" then drop socket)
   ══════════════════════ TCP ══════════════════════
  phone's background reader sees "FILEOK…" → completes pendingFileReply
```

Note the reply path: `sendFile` no longer reads its own reply inline. It parks a
`CompletableDeferred` and lets the **background reader** (§4) complete it when the
`FILEOK` line arrives ([RemoteClient.kt:137](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L137)).
It still holds `writeLock` across the whole write so no mouse command splits the
raw bytes, and it times out after 20 s so a lost reply can't hang forever.

### 5b. PC → phone  (`PUSH`)

Triggered by the **"Send file to phone"** button in the pairing window — so the
request crosses the daemon/GUI IPC boundary first, then goes out over TCP:

```
GUI: user clicks "Send file to phone", picks a file (JFileChooser)
  └─ GuiIpcClient.sendFileToPhone(path)
       out.write("SENDFILE <path>\n")        // over the UNIX IPC socket
   ═════════════ UNIX socket ═════════════
  DaemonIpc sees "SENDFILE " → sendFileHandler.accept(path)
       (wired in App.main to server::pushFile)
  └─ ControlServer.pushFile(path)
       link = active;  if null → "No phone connected" error
       └─ ClientLink.pushFile(path)          // under the same writeLock as replies
            out.write("PUSH <size> <nameUrlEncoded>\n")
            Files.newInputStream(path).transferTo(out)   // streamed, not buffered
   ══════════════════════ TCP ══════════════════════
  phone's background reader sees "PUSH …" → receivePush()
    └─ saveToDownloads(name, in, size)        // MediaStore on API 29+, else legacy
         reads EXACTLY <size> bytes (even on write failure, to stay framed)
    └─ reply "PUSHOK <savedName>"  (or "PUSHERR <reason>")
       and emits the name on `received` → MainActivity toasts "Saved to Downloads"
   ══════════════════════ TCP ══════════════════════
  ControlServer.dispatch sees "PUSHOK"/"PUSHERR" → log / surface error
```

### Shared invariants (both directions)

- **Filenames** are URL-encoded on the wire and sanitised + de-duplicated on the
  receiving side (PC: `FileReceiver`, ` (1)`/` (2)` on collision; phone:
  `MediaStore` / `uniqueFile`).
- The receiver **always consumes exactly `<size>` bytes, even on write failure**,
  so the socket stays framed for the next command
  ([saveToDownloads](Android_app/app/src/main/java/us/easyconnect/pcremote/net/RemoteClient.kt#L214)).
  The one exception is a mid-stream *read* error on the PC's `FILE` path, where
  the position is unrecoverable so the server sends `FILEERR` and **drops the
  connection** ([ControlServer.java:211](PC_Server/src/main/java/com/easyconnect/pcserver/server/ControlServer.java#L211)).
- The PC **streams** the file (`transferTo`) rather than loading it into memory;
  the phone caps outgoing files at 50 MB.
- A push needs a live phone: `pushFile` fails cleanly with a UI error if `active`
  is null (no phone connected).

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

Traffic on this IPC socket runs both ways. Daemon → GUI is the status stream
(`HELLO`, `CONNECTED`, `FILE`, …). GUI → daemon is user actions: `QUIT` (stop the
server) and **`SENDFILE <path>`** — the "Send file to phone" button hands the
daemon a local path, which `App.main` has wired to `server::pushFile`
([App.java:131](PC_Server/src/main/java/com/easyconnect/pcserver/App.java#L131)),
kicking off the PC → phone `PUSH` from §5b. So a PC-to-phone transfer actually
crosses **two** sockets: the UNIX IPC socket (GUI → daemon) then the TCP socket
(daemon → phone).

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
| Phone→PC file saving rules             | `FileReceiver` (PC) |
| PC→phone push / Downloads saving       | `ControlServer.pushFile`/`ClientLink` + `RemoteClient.receivePush`/`saveToDownloads` |
| The phone's socket read loop           | `RemoteClient.startReader` |
| Background survival                    | `ConnectionService` + `PcRemoteApp` |
| PC tray / window / process modes       | `App` + `ipc/` package |

---

### Invariants worth not breaking

1. **Keep `net/Protocol.java` and `net/Protocol.kt` in lockstep** with each other
   and with [PROTOCOL.md](PROTOCOL.md). They are the contract. Bump
   `Protocol.VERSION` on both sides together when the wire changes.
2. **Don't introduce a `BufferedReader`** on either the phone↔PC TCP socket — it
   breaks the raw `FILE`/`PUSH` bytes. Use the byte-wise `readLine`. (The UNIX
   IPC socket is pure text, so `BufferedReader` is fine there and *is* used.)
3. **Add capabilities, don't change existing lines.** Older peers must keep
   working: gate new features behind a `caps` token (that's how a v2 server still
   serves a v1 phone — it just never pushes).
4. **All reads on the TCP socket go through the one reader per side.** On the
   phone that's `startReader`; on the PC it's the per-connection `handle()` loop.
   Any framed transfer must consume **exactly** its `<size>` bytes even on error,
   or every subsequent line desyncs.
5. **All writes to a socket go through its single lock** — the phone's `writeLock`
   and the PC's `ClientLink` lock. This is what keeps a `PUSH`/reply from
   interleaving with a mouse command mid-stream now that both ends can send
   unsolicited data.
