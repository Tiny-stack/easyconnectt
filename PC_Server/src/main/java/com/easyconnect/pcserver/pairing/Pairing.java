package com.easyconnect.pcserver.pairing;

import com.easyconnect.pcserver.net.Protocol;
import com.easyconnect.pcserver.qr.QrCode;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Enumeration;

/**
 * Holds the per-session pairing secret and connection coordinates, and renders
 * the QR code the phone scans. A phone can only connect if it presents the
 * {@link #token()} embedded in this QR — so other devices on the LAN are locked
 * out even though they can reach the port.
 */
public final class Pairing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String token;
    private final String host;
    private final int port;

    private Pairing(String token, String host, int port) {
        this.token = token;
        this.host = host;
        this.port = port;
    }

    /** Generates a fresh 128-bit session secret as lowercase hex. */
    public static String randomToken() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** Builds pairing coordinates for an already-bound port and existing token. */
    public static Pairing of(String token, int port) {
        return new Pairing(token, detectLanIp(), port);
    }

    /** Creates a fresh random session token bound to the given listening port. */
    public static Pairing newSession(int port) {
        return new Pairing(randomToken(), detectLanIp(), port);
    }

    /** Renders the QR as terminal text (for headless / console runs). */
    public String qrAscii() {
        QrCode qr = QrCode.encodeText(qrPayload(), QrCode.Ecc.MEDIUM);
        int n = qr.size;
        int b = 2;
        StringBuilder sb = new StringBuilder();
        for (int y = -b; y < n + b; y += 2) {
            for (int x = -b; x < n + b; x++) {
                boolean top = inRange(qr, x, y);
                boolean bot = inRange(qr, x, y + 1);
                sb.append(top ? (bot ? '█' : '▀') : (bot ? '▄' : ' '));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean inRange(QrCode qr, int x, int y) {
        return x >= 0 && y >= 0 && x < qr.size && y < qr.size && qr.getModule(x, y);
    }

    public String token() {
        return token;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** The exact string encoded into the QR: {@code tcr://host:port/tokenHex}. */
    public String qrPayload() {
        return Protocol.QR_SCHEME + "://" + host + ":" + port + "/" + token;
    }

    /** Renders the pairing payload as a QR image at the given module scale. */
    public BufferedImage qrImage(int scale, int border) {
        QrCode qr = QrCode.encodeText(qrPayload(), QrCode.Ecc.MEDIUM);
        int size = qr.size;
        int dim = (size + border * 2) * scale;
        BufferedImage img = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_RGB);
        int dark = Color.BLACK.getRGB();
        int light = Color.WHITE.getRGB();
        for (int y = 0; y < dim; y++) {
            for (int x = 0; x < dim; x++) {
                int mx = x / scale - border;
                int my = y / scale - border;
                boolean on = mx >= 0 && my >= 0 && mx < size && my < size && qr.getModule(mx, my);
                img.setRGB(x, y, on ? dark : light);
            }
        }
        return img;
    }

    /** Best-effort pick of a site-local IPv4 address the phone can reach. */
    private static String detectLanIp() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to loopback
        }
        return fallback;
    }
}
