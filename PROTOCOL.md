# PC Remote — wire protocol

Shared contract between the PC server ([PC_Server/](PC_Server/)) and the Android
app ([Android_app/](Android_app/)). Keep both sides in sync with this file.

## Transport

Plain **TCP**. Control messages are UTF-8 **text lines** terminated by `\n`.
File transfer switches to raw bytes for exactly the declared length, then
returns to line mode on the same socket.

## Pairing

The PC shows a QR encoding:

```
tcr://<host>:<port>/<tokenHex>
```

- `host` / `port` — the server's LAN address (auto-detected site-local IPv4).
- `tokenHex` — a fresh random 128-bit secret, regenerated every server launch.

The phone scans it, connects, and must present the token. Any device that
didn't scan the QR has the wrong token and is rejected — so other phones on the
same Wi-Fi cannot control the PC.

## Handshake (version-negotiated)

```
client -> HELLO <tokenHex> [v<n>] [caps=a,b,…]
server -> OK v<n> caps=a,b,…   (token matches; session continues)
server -> DENIED               (wrong token; server closes the socket)
```

- Token comparison on the server is constant-time. The token is the **second
  whitespace field**; any trailing `v<n>` / `caps=…` fields are optional and are
  ignored by auth, so future clients can announce more without breaking older
  servers.
- The client treats **any reply starting with `OK`** as success and parses the
  version + comma-separated capabilities that follow. This is the forward-compat
  rule: a newer server advertising extra capabilities (e.g. `video`, `gamepad`)
  never looks "denied" to an older client — it just ignores caps it doesn't know.
- Current version is **`v1`**; capabilities advertised today: `input`, `file`,
  and `hscroll` (horizontal scroll — only when the server's uinput backend is
  active, since `java.awt.Robot` has no horizontal wheel). Add new capability
  tokens here as features land, and gate optional features on the peer
  advertising them.

> **Upgrade note:** the v0→v1 change to the handshake line requires the PC server
> and phone app to be rebuilt together. From v1 onward, version skew is graceful.

## Input commands (client → server, no reply)

| Line              | Action                                  |
|-------------------|-----------------------------------------|
| `M <dx> <dy>`     | Relative mouse move                     |
| `MA <x> <y>`      | Absolute mouse move                     |
| `C <btn>`         | Click (`btn` = `L` \| `R` \| `M`)       |
| `DC <btn>`        | Double click                            |
| `DN <btn>`        | Button press (start drag)               |
| `UP <btn>`        | Button release (end drag)               |
| `SC <amount>`     | Scroll wheel (negative = up)            |
| `SCH <amount>`    | Horizontal scroll (negative = left) — only if server advertised `hscroll` |
| `K <text…>`       | Type unicode text (rest of the line)    |
| `PING`            | Server replies `PONG`                   |

## File transfer (client → server)

```
client -> FILE <sizeBytes> <nameUrlEncoded>
client -> <sizeBytes raw bytes>
server -> FILEOK <savedName>      (saved to ~/PC Remote Files/)
server -> FILEERR <reason>        (on failure; connection then dropped)
```

## Security notes / roadmap

- Current MVP is plaintext on the LAN, gated by the session token — enough to
  keep other devices out, but not encrypted.
- **Trusted devices (future):** persist the token (or a long-lived per-device
  key) on the phone so a paired device can auto-reconnect without rescanning.
- **Encryption (future):** wrap the socket in TLS, or do an ECDH handshake keyed
  by a secret embedded in the QR, to defend against a LAN eavesdropper.
