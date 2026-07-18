package com.easyconnect.pcserver;

import com.easyconnect.pcserver.input.InputController;
import com.easyconnect.pcserver.ipc.DaemonIpc;
import com.easyconnect.pcserver.ipc.GuiIpcClient;
import com.easyconnect.pcserver.ipc.IpcPaths;
import com.easyconnect.pcserver.pairing.Pairing;
import com.easyconnect.pcserver.platform.DesktopIntegration;
import com.easyconnect.pcserver.platform.SelfLauncher;
import com.easyconnect.pcserver.server.CompositeListener;
import com.easyconnect.pcserver.server.ControlServer;
import com.easyconnect.pcserver.server.ForwardingListener;
import com.easyconnect.pcserver.transfer.FileReceiver;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PC Remote server entry point. Runs in one of several modes:
 *
 * <ul>
 *   <li><b>daemon</b> (default, on a desktop) — the lean, AWT-free server plus a
 *       native tray icon and desktop notifications. Spawns the Swing pairing
 *       window as a separate {@code --gui} process so its ~25 MB of AWT can be
 *       reclaimed by simply closing the window; reopen it from the tray.</li>
 *   <li><b>--gui</b> — the Swing window subprocess; connects to the daemon over a
 *       UNIX socket. The only process that loads AWT.</li>
 *   <li><b>--nogui</b> — pure headless server (prints the QR as text). Also the
 *       automatic fallback when no graphical session is present.</li>
 *   <li><b>--selftest</b> — pointer self-check.</li>
 * </ul>
 */
public final class App {

    private static final int DEFAULT_PORT = 5555;
    private static final String MODULE = "com.easyconnect.pcserver";
    private static final String MAIN_CLASS = "com.easyconnect.pcserver.App";

    private App() {
    }

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--selftest")) {
            selfTest();
            return;
        }
        // GUI subprocess role. Triggered by a BAREWORD "gui" subcommand, not
        // "--gui": the jpackage/JDK app launcher intercepts "--gui" as a JVM
        // option ("Unrecognized option") and refuses to start, while bareword
        // args are forwarded to the app cleanly. "--gui" is still accepted for
        // launches straight through java.
        if (hasFlag(args, "gui") || hasFlag(args, "--gui")) {
            runGuiSubprocess();
            return;
        }
        // Tray click: ask the running daemon to show/hide the window, then exit.
        if (hasFlag(args, "toggle")) {
            runToggle();
            return;
        }

        // Option B: if a daemon is already running, a second GUI launch (e.g. the
        // user clicking the app icon again) just toggles its window instead of
        // starting a duplicate server. Works on every platform — no tray needed.
        if (!hasFlag(args, "--nogui") && DesktopIntegration.hasDisplay() && toggleIfDaemonRunning()) {
            return;
        }

        int port = intFlag(args, "--port", DEFAULT_PORT);
        Path filesDir = Paths.get(System.getProperty("user.home"), "PC Remote Files");
        InputController input = new InputController();
        FileReceiver files = new FileReceiver(filesDir);

        String token = Pairing.randomToken();
        ForwardingListener listener = new ForwardingListener();
        ControlServer server = new ControlServer(token, input, files, listener);

        int boundPort = server.start(port);
        Pairing pairing = Pairing.of(token, boundPort);

        System.out.println("PC Remote server");
        System.out.println("  address : " + pairing.host() + ":" + pairing.port());
        System.out.println("  files   : " + filesDir);
        System.out.println("  input   : " + (input.isAvailable() ? "ready (" + input.backend() + ")" : "UNAVAILABLE (headless)"));
        System.out.println("  pairing : " + pairing.qrPayload());

        // Headless when explicitly asked, or when there's no graphical session.
        // hasDisplay() only reads env vars, so it never loads AWT.
        boolean headless = hasFlag(args, "--nogui") || !DesktopIntegration.hasDisplay();
        if (headless) {
            runHeadless(server, input, pairing);
        } else {
            runDaemon(listener, server, input, token, boundPort, filesDir, pairing);
        }
    }

    /**
     * Pure headless keep-alive: print the ASCII QR and block, serving input over
     * the network with no pairing window or control socket. Used for {@code
     * --nogui}, truly headless logins, and as the graceful fallback when the
     * daemon's control socket can't be opened (see {@link #runDaemon}).
     */
    private static void runHeadless(ControlServer server, InputController input, Pairing pairing) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            input.close();
        }));
        System.out.println();
        System.out.println(pairing.qrAscii());
        System.out.println("Scan the QR above with the PC Remote app. Ctrl+C to quit.");
        try {
            Thread.currentThread().join(); // keep the accept-thread alive
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Desktop daemon: server + IPC channel + tray + notifications, GUI spawned as
     * a separate process. Blocks until the process is killed. If the control
     * socket can't be opened — e.g. AF_UNIX unsupported (Wine, pre-1809 Windows) —
     * falls back to {@link #runHeadless} instead of crashing the already-running
     * server; the phone still connects and controls input, just with no window.
     */
    private static void runDaemon(ForwardingListener listener, ControlServer server,
                                  InputController input, String token, int boundPort,
                                  Path filesDir, Pairing pairing) throws Exception {
        DaemonIpc ipc = new DaemonIpc(token, boundPort, filesDir);
        try {
            ipc.start();
        } catch (Exception e) {
            System.err.println("[daemon] control socket unavailable (" + e.getMessage()
                    + ") — falling back to headless mode (no pairing window).");
            runHeadless(server, input, pairing);
            return;
        }
        ipc.onQuit(() -> System.exit(0));

        // How the daemon opens a pairing window: spawn the "gui" subprocess.
        SelfLauncher launcher = new SelfLauncher(MODULE, MAIN_CLASS);
        Runnable spawnGui = () -> {
            try {
                launcher.spawn("gui");
            } catch (IOException e) {
                System.err.println("[daemon] could not open the pairing window: " + e.getMessage());
            }
        };
        ipc.setGuiSpawner(spawnGui);
        // GUI "Send file to phone" -> daemon -> push over the phone connection.
        ipc.setSendFileHandler(server::pushFile);

        DesktopIntegration desktop = new DesktopIntegration();
        listener.setDelegate(new CompositeListener(ipc, desktop.notifications()));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            input.close();
            ipc.close();
        }));

        // Open the initial pairing window. There's no tray (it would cost ~60 MB);
        // relaunching the app toggles this window instead (Option B).
        spawnGui.run();

        System.out.println("[daemon] running (AWT-free). Close the window to hide it; "
                + "launch the app again to reopen. Use the window's Quit button to stop the server.");
        Thread.currentThread().join();
    }

    /** Tray toggle: connect to the daemon and ask it to show/hide the window. */
    private static void runToggle() {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(IpcPaths.socket()));
            ch.write(StandardCharsets.UTF_8.encode("TOGGLE\n"));
        } catch (IOException e) {
            System.err.println("[toggle] cannot reach daemon: " + e.getMessage());
        }
    }

    /**
     * If a daemon is already listening on the control socket, toggles its window
     * and returns true; otherwise (no daemon, or a stale socket file) returns
     * false so the caller starts a fresh daemon. A real connect attempt is used,
     * not just a file check, so a leftover socket from a crash doesn't block startup.
     */
    private static boolean toggleIfDaemonRunning() {
        Path sock = IpcPaths.socket();
        if (!java.nio.file.Files.exists(sock)) {
            return false;
        }
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(sock));
            ch.write(StandardCharsets.UTF_8.encode("TOGGLE\n"));
            System.out.println("[launch] PC Remote is already running — toggled its window.");
            return true;
        } catch (IOException e) {
            return false; // stale socket / no daemon — start fresh
        }
    }

    /** GUI subprocess: connect to the running daemon and show the pairing window. */
    private static void runGuiSubprocess() {
        try {
            new GuiIpcClient().run();
        } catch (IOException e) {
            System.err.println("[gui] cannot reach the daemon socket: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Moves the pointer via the active backend and reports whether it actually moved. */
    private static void selfTest() throws Exception {
        InputController input = new InputController();
        System.out.println("backend: " + input.backend());
        java.awt.Point before = java.awt.MouseInfo.getPointerInfo().getLocation();
        System.out.println("before: " + before);
        for (int i = 0; i < 10; i++) {
            input.moveRelative(15, 12);
            Thread.sleep(60);
        }
        java.awt.Point after = java.awt.MouseInfo.getPointerInfo().getLocation();
        System.out.println("after : " + after);
        System.out.println("moved : " + (!before.equals(after)) + "  (delta "
                + (after.x - before.x) + "," + (after.y - before.y) + ")");
        input.close();
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static int intFlag(String[] args, String flag, int def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }
}
