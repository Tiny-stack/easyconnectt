package com.easyconnect.pcserver.input;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A virtual mouse backed by the Linux kernel {@code /dev/uinput} device.
 *
 * <p>Under Wayland (KWin, Mutter, wlroots) {@link java.awt.Robot#mouseMove} only
 * warps the logical X11/XWayland pointer — the compositor ignores it for the
 * <em>visible</em> cursor. Injecting relative motion through uinput instead looks
 * like a real hardware mouse, so the compositor moves the on-screen cursor.
 *
 * <p>Uses the Java Foreign Function &amp; Memory API to call {@code open/ioctl/
 * write/close} directly — no native library or external tool (e.g. ydotool)
 * required. Needs write access to {@code /dev/uinput} (world-writable or the
 * {@code input} group on most distros).
 */
public final class UinputMouse implements AutoCloseable {

    // ioctl request codes (x86_64 Linux).
    private static final long UI_SET_EVBIT  = 0x40045564L; // _IOW('U',100,int)
    private static final long UI_SET_KEYBIT = 0x40045565L; // _IOW('U',101,int)
    private static final long UI_SET_RELBIT = 0x40045566L; // _IOW('U',102,int) -- 103 is ABSBIT!
    private static final long UI_DEV_SETUP  = 0x405C5503L;
    private static final long UI_DEV_CREATE = 0x00005501L;
    private static final long UI_DEV_DESTROY = 0x00005502L;

    private static final int O_WRONLY = 0x1;
    private static final int O_NONBLOCK = 0x800;

    private static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2;
    private static final int REL_X = 0, REL_Y = 1, REL_HWHEEL = 6, REL_WHEEL = 8;
    private static final int SYN_REPORT = 0;
    private static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112;
    private static final int BUS_USB = 0x03;

    // Keyboard evdev keycodes (linux/input-event-codes.h) needed for typing.
    private static final int KEY_LEFTSHIFT = 42;
    // Every distinct keycode the char map can produce — declared as capabilities
    // before UI_DEV_CREATE so the kernel lets us inject them. Includes the shift
    // modifier plus the two whitespace keys not covered by the printable map.
    private static final int[] KEYBOARD_KEYS = keyboardKeycodes();

    private final MethodHandle open, ioctlInt, ioctlPtr, write, close;
    private final Arena arena;
    private final MemorySegment eventBuf; // reusable 24-byte input_event
    private final int fd;

    public UinputMouse() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        open = linker.downcallHandle(libc.find("open").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.firstVariadicArg(2));
        ioctlInt = linker.downcallHandle(libc.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT),
                Linker.Option.firstVariadicArg(2));
        ioctlPtr = linker.downcallHandle(libc.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2));
        write = linker.downcallHandle(libc.find("write").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        close = linker.downcallHandle(libc.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        arena = Arena.ofShared();
        eventBuf = arena.allocate(24);

        MemorySegment path = arena.allocateUtf8String("/dev/uinput");
        fd = (int) open.invoke(path, O_WRONLY | O_NONBLOCK);
        if (fd < 0) {
            throw new IllegalStateException("cannot open /dev/uinput (fd=" + fd + ")");
        }

        // Declare the capabilities: three buttons + relative X/Y/wheel.
        io("EVBIT EV_KEY", UI_SET_EVBIT, EV_KEY);
        io("KEYBIT BTN_LEFT", UI_SET_KEYBIT, BTN_LEFT);
        io("KEYBIT BTN_RIGHT", UI_SET_KEYBIT, BTN_RIGHT);
        io("KEYBIT BTN_MIDDLE", UI_SET_KEYBIT, BTN_MIDDLE);
        // Keyboard keys, so type() can inject text through the same device
        // instead of java.awt.Robot (which would pull in the ~22 MB AWT stack).
        for (int key : KEYBOARD_KEYS) {
            io("KEYBIT " + key, UI_SET_KEYBIT, key);
        }
        io("EVBIT EV_REL", UI_SET_EVBIT, EV_REL);
        io("RELBIT REL_X", UI_SET_RELBIT, REL_X);
        io("RELBIT REL_Y", UI_SET_RELBIT, REL_Y);
        io("RELBIT REL_WHEEL", UI_SET_RELBIT, REL_WHEEL);
        io("RELBIT REL_HWHEEL", UI_SET_RELBIT, REL_HWHEEL);
        io("EVBIT EV_SYN", UI_SET_EVBIT, EV_SYN);

        // struct uinput_setup { input_id id; char name[80]; u32 ff_effects_max; }
        MemorySegment setup = arena.allocate(92);
        setup.set(ValueLayout.JAVA_SHORT, 0, (short) BUS_USB);   // id.bustype
        setup.set(ValueLayout.JAVA_SHORT, 2, (short) 0x1234);    // id.vendor
        setup.set(ValueLayout.JAVA_SHORT, 4, (short) 0x5678);    // id.product
        setup.set(ValueLayout.JAVA_SHORT, 6, (short) 1);         // id.version
        byte[] name = "PC Remote Virtual Input".getBytes(StandardCharsets.US_ASCII);
        MemorySegment.copy(name, 0, setup, ValueLayout.JAVA_BYTE, 8, Math.min(name.length, 79));
        setup.set(ValueLayout.JAVA_INT, 88, 0);                  // ff_effects_max

        if ((int) ioctlPtr.invoke(fd, UI_DEV_SETUP, setup) < 0) {
            close.invoke(fd);
            throw new IllegalStateException("UI_DEV_SETUP failed");
        }
        if ((int) ioctlInt.invoke(fd, UI_DEV_CREATE, 0) < 0) {
            close.invoke(fd);
            throw new IllegalStateException("UI_DEV_CREATE failed");
        }
        // Give udev/the compositor a moment to register the new device.
        Thread.sleep(200);
    }

    /** Probe for a usable uinput device; returns null if unavailable. */
    public static UinputMouse tryCreate() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return null;
        }
        if (!Files.isWritable(Path.of("/dev/uinput"))) {
            System.err.println("[uinput] /dev/uinput not writable — falling back to Robot. "
                    + "Add yourself to the 'input' group or add a udev rule to enable native cursor control.");
            return null;
        }
        try {
            UinputMouse m = new UinputMouse();
            System.out.println("[uinput] virtual mouse ready (Wayland-compatible cursor control).");
            return m;
        } catch (Throwable t) {
            System.err.println("[uinput] init failed (" + t.getMessage() + ") — falling back to Robot.");
            return null;
        }
    }

    public synchronized void moveRelative(int dx, int dy) {
        emit(EV_REL, REL_X, dx);
        emit(EV_REL, REL_Y, dy);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    public synchronized void button(int code, boolean down) {
        emit(EV_KEY, code, down ? 1 : 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    /** amount>0 scrolls down (matches Robot.mouseWheel); uinput wheel is inverted. */
    public synchronized void scroll(int amount) {
        emit(EV_REL, REL_WHEEL, -amount);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    /** amount>0 scrolls right. */
    public synchronized void scrollHorizontal(int amount) {
        emit(EV_REL, REL_HWHEEL, amount);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    /**
     * Types unicode text through the virtual keyboard. Characters that aren't on
     * the US layout map are skipped (same best-effort contract as the previous
     * {@link java.awt.Robot} path) — no AWT is touched.
     */
    public synchronized void type(String text) {
        for (int i = 0; i < text.length(); i++) {
            int m = mapChar(text.charAt(i));
            if (m < 0) {
                continue; // unmappable char — skip, as Robot did
            }
            int keycode = m & 0xFFFF;
            boolean shift = (m & SHIFT_FLAG) != 0;
            if (shift) {
                emit(EV_KEY, KEY_LEFTSHIFT, 1);
                emit(EV_SYN, SYN_REPORT, 0);
            }
            emit(EV_KEY, keycode, 1);
            emit(EV_SYN, SYN_REPORT, 0);
            emit(EV_KEY, keycode, 0);
            emit(EV_SYN, SYN_REPORT, 0);
            if (shift) {
                emit(EV_KEY, KEY_LEFTSHIFT, 0);
                emit(EV_SYN, SYN_REPORT, 0);
            }
        }
    }

    public static int buttonCode(String btn) {
        return switch (btn == null ? "L" : btn.toUpperCase()) {
            case "R" -> BTN_RIGHT;
            case "M" -> BTN_MIDDLE;
            default -> BTN_LEFT;
        };
    }

    // --- US-QWERTY character -> evdev keycode mapping -----------------------
    // Values are the evdev keycode; SHIFT_FLAG is OR'd in for shifted glyphs.
    // Layout-independent Unicode input is out of scope here (would need runtime
    // keymap remapping); unmapped chars are skipped, matching the old behaviour.

    private static final int SHIFT_FLAG = 0x10000;
    private static final int[] CHAR_MAP = buildCharMap();

    /** Returns keycode (low 16 bits) | optional SHIFT_FLAG, or -1 if unmappable. */
    private static int mapChar(char c) {
        return c < CHAR_MAP.length ? CHAR_MAP[c] : -1;
    }

    private static int[] buildCharMap() {
        int[] m = new int[128];
        java.util.Arrays.fill(m, -1);
        // rows as (chars, keycodes) with shift where noted
        String lower = "abcdefghijklmnopqrstuvwxyz";
        int[] letterKeys = {30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44};
        for (int i = 0; i < lower.length(); i++) {
            m[lower.charAt(i)] = letterKeys[i];
            m[Character.toUpperCase(lower.charAt(i))] = letterKeys[i] | SHIFT_FLAG;
        }
        // digit row, unshifted
        String digits = "1234567890";
        int[] digitKeys = {2,3,4,5,6,7,8,9,10,11};
        for (int i = 0; i < digits.length(); i++) {
            m[digits.charAt(i)] = digitKeys[i];
        }
        // shifted digit-row symbols
        String shd = "!@#$%^&*()";
        for (int i = 0; i < shd.length(); i++) {
            m[shd.charAt(i)] = digitKeys[i] | SHIFT_FLAG;
        }
        // whitespace + common punctuation (unshifted / shifted pairs)
        m[' ']  = 57;   m['\n'] = 28;   m['\t'] = 15;   m['\r'] = 28;
        put(m, '-', 12, '_', 12);
        put(m, '=', 13, '+', 13);
        put(m, '[', 26, '{', 26);
        put(m, ']', 27, '}', 27);
        put(m, '\\', 43, '|', 43);
        put(m, ';', 39, ':', 39);
        put(m, '\'', 40, '"', 40);
        put(m, '`', 41, '~', 41);
        put(m, ',', 51, '<', 51);
        put(m, '.', 52, '>', 52);
        put(m, '/', 53, '?', 53);
        return m;
    }

    /** Sets an unshifted char and its shifted partner sharing the same keycode. */
    private static void put(int[] m, char plain, int key, char shifted, int sameKey) {
        m[plain] = key;
        m[shifted] = sameKey | SHIFT_FLAG;
    }

    /** Distinct keycodes to declare as device capabilities (plus the shift key). */
    private static int[] keyboardKeycodes() {
        java.util.LinkedHashSet<Integer> keys = new java.util.LinkedHashSet<>();
        keys.add(KEY_LEFTSHIFT);
        for (int v : buildCharMap()) {
            if (v >= 0) {
                keys.add(v & 0xFFFF);
            }
        }
        int[] out = new int[keys.size()];
        int i = 0;
        for (int k : keys) {
            out[i++] = k;
        }
        return out;
    }

    private static final boolean DEBUG = Boolean.getBoolean("pcremote.debug");

    /** ioctl with return-code logging under debug. */
    private void io(String label, long req, int arg) throws Throwable {
        int r = (int) ioctlInt.invoke(fd, req, arg);
        if (DEBUG || r < 0) {
            System.out.println("[uinput] ioctl " + label + " -> " + r);
        }
    }

    private void emit(int type, int code, int value) {
        try {
            eventBuf.set(ValueLayout.JAVA_LONG, 0, 0L);  // timeval.tv_sec
            eventBuf.set(ValueLayout.JAVA_LONG, 8, 0L);  // timeval.tv_usec
            eventBuf.set(ValueLayout.JAVA_SHORT, 16, (short) type);
            eventBuf.set(ValueLayout.JAVA_SHORT, 18, (short) code);
            eventBuf.set(ValueLayout.JAVA_INT, 20, value);
            long n = (long) write.invoke(fd, eventBuf, 24L);
            if (DEBUG && n != 24) {
                System.err.println("[uinput] short write: " + n + " (type=" + type + " code=" + code + ")");
            }
        } catch (Throwable t) {
            throw new RuntimeException("uinput write failed", t);
        }
    }

    @Override
    public synchronized void close() {
        try {
            ioctlInt.invoke(fd, UI_DEV_DESTROY, 0);
            close.invoke(fd);
        } catch (Throwable ignored) {
            // best effort
        } finally {
            arena.close();
        }
    }
}
