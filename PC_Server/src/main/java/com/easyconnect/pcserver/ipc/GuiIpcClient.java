package com.easyconnect.pcserver.ipc;

import com.easyconnect.pcserver.pairing.Pairing;
import com.easyconnect.pcserver.server.ServerListener;
import com.easyconnect.pcserver.ui.MainWindow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The GUI subprocess. Connects to the daemon's control socket, rebuilds the
 * pairing info from the {@code HELLO} snapshot, shows the Swing window, and
 * drives it from the daemon's event stream.
 *
 * <p>This is the ONLY process that loads AWT. When the user closes the window,
 * {@code MainWindow}'s {@code EXIT_ON_CLOSE} ends this process and the OS
 * reclaims all of its ~25 MB — the daemon keeps running untouched.
 */
public final class GuiIpcClient {

    private final Path socketPath;
    private MainWindow window;
    private Writer out;

    public GuiIpcClient() {
        this.socketPath = IpcPaths.socket();
    }

    /** Connects and runs the event loop on the caller's thread until the socket closes. */
    public void run() throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socketPath));
        // Announce our role so the daemon registers us as an open window.
        out = new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8);
        out.write("GUI\n");
        out.flush();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                dispatch(line);
            }
        }
        // Socket closed => daemon is gone. Nothing to show; exit the subprocess.
        System.exit(0);
    }

    private void dispatch(String line) {
        int sp = line.indexOf(' ');
        String verb = sp < 0 ? line : line.substring(0, sp);
        String rest = sp < 0 ? "" : line.substring(sp + 1);
        switch (verb) {
            case "HELLO" -> hello(rest);
            case "LISTENING" -> withWindow(w -> w.onListening(parseInt(rest)));
            case "CONNECTED" -> withWindow(w -> w.onClientConnected(rest));
            case "REJECTED" -> withWindow(w -> w.onClientRejected(rest));
            case "DISCONNECTED" -> withWindow(w -> w.onClientDisconnected(rest));
            case "FILE" -> withWindow(w -> w.onFileReceived(Path.of(rest)));
            case "ERROR" -> withWindow(w -> w.onError(rest));
            case "CLOSE" -> System.exit(0); // tray toggled us off — exit, freeing this process's AWT
            default -> { /* ignore unknown verbs (forward-compat) */ }
        }
    }

    /** HELLO &lt;token&gt; &lt;port&gt; &lt;filesDir...&gt; — filesDir may contain spaces, so it is the tail. */
    private void hello(String rest) {
        String[] p = rest.split(" ", 3);
        if (p.length < 3) {
            return;
        }
        String token = p[0];
        int port = parseInt(p[1]);
        Path filesDir = Path.of(p[2]);
        Pairing pairing = Pairing.of(token, port);
        window = new MainWindow(pairing, filesDir, this::sendQuit, this::sendFileToPhone);
        window.show();
    }

    /** "Quit server" button: tell the daemon to shut down. It then closes the
     *  socket, which ends this process too. */
    private void sendQuit() {
        try {
            out.write("QUIT\n");
            out.flush();
        } catch (IOException e) {
            System.exit(0); // daemon already gone
        }
    }

    /** "Send file to phone" button: hand the chosen file's path to the daemon,
     *  which pushes it over the phone connection. */
    private void sendFileToPhone(Path file) {
        try {
            out.write("SENDFILE " + file + "\n");
            out.flush();
        } catch (IOException e) {
            System.err.println("[gui] send-to-phone failed: " + e.getMessage());
        }
    }

    private void withWindow(java.util.function.Consumer<ServerListener> action) {
        MainWindow w = window;
        if (w != null) {
            action.accept(w);
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
