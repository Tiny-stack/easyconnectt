package com.easyconnect.pcserver.ipc;

import com.easyconnect.pcserver.server.ServerListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The daemon side of the GUI split. Runs a UNIX-domain-socket control channel
 * that GUI subprocesses and the tray connect to.
 *
 * <p>A client announces its role on the first line:
 * <ul>
 *   <li>{@code GUI} — a pairing window. Gets a {@code HELLO} snapshot, is
 *       registered as an open window, and receives the event stream. May be told
 *       to {@code CLOSE}.</li>
 *   <li>{@code TOGGLE} — a tray click. If any window is open, all are closed;
 *       otherwise a new one is spawned. Gives Telegram-style show/hide from one
 *       icon instead of piling up windows.</li>
 *   <li>{@code QUIT} — shut the daemon down.</li>
 * </ul>
 *
 * <p>Deliberately touches no AWT — this runs inside the lean headless daemon.
 */
public final class DaemonIpc implements ServerListener, AutoCloseable {

    private static final long TOGGLE_DEBOUNCE_NANOS = 500_000_000L; // ignore bounces < 500 ms

    private final String token;
    private final Path filesDir;
    private final Path socketPath;

    // Writers for currently-open GUI windows (each an attached subprocess).
    private final CopyOnWriteArrayList<Writer> guiClients = new CopyOnWriteArrayList<>();
    private volatile ServerSocketChannel server;

    // Snapshot state so a window opened mid-session shows the right thing.
    private volatile int port;
    private volatile String connectedRemote; // null when waiting
    private volatile Runnable onQuit = () -> {};
    private volatile Runnable guiSpawner = () -> {};
    private volatile Consumer<Path> sendFileHandler = p -> {};
    private volatile long lastToggleNanos;

    public DaemonIpc(String token, int port, Path filesDir) {
        this.token = token;
        this.port = port;
        this.filesDir = filesDir;
        this.socketPath = IpcPaths.socket();
    }

    /** Callback invoked when a client asks the daemon to quit. */
    public void onQuit(Runnable r) {
        this.onQuit = r;
    }

    /** How the daemon opens a new pairing window (spawns the {@code gui} subprocess). */
    public void setGuiSpawner(Runnable r) {
        this.guiSpawner = r;
    }

    /** How the daemon sends a PC-side file to the phone (usually ControlServer::pushFile). */
    public void setSendFileHandler(Consumer<Path> handler) {
        this.sendFileHandler = handler;
    }

    public Path socketPath() {
        return socketPath;
    }

    /** Binds the socket and starts accepting clients. */
    public void start() throws IOException {
        Files.deleteIfExists(socketPath);
        ServerSocketChannel ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ssc.bind(UnixDomainSocketAddress.of(socketPath));
        this.server = ssc;
        Thread t = new Thread(this::acceptLoop, "ipc-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (server != null && server.isOpen()) {
            try {
                SocketChannel ch = server.accept();
                Thread t = new Thread(() -> serveClient(ch), "ipc-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (server != null && server.isOpen()) {
                    System.err.println("[ipc] accept failed: " + e.getMessage());
                }
                return; // socket closed — daemon shutting down
            }
        }
    }

    /** Reads the client's role announcement and dispatches accordingly. */
    private void serveClient(SocketChannel ch) {
        try (ch) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(
                    Channels.newOutputStream(ch), StandardCharsets.UTF_8);
            String role = in.readLine();
            if (role == null) {
                return;
            }
            switch (role.trim()) {
                case "GUI" -> serveGui(in, out);
                case "TOGGLE" -> handleToggle();
                case "QUIT" -> onQuit.run();
                default -> { /* unknown role — drop */ }
            }
        } catch (IOException ignored) {
            // client vanished
        }
    }

    /** Serves an attached pairing window: snapshot, then stream until it closes. */
    private void serveGui(BufferedReader in, Writer out) throws IOException {
        send(out, "HELLO " + token + " " + port + " " + filesDir);
        send(out, connectedRemote != null ? "CONNECTED " + connectedRemote : "LISTENING " + port);
        guiClients.add(out);
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if ("QUIT".equals(trimmed)) {
                    onQuit.run();
                    return;
                }
                if (trimmed.startsWith("SENDFILE ")) {
                    // Rest of the line is a PC filesystem path (may contain spaces).
                    sendFileHandler.accept(Path.of(trimmed.substring("SENDFILE ".length())));
                }
            }
        } finally {
            guiClients.remove(out);
        }
    }

    /** Tray click: hide (close) any open window, else show (spawn) one. */
    private synchronized void handleToggle() {
        long now = System.nanoTime();
        if (now - lastToggleNanos < TOGGLE_DEBOUNCE_NANOS) {
            return; // debounce accidental double-clicks
        }
        lastToggleNanos = now;
        if (!guiClients.isEmpty()) {
            for (Writer w : guiClients) {
                try {
                    send(w, "CLOSE");
                } catch (IOException ignored) {
                    guiClients.remove(w);
                }
            }
        } else {
            guiSpawner.run();
        }
    }

    private void broadcast(String message) {
        for (Writer w : guiClients) {
            try {
                send(w, message);
            } catch (IOException e) {
                guiClients.remove(w); // drop dead client
            }
        }
    }

    private static void send(Writer w, String message) throws IOException {
        synchronized (w) {
            w.write(message);
            w.write('\n');
            w.flush();
        }
    }

    // --- ServerListener: fan every event out to open windows ---

    @Override
    public void onListening(int p) {
        this.port = p;
        broadcast("LISTENING " + p);
    }

    @Override
    public void onClientConnected(String remote) {
        this.connectedRemote = remote;
        broadcast("CONNECTED " + remote);
    }

    @Override
    public void onClientRejected(String remote) {
        broadcast("REJECTED " + remote);
    }

    @Override
    public void onClientDisconnected(String remote) {
        this.connectedRemote = null;
        broadcast("DISCONNECTED " + remote);
    }

    @Override
    public void onFileReceived(Path file) {
        broadcast("FILE " + file);
    }

    @Override
    public void onError(String message) {
        broadcast("ERROR " + message);
    }

    /** True if at least one pairing window is currently open. */
    public boolean hasClients() {
        return !guiClients.isEmpty();
    }

    @Override
    public void close() {
        try {
            ServerSocketChannel s = server;
            server = null;
            if (s != null) {
                s.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
