package com.assense.OCIImageBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class OCIImageBuilderTest {
    public static void main(String[] args) throws Exception {
        String outDir = "test-oci-image";
        String jreDir = "test-jre";
        String appDir = "test-app";
        String moduleName = "test.module";

        // --- 1. Prepare fake JRE and App directories ---
        cleanDir(jreDir);
        cleanDir(appDir);
        Files.createDirectories(Paths.get(jreDir, "bin"));
        Files.writeString(Paths.get(jreDir, "bin", "java"), "#!/bin/sh\necho 'Fake java!'", StandardOpenOption.CREATE_NEW);
        Files.createDirectories(Paths.get(appDir, moduleName.replace('.', '/')));
        Files.writeString(Paths.get(appDir, moduleName.replace('.', '/') + "/HelloWorld.class"), "dummyclass", StandardOpenOption.CREATE_NEW);

        // --- 2. Run com.assense.OCIImageBuilder.OCIImageBuilder ---
        cleanDir(outDir);
        List<String> cmd = Arrays.asList("java", "com.assense.OCIImageBuilder.OCIImageBuilder", "--jre", jreDir, "--app", appDir, "--module", moduleName, "--out", outDir);
        System.out.println("Running com.assense.OCIImageBuilder.OCIImageBuilder...");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        assert p.waitFor() == 0 : "com.assense.OCIImageBuilder.OCIImageBuilder failed to run.";

        // --- 3. Check output files/structure ---
        Path outPath = Paths.get(outDir);
        assert Files.exists(outPath) : "Output dir missing";
        assert Files.exists(outPath.resolve("oci-layout")) : "oci-layout missing";
        assert Files.exists(outPath.resolve("index.json")) : "index.json missing";
        assert Files.exists(outPath.resolve("blobs/sha256")) : "blobs/sha256 missing";
        assert Files.list(outPath.resolve("blobs/sha256")).findAny().isPresent() : "No blobs found";

        // --- 4. Check manifest and config correctness ---
        // Parse index.json
        String index = Files.readString(outPath.resolve("index.json"));
        String manifestDigest = index.split("\"digest\"\\s*:\\s*\"sha256:")[1].split("\"")[0];
        Path manifestPath = outPath.resolve("blobs/sha256").resolve(manifestDigest);
        assert Files.exists(manifestPath) : "manifest blob missing";
        String manifest = Files.readString(manifestPath);

        // Manifest must list 3 layers (distroless, jre, app)
        int layersCount = manifest.split("\"mediaType\"\\s*:\\s*\"application/vnd.oci.image.layer.v1.tar").length - 1;
        assert layersCount == 3 : "Expected 3 layers, found " + layersCount;

        // --- 5. Config correctness ---
        String configDigest = manifest.split("\"config\"\\s*:\\s*\\{[^}]*\"digest\"\\s*:\\s*\"sha256:")[1].split("\"")[0];
        Path configPath = outPath.resolve("blobs/sha256").resolve(configDigest);
        assert Files.exists(configPath) : "config blob missing";
        String config = Files.readString(configPath);
        assert config.contains(moduleName) : "Module name missing in config";
        assert config.contains("\"diff_ids\"") : "diff_ids missing in config";
        assert config.split("sha256:").length - 1 == 4 : "diff_ids + digests should contain 4 hashes (3 diff_ids + 1 digest)";

        // --- 6. Entrypoint correctness ---
        assert config.contains("/opt/java/openjdk/bin/java") : "Entrypoint java missing";
        assert config.contains("/opt/app") : "Entrypoint app path missing";

        // --- 7. Cleanup ---
        System.out.println("All OCI image assertions passed!");
        cleanDir(jreDir);
        cleanDir(appDir);
        // Optionally: cleanDir(outDir);
    }

    static void cleanDir(String dir) throws IOException {
        Path d = Paths.get(dir);
        if (Files.exists(d)) Files.walk(d).sorted(Comparator.reverseOrder()).forEach(path -> {
            try {
                Files.delete(path);
            } catch (IOException ignored) {
            }
        });
    }
}
