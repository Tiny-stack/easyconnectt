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
 *   client -> "HELLO &lt;tokenHex&gt; [v&lt;n&gt;] [caps=a,b,...]"   extra fields optional/ignored
 *   server -> "OK v&lt;n&gt; caps=a,b,..."  | "DENIED"
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

    /** Current wire-protocol version, advertised in the handshake. */
    public static final int VERSION = 1;

    // Capability tokens advertised in the handshake. Add new ones here as
    // features land (e.g. "video", "gamepad"); older peers simply ignore
    // capabilities they don't recognise.
    public static final String CAP_INPUT   = "input";
    public static final String CAP_FILE    = "file";
    public static final String CAP_HSCROLL = "hscroll";

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
