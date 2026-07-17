# File Sharing — How It Works

PC Remote transfers files **both ways** on your LAN — no HTTP server, no cloud.

| Direction | Trigger | Carried on | Lands in |
|---|---|---|---|
| **Phone → PC** | "Send file to PC" button in the app | a short-lived **data connection** | `~/PC Remote Files/` |
| **PC → Phone** | "Send file to phone" button in the PC window | the **control connection** | the phone's **Downloads** |

The asymmetry is deliberate and is explained in §4 — it's what keeps the mouse
alive while a big file uploads.

---

## 1. The transport, in one picture

The phone is the **client**, the PC is the **server**. The phone dials out, so no
port needs opening on the phone — and both connections go to the *same* port.

```
        phone  ══ control connection ══► PC     mouse, keys, PC→phone PUSH
               ◄══════════════════════        (long-lived)

        phone  ── data connection ────► PC      one file upload, then closed
               ◄──────────────────────         (only while uploading — see §4)
```

Everything is **UTF-8 text lines** ending in `\n` (`M 3 -2`, `C L`, `PING`…).
A file transfer temporarily switches the stream to **raw bytes** for exactly the
number of bytes declared in the header, then returns to line mode:

```
"FILE 1234 holiday.jpg\n"   <- header line (text)
<exactly 1234 raw bytes>    <- payload (binary, no framing)
"FILEOK holiday.jpg\n"      <- reply line (text)
```

This "**length-prefixed block**" is the heart of the design. Both sides read
*exactly* N bytes and not one more, so the stream stays perfectly in sync and the
next line lands on a clean boundary.

> **Why not a BufferedReader?** Both sides deliberately read lines byte-by-byte
> (`Protocol.readLine`). A buffered reader would greedily pull the file's raw
> bytes into its internal buffer while reading the header line, and they'd be
> lost. This is called out in the code on both sides.

Filenames are **URL-encoded** in the header so spaces/newlines can't break the
line framing (`My Photo.jpg` → `My+Photo.jpg`).

---

## 2. Phone → PC

### The wire

```
phone → PC:  FILE <sizeBytes> <nameUrlEncoded>
phone → PC:  <sizeBytes raw bytes>
PC → phone:  FILEOK <savedName>      (or FILEERR <reason>)
```

### Phone side — `net/RemoteClient.kt`

`sendFile(name, size, open, onProgress)`:
1. Dials a **fresh data connection** to the same host/port and handshakes with
   `HELLO <token> v3 data` (see §4 for why it isn't the control socket).
2. Writes the header, then **streams** the source in 64 KB chunks and flushes.
3. Reads its own `FILEOK`/`FILEERR` straight off that socket, then closes it.

The bytes are **never all held in memory**. An earlier version did
`inputStream.readBytes()` into a `ByteArray`, which capped uploads at whatever
fit in the app's heap (a few hundred MB before an OOM crash). Streaming means the
only real limits are disk space and time.

The UI picks the file with `ActivityResultContracts.GetContent()` and resolves its
**exact size** first (`queryNameAndSize`). The size is mandatory: the header is
length-prefixed, so a provider that can't report one is refused up front rather
than risking a desync mid-transfer.

**Progress reporting** — `onProgress(sent, total)` fires only on whole-percent
changes (~100 callbacks max), so a multi-GB upload can't spam Compose with
thousands of recompositions. `MainActivity` renders a `LinearProgressIndicator`
with a `12.3 MB / 250.0 MB` label and disables the send button while a transfer is
in flight (one at a time).

### PC side — `server/ControlServer.java` → `transfer/FileReceiver.java`

`dispatch()` sees `FILE` and calls `receiveFile()`, which hands the socket stream
to `FileReceiver.receive(in, name, size)`:

1. **Sanitises** the name — strips `\ / : * ? " < > |` and control chars, so a
   malicious phone can't path-traverse out of the folder.
2. **De-duplicates** — `report.pdf`, then `report (1).pdf`, `report (2).pdf`…
3. Streams into a **temp file** (`.incoming-*.part`) in 64 KB chunks, reading
   exactly `size` bytes.
4. **Atomically moves** the temp file into place. A half-received file is deleted
   and never appears as a real file.
5. Replies `FILEOK <savedName>`; the GUI status line updates via `onFileReceived`.

If the copy fails, the connection is **dropped on purpose** — the stream position
is no longer trustworthy, so reusing it would desync the protocol.

---

## 3. PC → Phone

This direction is newer and has one extra hop, because of the daemon/GUI split:
the **daemon** owns the phone socket, but the **window** is a separate process.

```
 [GUI window process]                    [daemon process]                [phone]
  "Send file to phone"
      JFileChooser
          │
          │  SENDFILE /path/to/file        (UNIX socket, local IPC)
          └──────────────────────────────►
                                          ControlServer.pushFile(path)
                                              │  PUSH <size> <name>
                                              │  <raw bytes>
                                              └──────────────────────────► saves to
                                          ◄──────────────────────────────  Downloads
                                              PUSHOK <name>
```

### The wire

```
PC → phone:  PUSH <sizeBytes> <nameUrlEncoded>
PC → phone:  <sizeBytes raw bytes>
phone → PC:  PUSHOK <savedName>      (or PUSHERR <reason>)
```

`PUSH` is intentionally **framed exactly like `FILE`**, just in the other
direction, so both peers reuse the same read-N-bytes logic.

### PC side

1. **`ui/MainWindow.java`** — the "Send file to phone" button opens a
   `JFileChooser`. It's **disabled until a phone connects** (toggled by
   `onClientConnected` / `onClientDisconnected`), so you can't send into the void.
2. **`ipc/GuiIpcClient.java`** — sends `SENDFILE <absolutePath>` to the daemon.
   Only the **path** crosses the IPC channel, not the bytes — both processes are
   on the same machine, so the daemon just opens the file itself.
   The path is the rest of the line, so spaces are fine.
3. **`ipc/DaemonIpc.java`** — recognises `SENDFILE` and calls the handler wired in
   `App.runDaemon`: `ipc.setSendFileHandler(server::pushFile)`.
4. **`server/ControlServer.java`** — `pushFile(Path)`:
   - Looks up `active`, the currently-connected phone (`ClientLink`). If none,
     reports "No phone connected" and stops.
   - `ClientLink.pushFile()` writes the header then **streams** the file with
     `InputStream.transferTo(out)` — the file is never fully loaded into RAM, so
     a big file won't blow up the lean daemon's heap.

### Phone side — `net/RemoteClient.kt`

The background reader (§5) sees a line starting with `PUSH ` and calls
`receivePush()` → `saveToDownloads()`:

- **Android 10+ (API 29+)** — inserts a row into `MediaStore.Downloads` with
  `IS_PENDING=1`, streams the bytes into it, then clears `IS_PENDING` so the file
  becomes visible. **No storage permission is needed** — this is the scoped-storage
  way, and the file shows up in the Files app under Downloads.
- **Android 9 and older** — falls back to the public `Downloads` directory as a
  plain file (with a `file (1).ext` de-dupe helper). This path needs
  `WRITE_EXTERNAL_STORAGE`, declared with `maxSdkVersion="28"`.

Then it replies `PUSHOK <name>` and emits the name on a `SharedFlow` (`received`),
which `MainActivity` collects to show the **"Saved to Downloads: …"** toast.

> **Critical detail:** `saveToDownloads` **always consumes exactly `size` bytes**,
> even if the file can't be written. If it bailed out early, the unread bytes
> would still be in the socket and the *next* `readLine` would interpret file
> content as commands — desyncing the connection. On failure it drains the bytes
> and replies `PUSHERR`.

---

## 4. Why uploads get their own connection

A length-prefixed payload **must be contiguous on the wire**. Once you've written
`FILE 250000000 …`, the PC reads exactly that many bytes as file content — so you
cannot slip an `M 3 -2` in between. To guarantee that, the first version held the
phone's single **write lock** for the whole upload:

```kotlin
writeLock.withLock {          // held for header + ALL bytes + the FILEOK wait
    o.write(header); …stream…
}
```

Every mouse move goes through `send()`, which needs that same lock — so **mouse and
keyboard froze for the entire upload**. On a 250 MB file that's minutes of dead
input. (PC → phone never had this problem: it only occupies the *PC's* write lock,
while the phone's mouse traffic flows the other way on a free lock.)

Two ways out:

1. **Chunk the payload** into many small framed blocks and release the lock
   between them. Works, but needs a protocol state machine and reassembly on the
   PC, and still adds per-chunk latency to input.
2. **Give the upload its own socket.** ← chosen

So `sendFile` now dials a **second, short-lived connection** and streams there:

```
control connection  ──  mouse, keys, PUSH        (never blocked)
data connection     ──  one file upload, then closed
```

The data channel authenticates with the same pairing token but adds a marker:

```
HELLO <token> v3 data
```

`ControlServer.isDataChannel()` spots it and treats the connection differently — it
**does not** become the `active` push target, and it raises **no**
connected/disconnected events (otherwise the PC's UI would flash "phone connected"
and then "disconnected" on every upload, and a push could be aimed at a socket
that's about to close). Everything else — auth, `FILE`, `FileReceiver`, `FILEOK` —
is byte-for-byte the same code path.

Two nice side effects: the upload reads its **own** `FILEOK` directly (no reply
routing needed), and a failed upload can't kill the control connection — the mouse
keeps working regardless.

## 5. The one piece that made PC → Phone possible

Originally the phone only ever **replied** to its own requests — nobody was
listening between them, so the PC had no way to send anything on its own
initiative.

So `RemoteClient` now runs a **single background reader** that owns the control
socket's input stream (started right after the handshake in `connect()`):

```kotlin
while (true) {
    val line = Protocol.readLine(input) ?: break     // EOF -> peer gone
    when {
        line.startsWith("PUSH ") -> receivePush(line, input)  // handle inline
        else -> { /* PONG / unknown -> ignore */ }
    }
}
```

Exactly one reader touches that stream, so there's no race over who consumes which
bytes. Uploads don't appear here at all — they read their own `FILEOK` on their own
data socket (§4).

### Who may write, and when

Both sides serialise **writes**, because two threads can share one socket:

- **Phone** — `writeLock: Mutex`, taken only by `send()` (mouse/keys). Uploads no
  longer take it, which is precisely why input stays responsive during a transfer.
- **PC** — `ClientLink.writeLock`. Replies (`FILEOK`, `PONG`) run on the client's
  handler thread while `pushFile()` runs on the daemon's IPC thread; the lock keeps
  a push and a reply from interleaving mid-stream.

Reads never conflict: each connection has exactly one reader.

---

## 6. Capability negotiation

The handshake tells each side what the other can do, so mixed versions degrade
instead of breaking:

```
phone → PC:  HELLO <token> v3
PC → phone:  OK v3 caps=input,file,push,hscroll
```

Any reply starting with `OK` is success. The phone parses `caps=` into
`ServerHello.caps`; the code checks membership (e.g. `CAP_HSCROLL`) before using
a feature. Unknown capabilities are ignored, so a newer server never breaks an
older phone.

---

## 7. Limits, safety and failure modes

| Concern | Behaviour |
|---|---|
| Max size (phone → PC) | ~5.5 GB sanity cap (`MAX_FILE_BYTES`); streamed, never buffered in RAM |
| Max size (PC → phone) | no hard cap; streamed, never buffered in RAM |
| Unknown source size (phone) | send refused — the length-prefixed header needs an exact count |
| Source shorter than declared | send aborts and drops the connection; the header already promised N bytes, so continuing would desync |
| Path traversal | PC sanitises the name; `/`, `..`, control chars stripped |
| Name collisions | both sides de-dupe → `file (1).ext` |
| Partial transfer (PC) | written to a temp `.part`, atomically moved only when complete |
| Write fails (phone) | bytes still drained, replies `PUSHERR`, connection stays valid |
| Stream desync (PC) | connection dropped deliberately — safer than guessing |
| No phone connected | "Send file to phone" button is disabled; `pushFile` reports an error |
| Concurrent transfers | serialised by the write lock; the app also disables the button mid-upload |
| Auth | the pairing token gates the whole connection; unpaired peers get `DENIED` |

> **Watch the `L`.** `MAX_FILE_BYTES` must be a `Long` literal
> (`5500L * 1024 * 1024`). With plain `Int` literals the arithmetic silently
> overflows and yields a much smaller, nonsensical cap.

**Not encrypted.** Traffic is plaintext on your LAN, protected by the pairing
token (which stops other devices *using* your PC) — but anyone sniffing the
network could read a transferred file. Fine for a home LAN; TLS would be the
upgrade for anything more.

---

## 8. Where to look in the code

| Concern | File |
|---|---|
| Wire format + `readLine` (PC) | `PC_Server/.../net/Protocol.java` |
| Wire format + command builders (phone) | `Android_app/.../net/Protocol.kt` |
| Dispatch, `pushFile`, `ClientLink` | `PC_Server/.../server/ControlServer.java` |
| Saving a received file (PC) | `PC_Server/.../transfer/FileReceiver.java` |
| Send button + chooser (PC) | `PC_Server/.../ui/MainWindow.java` |
| GUI → daemon `SENDFILE` hop | `PC_Server/.../ipc/GuiIpcClient.java`, `ipc/DaemonIpc.java` |
| Reader, streaming `sendFile`, `saveToDownloads` | `Android_app/.../net/RemoteClient.kt` |
| File picker, size lookup, progress bar, toast | `Android_app/.../MainActivity.kt` |
