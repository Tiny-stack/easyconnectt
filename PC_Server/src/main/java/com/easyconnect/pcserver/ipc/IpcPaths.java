package com.easyconnect.pcserver.ipc;

import java.nio.file.Path;

/**
 * Location of the daemon's local control socket. Both the daemon (server) and
 * the GUI subprocess resolve it the same way so they can find each other.
 *
 * <p>Prefers {@code $XDG_RUNTIME_DIR} (a per-user, tmpfs, 0700 directory that is
 * cleaned up on logout) and falls back to {@code /tmp} keyed by uid.
 */
public final class IpcPaths {

    private IpcPaths() {
    }

    public static Path socket() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime != null && !runtime.isBlank()) {
            return Path.of(runtime, "pcremote.sock");
        }
        String uid = System.getProperty("user.name", "0");
        return Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "pcremote-" + uid + ".sock");
    }
}
