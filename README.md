# PC Remote

Control your PC from your phone — move the cursor, click, scroll, type, and send
files. The phone pairs by **scanning a QR code** shown on the PC, so only your
device can connect (other phones on the same Wi-Fi are locked out).

## Components

| Folder | What it is | Stack |
|---|---|---|
| [PC_Server/](PC_Server/) | Desktop server that receives input & files | Java 21, JPMS module, Swing UI, `java.awt.Robot` |
| [Android_app/](Android_app/) | Phone app: QR scanner + touchpad | Kotlin, Jetpack Compose, ZXing |
| [PROTOCOL.md](PROTOCOL.md) | The wire protocol both sides implement | — |

## Features

- **Pointer control** — trackpad-style drag to move, tap / double-tap / hold for
  left / double / right click, scroll buttons.
- **Typing** — send text to the PC.
- **File transfer** — push a file from the phone into `~/PC Remote Files/`.
- **QR pairing** — connection is gated by a per-session token embedded in the QR.
- **Small builds** — PC ships a jlink'd runtime (~52 MB vs ~300 MB JDK); Android
  APK is R8-minified and resource-shrunk.

## How to run

### PC server
```bash
cd PC_Server
./gradlew run                 # from source
# or a self-contained build:
./gradlew jpackageImage       # build/jpackage/PCServer/bin/PCServer
```
A window shows the pairing QR. (Headless? it prints an ASCII QR instead.)

### Android app
```bash
cd Android_app
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/
# install on a connected device:
./gradlew :app:installRelease
```
Open the app → **Scan QR to connect** → point at the PC window → control it.

> Both devices must be on the **same network**, and the PC firewall must allow
> the server's TCP port (default **5555**).

## Cross-platform builds

- **PC:** `jpackage` app-image works on Linux and Windows. For OS installers,
  change `--type` in `PC_Server/build.gradle` to `deb`/`rpm` (Linux) or
  `msi`/`exe` (Windows). Build on the target OS (jpackage isn't a cross-compiler).
- **Android:** the same APK runs on Linux-built and Windows-built machines; the
  Android toolchain is cross-platform.

## Roadmap

- Trusted devices (persist the token to auto-reconnect without rescanning).
- TLS / encrypted transport.
- Two-finger scroll gesture on the trackpad surface.
