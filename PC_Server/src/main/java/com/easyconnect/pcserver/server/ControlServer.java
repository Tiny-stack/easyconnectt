package com.easyconnect.pcserver.server;

import com.easyconnect.pcserver.input.InputController;
import com.easyconnect.pcserver.net.Protocol;
import com.easyconnect.pcserver.transfer.FileReceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accepts phone connections, enforces token auth, then dispatches input and
 * file-transfer commands. One handler thread per connection.
 */
public final class ControlServer {

    private final String token;
    private final InputController input;
    private final FileReceiver files;
    private final ServerListener listener;

    private static final boolean DEBUG = Boolean.getBoolean("pcremote.debug");
    private long moveLogCount;

    // The currently-connected phone we can push files to (PC -> phone). Assumes a
    // single controlling phone; a newer connection replaces the older one.
    private volatile ClientLink active;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile int port = -1;

    public ControlServer(String token, InputController input, FileReceiver files, ServerListener listener) {
        this.token = token;
        this.input = input;
        this.files = files;
        this.listener = listener;
    }

    /**
     * Binds {@code preferredPort} (falling back to an ephemeral port if taken)
     * and starts the accept loop on a background thread.
     *
     * @return the port actually bound.
     */
    public int start(int preferredPort) throws IOException {
        ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(preferredPort));
        } catch (BindException be) {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(0));
        }
        this.serverSocket = ss;
        this.port = ss.getLocalPort();
        running.set(true);
        listener.onListening(port);

        Thread accept = new Thread(this::acceptLoop, "control-accept");
        accept.setDaemon(true);
        accept.start();
        return port;
    }

    public int port() {
        return port;
    }

    public void stop() {
        running.set(false);
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handle(client), "control-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    listener.onError("accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        ClientLink link = null;
        boolean dataChannel = false;
        try (socket) {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String hello = Protocol.readLine(in);
            if (hello == null || !authorized(hello)) {
                reply(out, Protocol.DENIED);
                listener.onClientRejected(remote);
                return;
            }
            // A data channel is a throwaway second connection carrying one bulk
            // upload. It must not become the push target, and it must not look
            // like the phone connecting/disconnecting to the UI.
            dataChannel = isDataChannel(hello);
            link = new ClientLink(out);
            if (!dataChannel) {
                this.active = link; // this phone can now receive PC -> phone pushes
            }
            link.send(Protocol.okLine(capabilities()));
            if (!dataChannel) {
                listener.onClientConnected(remote);
            }

            String line;
            while (running.get() && (line = Protocol.readLine(in)) != null) {
                dispatch(line, in, link);
            }
        } catch (IOException e) {
            // client dropped
        } finally {
            if (!dataChannel) {
                if (active == link) {
                    active = null; // only clear if a newer client hasn't replaced us
                }
                listener.onClientDisconnected(remote);
            }
        }
    }

    /** True if the HELLO marks this connection as a bulk-upload data channel. */
    private static boolean isDataChannel(String hello) {
        for (String field : hello.split("\\s+")) {
            if (Protocol.DATA.equals(field)) {
                return true;
            }
        }
        return false;
    }

    /** Capabilities this server advertises, gated by what the input backend can do. */
    private String[] capabilities() {
        java.util.List<String> caps = new java.util.ArrayList<>();
        caps.add(Protocol.CAP_INPUT);
        caps.add(Protocol.CAP_FILE);
        caps.add(Protocol.CAP_PUSH); // this server can send files to the phone
        if (input.supportsHorizontalScroll()) {
            caps.add(Protocol.CAP_HSCROLL);
        }
        return caps.toArray(new String[0]);
    }

    private boolean authorized(String hello) {
        // "HELLO <token> [v<n>] [caps=...]" — the token is the second field; any
        // trailing version/capability fields are optional and ignored here, so
        // future clients can announce more without breaking auth.
        String[] parts = hello.split("\\s+");
        return parts.length >= 2
                && Protocol.HELLO.equals(parts[0])
                && constantTimeEquals(token, parts[1]);
    }

    private void dispatch(String line, InputStream in, ClientLink link) throws IOException {
        if (line.isEmpty()) {
            return;
        }
        String[] p = line.split("\\s+");
        if (DEBUG && ("M".equals(p[0]) || "MA".equals(p[0]))) {
            if (moveLogCount < 15 || moveLogCount % 60 == 0) {
                System.out.println("[recv] " + line + "   (#" + moveLogCount + ")");
            }
            moveLogCount++;
        }
        switch (p[0]) {
            case "M"  -> input.moveRelative(intAt(p, 1), intAt(p, 2));
            case "MA" -> input.moveAbsolute(intAt(p, 1), intAt(p, 2));
            case "C"  -> input.click(argAt(p, 1, "L"));
            case "DC" -> input.doubleClick(argAt(p, 1, "L"));
            case "DN" -> input.press(argAt(p, 1, "L"));
            case "UP" -> input.release(argAt(p, 1, "L"));
            case "SC" -> input.scroll(intAt(p, 1));
            case "SCH" -> input.scrollHorizontal(intAt(p, 1));
            case "K"  -> input.type(line.length() > 2 ? line.substring(2) : "");
            case "PING" -> link.trySend(Protocol.PONG); // droppable; skipped during a push
            case Protocol.FILE -> receiveFile(p, in, link);
            // Acknowledgements for a PC -> phone push (see pushFile).
            case Protocol.PUSH_OK -> System.out.println("[push] phone saved: " + argAt(p, 1, "?"));
            case Protocol.PUSH_ERR -> listener.onError("phone couldn't save the file: " + argAt(p, 1, "?"));
            default -> { /* ignore unknown */ }
        }
    }

    private void receiveFile(String[] p, InputStream in, ClientLink link) throws IOException {
        if (p.length < 3) {
            link.send(Protocol.FILE_ERR + " bad-header");
            return;
        }
        long size;
        try {
            size = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            link.send(Protocol.FILE_ERR + " bad-size");
            return;
        }
        String name = URLDecoder.decode(p[2], StandardCharsets.UTF_8);
        try {
            Path saved = files.receive(in, name, size);
            listener.onFileReceived(saved);
            link.send(Protocol.FILE_OK + " " + saved.getFileName());
        } catch (IOException e) {
            link.send(Protocol.FILE_ERR + " " + e.getMessage());
            throw e; // stream position is now unreliable — drop the connection
        }
    }

    /**
     * Sends a file from the PC to the currently-connected phone (PC -> phone).
     * Returns false (and notifies the UI) if no phone is connected.
     */
    public boolean pushFile(Path file) {
        ClientLink link = active;
        if (link == null) {
            listener.onError("No phone connected — can't send the file.");
            return false;
        }
        try {
            link.pushFile(file);
            System.out.println("[push] sent " + file.getFileName() + " to phone");
            return true;
        } catch (IOException e) {
            listener.onError("Send to phone failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * A connected client we can write to — replies AND server-initiated pushes.
     * All writes are serialised on one lock so a push and a reply (which run on
     * different threads) never interleave on the socket.
     *
     * The lock is a {@link ReentrantLock} rather than a monitor so a low-priority
     * reply (PONG) can be attempted with {@code tryLock} and skipped when a push
     * is streaming — otherwise the client handler thread would block for the whole
     * transfer waiting to answer a heartbeat, and stop reading/injecting mouse.
     */
    private static final class ClientLink {
        private final OutputStream out;
        private final java.util.concurrent.locks.ReentrantLock writeLock =
                new java.util.concurrent.locks.ReentrantLock();

        ClientLink(OutputStream out) {
            this.out = out;
        }

        void send(String lineText) throws IOException {
            writeLock.lock();
            try {
                out.write((lineText + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Sends only if the lock is free right now — used for PONG so a heartbeat
         * during a push is silently skipped instead of blocking the reader. The
         * phone treats the push bytes it's receiving as proof of life anyway.
         */
        void trySend(String lineText) {
            if (!writeLock.tryLock()) {
                return; // a push is streaming; skip this heartbeat reply
            }
            try {
                out.write((lineText + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // best effort
            } finally {
                writeLock.unlock();
            }
        }

        void pushFile(Path file) throws IOException {
            long size = Files.size(file);
            String name = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8);
            writeLock.lock();
            try {
                out.write((Protocol.PUSH + " " + size + " " + name + "\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream fin = Files.newInputStream(file)) {
                    fin.transferTo(out); // stream, don't buffer the whole file
                }
                out.flush();
            } finally {
                writeLock.unlock();
            }
        }
    }

    private static void reply(OutputStream out, String msg) throws IOException {
        out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static int intAt(String[] a, int i) {
        try {
            return i < a.length ? Integer.parseInt(a[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String argAt(String[] a, int i, String def) {
        return i < a.length ? a[i] : def;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length && i < y.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}
