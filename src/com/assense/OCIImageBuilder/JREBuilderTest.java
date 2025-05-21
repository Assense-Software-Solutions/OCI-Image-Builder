package com.assense.OCIImageBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

public class JREBuilderTest {
    public static void main(String[] args) throws Exception {
        String srcDir = "test-src";
        String moduleDir = "test-mods";
        String jreDir = "test-jre";
        String moduleName = "test.jre";

        cleanDir(srcDir);
        cleanDir(moduleDir);
        cleanDir(jreDir);

        Path pkg = Paths.get(srcDir, moduleName.replace('.', '/'));
        Files.createDirectories(pkg);
        Files.writeString(Paths.get(srcDir, "module-info.java"),
                "module " + moduleName + " { exports " + moduleName + "; }");
        Files.writeString(pkg.resolve("Hello.java"),
                "package " + moduleName + "; public class Hello { public static void main(String[] args) { System.out.println(\"hi\"); } }");

        runCmd("javac", "-d", moduleDir,
                Paths.get(srcDir, "module-info.java").toString(),
                pkg.resolve("Hello.java").toString());

        JREBuilder.build(moduleDir, moduleName, jreDir);

        if (!Files.exists(Paths.get(jreDir, "bin", "java"))) {
            throw new AssertionError("java executable missing in built JRE");
        }

        System.out.println("All JREBuilder assertions passed!");
        cleanDir(srcDir);
        cleanDir(moduleDir);
        cleanDir(jreDir);
    }

    static void runCmd(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) throw new RuntimeException("Command failed: " + String.join(" ", args));
    }

    static void cleanDir(String dir) throws IOException {
        Path d = Paths.get(dir);
        if (Files.exists(d)) Files.walk(d)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try { Files.delete(path); } catch (IOException ignored) {}
                });
    }
}
