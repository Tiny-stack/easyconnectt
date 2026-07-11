package com.easyconnect.pcserver.input;

import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Turns protocol commands into real OS input via {@link Robot}. Movement is
 * tracked relative to the current pointer location so the phone can act as a
 * trackpad. Degrades to a no-op (with a one-time warning) on headless hosts.
 */
public final class InputController {

    private static final boolean DEBUG = Boolean.getBoolean("pcremote.debug");
    private int moveLog;

    private final UinputMouse uinput; // preferred on Linux/Wayland; null elsewhere
    private Robot robot;              // lazy: only built if a Robot-only path is used
    private Dimension screen;         // set alongside robot
    private boolean robotInit;        // whether we've attempted robot creation
    private boolean warned;

    public InputController() {
        // Do NOT create Robot here. Building it loads the AWT native stack
        // (~22 MB) and blocks GraalVM. On a working uinput box every command
        // (move/click/scroll/type) goes through uinput, so Robot is never
        // needed and AWT never loads. Robot is created lazily only as a
        // fallback — see robot() — for the rare MA (absolute move) command or
        // when uinput is unavailable.
        this.uinput = UinputMouse.tryCreate();
    }

    /**
     * Lazily creates (once) and returns the AWT Robot fallback, or null if it
     * can't be created (headless). First call here is what actually loads AWT.
     */
    private Robot robot() {
        if (!robotInit) {
            robotInit = true;
            try {
                robot = new Robot();
                robot.setAutoWaitForIdle(false);
                screen = Toolkit.getDefaultToolkit().getScreenSize();
            } catch (Throwable t) {
                robot = null;
                screen = new Dimension(0, 0);
            }
        }
        return robot;
    }

    /** True if any input backend (uinput or Robot) can act. */
    public boolean isAvailable() {
        return uinput != null || robot() != null; // short-circuits before touching AWT
    }

    /** Which backend is driving pointer events, for logging. */
    public String backend() {
        return uinput != null ? "uinput" : (robot() != null ? "robot" : "none");
    }

    private boolean guard() {
        if (robot() == null) {
            if (!warned) {
                System.err.println("[input] Robot unavailable (headless?) — input commands ignored.");
                warned = true;
            }
            return false;
        }
        return true;
    }

    /** Relative move from the current pointer position, clamped to the screen. */
    public synchronized void moveRelative(int dx, int dy) {
        if (uinput != null) {
            uinput.moveRelative(dx, dy);
            if (DEBUG && moveLog++ < 15) {
                System.out.println("[move-uinput] d(" + dx + "," + dy + ")");
            }
            return;
        }
        if (!guard()) {
            return;
        }
        Point p = MouseInfo.getPointerInfo().getLocation();
        int nx = clamp(p.x + dx, 0, screen.width - 1);
        int ny = clamp(p.y + dy, 0, screen.height - 1);
        robot.mouseMove(nx, ny);
        if (DEBUG && moveLog++ < 15) {
            Point after = MouseInfo.getPointerInfo().getLocation();
            System.out.println("[move] d(" + dx + "," + dy + ") from(" + p.x + "," + p.y
                    + ") -> req(" + nx + "," + ny + ") actual(" + after.x + "," + after.y + ")"
                    + (after.x == nx && after.y == ny ? "" : "  << MISMATCH"));
        }
    }

    public synchronized void moveAbsolute(int x, int y) {
        if (!guard()) {
            return;
        }
        robot.mouseMove(clamp(x, 0, screen.width - 1), clamp(y, 0, screen.height - 1));
    }

    public synchronized void click(String button) {
        if (uinput != null) {
            int code = UinputMouse.buttonCode(button);
            uinput.button(code, true);
            uinput.button(code, false);
            return;
        }
        if (!guard()) {
            return;
        }
        int mask = mask(button);
        robot.mousePress(mask);
        robot.mouseRelease(mask);
    }

    public synchronized void doubleClick(String button) {
        click(button);
        click(button);
    }

    public synchronized void press(String button) {
        if (uinput != null) {
            uinput.button(UinputMouse.buttonCode(button), true);
        } else if (guard()) {
            robot.mousePress(mask(button));
        }
    }

    public synchronized void release(String button) {
        if (uinput != null) {
            uinput.button(UinputMouse.buttonCode(button), false);
        } else if (guard()) {
            robot.mouseRelease(mask(button));
        }
    }

    public synchronized void scroll(int amount) {
        if (uinput != null) {
            uinput.scroll(amount);
        } else if (guard()) {
            robot.mouseWheel(amount);
        }
    }

    /**
     * Horizontal scroll (amount &gt; 0 = right). Only the uinput backend can do
     * this — {@link Robot} has no horizontal wheel — so it's a no-op otherwise.
     * Gate on {@link #supportsHorizontalScroll()} before advertising it.
     */
    public synchronized void scrollHorizontal(int amount) {
        if (uinput != null) {
            uinput.scrollHorizontal(amount);
        }
    }

    /** True if horizontal scrolling can actually be injected (uinput backend). */
    public boolean supportsHorizontalScroll() {
        return uinput != null;
    }

    /** Types unicode text one character at a time via the system keyboard. */
    public synchronized void type(String text) {
        if (uinput != null) {
            uinput.type(text);
            return;
        }
        if (!guard()) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            typeChar(text.charAt(i));
        }
    }

    private void typeChar(char c) {
        int code = KeyEvent.getExtendedKeyCodeForChar(c);
        if (code == KeyEvent.VK_UNDEFINED) {
            return;
        }
        boolean shift = Character.isUpperCase(c);
        if (shift) {
            robot.keyPress(KeyEvent.VK_SHIFT);
        }
        try {
            robot.keyPress(code);
            robot.keyRelease(code);
        } catch (IllegalArgumentException ignored) {
            // key not typeable on this layout
        } finally {
            if (shift) {
                robot.keyRelease(KeyEvent.VK_SHIFT);
            }
        }
    }

    /** Releases the virtual uinput device, if any. */
    public void close() {
        if (uinput != null) {
            uinput.close();
        }
    }

    private static int mask(String button) {
        return switch (button == null ? "L" : button.toUpperCase()) {
            case "R" -> InputEvent.BUTTON3_DOWN_MASK;
            case "M" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
