package com.easyconnect.pcserver.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Relaunches this application as a child process with different arguments — used
 * by the daemon to spawn the Swing GUI subprocess (so its AWT memory is a
 * separate process the user can close and fully reclaim).
 *
 * <p>Handles two ways the app can be running:
 * <ul>
 *   <li><b>Native launcher</b> (jpackage app-image / jlink launcher): the current
 *       process command IS the launcher binary, so we just run it again with the
 *       new args. Preview/native-access flags are already baked into it.</li>
 *   <li><b>Plain {@code java}</b> (gradle run, {@code java -m …}): we rebuild a
 *       {@code java --enable-preview -p <modulepath> -m <module>/<main>} command.
 *       {@code --enable-preview} is required even for the GUI subprocess because
 *       the module's classfiles are marked as preview.</li>
 * </ul>
 */
public final class SelfLauncher {

    private final String module;
    private final String mainClass;

    public SelfLauncher(String module, String mainClass) {
        this.module = module;
        this.mainClass = mainClass;
    }

    /** The full command that would relaunch this app with {@code extraArgs}. */
    public List<String> command(String... extraArgs) {
        List<String> cmd = baseCommand();
        for (String a : extraArgs) {
            cmd.add(a);
        }
        return cmd;
    }

    /** Spawns a child running this app with {@code extraArgs}; returns the Process. */
    public Process spawn(String... extraArgs) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(extraArgs));
        scrubJpackageEnv(pb.environment());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        return pb.start();
    }

    /**
     * jpackage's native launcher sets {@code _JPACKAGE_LAUNCHER} in our process
     * environment to flag "already launched". If a child we spawn inherits it,
     * the child's launcher skips reading its app config and misparses our args
     * (treating {@code gui} as a main class, {@code --gui} as a JVM option).
     * Remove any such marker so the child launches as a fresh instance.
     */
    public static void scrubJpackageEnv(java.util.Map<String, String> env) {
        env.keySet().removeIf(k -> k.startsWith("_JPACKAGE"));
    }

    private List<String> baseCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> command = info.command();

        // Native launcher case: the process command is our own launcher binary.
        if (command.isPresent() && !isJavaExecutable(command.get())) {
            List<String> cmd = new ArrayList<>();
            cmd.add(command.get());
            return cmd;
        }

        // Plain-java case: reconstruct the module launch.
        String java = command.orElse(System.getProperty("java.home") + "/bin/java");
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("--enable-preview");
        cmd.add("--enable-native-access=" + module);
        String modulePath = System.getProperty("jdk.module.path");
        if (modulePath != null && !modulePath.isBlank()) {
            cmd.add("-p");
            cmd.add(modulePath);
            cmd.add("-m");
            cmd.add(module + "/" + mainClass);
        } else {
            // Fell back to the classpath (rare here) — launch by main class.
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path", "."));
            cmd.add(mainClass);
        }
        return cmd;
    }

    private static boolean isJavaExecutable(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith("/java") || lower.endsWith("\\java.exe") || lower.endsWith("/java.exe")
                || lower.equals("java");
    }
}
