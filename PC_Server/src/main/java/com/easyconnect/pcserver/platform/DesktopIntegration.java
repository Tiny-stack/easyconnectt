package com.easyconnect.pcserver.platform;

import com.easyconnect.pcserver.server.ServerListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lightweight desktop hooks for the daemon: display detection and transient
 * {@code notify-send} pop-ups. Everything here is best-effort and cheap.
 *
 * <p>There is intentionally NO persistent tray icon: the only cross-platform,
 * dependency-free option (GTK's {@code yad}) pulls in ~60 MB of GTK, which would
 * more than double the daemon's footprint and undo the whole memory effort.
 * Reopening a closed window is handled instead by relaunching the app, which
 * toggles the running daemon's window (see {@code App.toggleIfDaemonRunning}).
 */
public final class DesktopIntegration {

    /** True if there's a graphical session at all (else: pure headless, skip UI). */
    public static boolean hasDisplay() {
        String os = System.getProperty("os.name", "").toLowerCase();
        // Windows and macOS have a window server whenever a user is logged in and
        // expose no X11/Wayland env var to probe — so assume a display is present.
        // (Only a truly headless server login lacks one, and that's the rare case;
        // pass --nogui there.) Checking os.name avoids loading AWT.
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        // Linux / other X11/Wayland Unix: a graphical session sets one of these.
        return notBlank(System.getenv("WAYLAND_DISPLAY")) || notBlank(System.getenv("DISPLAY"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean hasCommand(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    /** A ServerListener that raises desktop notifications for key events. */
    public ServerListener notifications() {
        return new ServerListener() {
            @Override public void onListening(int port) { }
            @Override public void onClientConnected(String remote) {
                notifySend("Phone connected", remote);
            }
            @Override public void onClientRejected(String remote) {
                notifySend("Pairing rejected", "Wrong code from " + remote);
            }
            @Override public void onClientDisconnected(String remote) {
                notifySend("Phone disconnected", remote);
            }
            @Override public void onFileReceived(Path file) {
                notifySend("File received", String.valueOf(file.getFileName()));
            }
            @Override public void onError(String message) { }
        };
    }

    private void notifySend(String title, String body) {
        if (!hasCommand("notify-send")) {
            return; // Linux only; on Win/macOS this is a no-op (native notifications: future work)
        }
        try {
            new ProcessBuilder("notify-send", "-a", "PC Remote", title, body)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
