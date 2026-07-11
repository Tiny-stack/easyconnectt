package com.easyconnect.pcserver.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Receives an incoming file: reads exactly {@code size} bytes from the stream
 * and writes them into the destination directory, avoiding name collisions.
 */
public final class FileReceiver {

    private final Path destDir;

    public FileReceiver(Path destDir) {
        this.destDir = destDir;
        try {
            Files.createDirectories(destDir);
        } catch (IOException e) {
            System.err.println("[file] cannot create " + destDir + ": " + e.getMessage());
        }
    }

    public Path destDir() {
        return destDir;
    }

    /**
     * Streams {@code size} bytes from {@code in} into a new file named after
     * {@code name} (sanitised, de-duplicated).
     *
     * @return the path actually written.
     */
    public Path receive(InputStream in, String name, long size) throws IOException {
        Path target = uniquePath(sanitize(name));
        Path tmp = Files.createTempFile(destDir, ".incoming-", ".part");
        long remaining = size;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(tmp)) {
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int r = in.read(buf, 0, want);
                if (r < 0) {
                    throw new IOException("stream ended with " + remaining + " bytes left");
                }
                out.write(buf, 0, r);
                remaining -= r;
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private Path uniquePath(String name) {
        Path p = destDir.resolve(name);
        if (!Files.exists(p)) {
            return p;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            Path cand = destDir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(cand)) {
                return cand;
            }
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "received.bin";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        return cleaned.isEmpty() ? "received.bin" : cleaned;
    }
}
