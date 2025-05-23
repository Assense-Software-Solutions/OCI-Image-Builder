package com.assense.OCIImageBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Simple helper that creates a custom JRE for a given module using jlink.
 */
public class JREBuilder {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || Arrays.asList(args).contains("--help")) {
            System.out.println("Usage: java JREBuilder <module-path> <module-name> <output-dir>");
            System.exit(args.length == 0 ? 1 : 0);
        }
        build(args[0], args[1], args[2]);
    }

    /**
     * Creates a custom JRE at {@code outDir} containing the specified module.
     */
    public static void build(String modulePath, String moduleName, String outDir) throws Exception {
        Path out = Paths.get(outDir);
        if (Files.exists(out)) deleteDir(out);

        String javaHome = Optional.ofNullable(System.getenv("JAVA_HOME"))
                .orElseGet(() -> System.getProperty("java.home"));
        String jmods = Paths.get(javaHome, "jmods").toString();

        runCmd("jlink",
                "--module-path", jmods + File.pathSeparator + modulePath,
                "--add-modules", moduleName,
                "--output", outDir,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages");
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
    }

    static void runCmd(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) throw new RuntimeException("Command failed: " + String.join(" ", cmd));
    }
}
