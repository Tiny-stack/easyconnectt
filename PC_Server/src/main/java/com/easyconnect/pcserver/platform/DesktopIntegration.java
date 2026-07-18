package com.easyconnect.pcserver.platform;

import com.easyconnect.pcserver.server.ServerListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lightweight desktop hooks for the daemon: display detection and transient
 * notification pop-ups. Everything here is best-effort and cheap.
 *
 * <p>Notifications are delivered with the platform's built-in, dependency-free
 * mechanism: {@code notify-send} on Linux and a PowerShell Windows-Runtime toast
 * on Windows. macOS is a no-op for now (future work).
 *
 * <p>There is intentionally NO persistent tray icon: the only cross-platform,
 * dependency-free option (GTK's {@code yad}) pulls in ~60 MB of GTK, which would
 * more than double the daemon's footprint and undo the whole memory effort.
 * Reopening a closed window is handled instead by relaunching the app, which
 * toggles the running daemon's window (see {@code App.toggleIfDaemonRunning}).
 */
public final class DesktopIntegration {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

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
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
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

    /** Raises a transient desktop notification via the platform's native tool. */
    private void notifySend(String title, String body) {
        if (IS_WINDOWS) {
            notifyWindows(title, body);
        } else {
            notifyLinux(title, body);
        }
    }

    private void notifyLinux(String title, String body) {
        if (!hasCommand("notify-send")) {
            return; // no libnotify tool present — nothing to do
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

    /**
     * Windows 10/11 toast via the built-in Windows Runtime notification API,
     * driven from PowerShell — no third-party module (e.g. BurntToast) required.
     * The title/body are passed through the environment ({@code PCR_TITLE} /
     * {@code PCR_BODY}) rather than interpolated into the script, so text with
     * quotes or {@code $}/backtick characters can't break or inject into it.
     */
    private void notifyWindows(String title, String body) {
        String script =
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] > $null;" +
            "$t=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);" +
            "$n=$t.GetElementsByTagName('text');" +
            "$n.Item(0).AppendChild($t.CreateTextNode($env:PCR_TITLE)) > $null;" +
            "$n.Item(1).AppendChild($t.CreateTextNode($env:PCR_BODY)) > $null;" +
            "$toast=[Windows.UI.Notifications.ToastNotification]::new($t);" +
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('PC Remote').Show($toast);";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
            pb.environment().put("PCR_TITLE", title == null ? "" : title);
            pb.environment().put("PCR_BODY", body == null ? "" : body);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
        } catch (IOException ignored) {
            // best effort — powershell missing or blocked
        }
    }
}
