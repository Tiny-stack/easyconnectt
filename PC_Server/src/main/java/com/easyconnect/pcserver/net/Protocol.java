package com.easyconnect.pcserver.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wire protocol shared with the Android client.
 *
 * <p>Transport: plain TCP. All control messages are UTF-8 text lines terminated
 * by {@code '\n'}. File transfer switches to raw bytes for exactly the declared
 * length, then returns to line mode.
 *
 * <pre>
 * Handshake (version-negotiated):
 *   client -> "HELLO &lt;tokenHex&gt; [v&lt;n&gt;] [data] [caps=a,b,...]"  extra fields optional
 *   server -> "OK v&lt;n&gt; caps=a,b,..."  | "DENIED"
 *
 * A HELLO carrying the bare word "data" marks a short-lived <em>data channel</em>:
 * a second connection used only for a bulk file upload. It is authenticated the
 * same way, but the server does not treat it as "the phone" — it never becomes
 * the push target and raises no connected/disconnected events. This keeps the
 * control connection free to carry mouse/keyboard while a big file uploads.
 *
 * The server always answers a good token with an "OK " line that carries its
 * protocol version and a comma-separated capability list. Clients must treat any
 * reply starting with "OK" as success and parse the caps that follow, so a newer
 * server advertising extra capabilities never breaks an older client.
 *
 * Input commands (client -> server):
 *   M  &lt;dx&gt; &lt;dy&gt;      relative mouse move
 *   MA &lt;x&gt; &lt;y&gt;        absolute mouse move
 *   C  &lt;btn&gt;          click            (btn = L | R | M)
 *   DC &lt;btn&gt;          double click
 *   DN &lt;btn&gt;          button press     (drag start)
 *   UP &lt;btn&gt;          button release   (drag end)
 *   SC &lt;amount&gt;       scroll wheel     (negative = up)
 *   SCH &lt;amount&gt;      horizontal scroll (negative = left; needs "hscroll" cap)
 *   K  &lt;text...&gt;      type unicode text
 *   PING             -> server replies "PONG"
 *
 * File transfer (client -> server):
 *   "FILE &lt;sizeBytes&gt; &lt;nameUrlEncoded&gt;" then &lt;sizeBytes&gt; raw bytes
 *   server -> "FILEOK &lt;savedName&gt;" | "FILEERR &lt;reason&gt;"
 *
 * File transfer (server -> client, i.e. PC -&gt; phone):
 *   "PUSH &lt;sizeBytes&gt; &lt;nameUrlEncoded&gt;" then &lt;sizeBytes&gt; raw bytes
 *   client -> "PUSHOK &lt;savedName&gt;" | "PUSHERR &lt;reason&gt;"
 * The phone saves the file to its Downloads folder. Same framing as FILE, just
 * the other direction, so both peers reuse the read-N-bytes logic.
 * </pre>
 */
public final class Protocol {

    public static final String HELLO   = "HELLO";
    public static final String OK      = "OK";
    public static final String DENIED  = "DENIED";
    public static final String PONG    = "PONG";
    public static final String FILE    = "FILE";
    public static final String FILE_OK = "FILEOK";
    public static final String FILE_ERR = "FILEERR";
    public static final String PUSH     = "PUSH";     // PC -> phone file transfer
    public static final String PUSH_OK  = "PUSHOK";
    public static final String PUSH_ERR = "PUSHERR";

    /** HELLO marker for a bulk-upload data channel (see the class docs). */
    public static final String DATA = "data";

    /** Current wire-protocol version, advertised in the handshake. */
    public static final int VERSION = 3;

    // Capability tokens advertised in the handshake. Add new ones here as
    // features land (e.g. "video", "gamepad"); older peers simply ignore
    // capabilities they don't recognise.
    public static final String CAP_INPUT   = "input";
    public static final String CAP_FILE    = "file";
    public static final String CAP_HSCROLL = "hscroll";
    public static final String CAP_PUSH    = "push";  // server can send files to the phone

    /** URI scheme encoded into the pairing QR: {@code tcr://host:port/tokenHex}. */
    public static final String QR_SCHEME = "tcr";

    private Protocol() {
    }

    /** Builds the success handshake line, e.g. {@code "OK v1 caps=input,file"}. */
    public static String okLine(String... caps) {
        return OK + " v" + VERSION + " caps=" + String.join(",", caps);
    }

    /**
     * Reads a single UTF-8 line (up to and excluding {@code '\n'}) directly from
     * the raw stream, leaving the stream positioned right after the newline.
     * We avoid {@link java.io.BufferedReader} on purpose so that raw file bytes
     * following a header line are not swallowed by a reader's buffer.
     *
     * @return the line, or {@code null} at end of stream.
     */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (!any && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
