package com.assense.OCIImageBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class OCIImageBuilder {
    static final String DISTROLESS_IMAGE = "gcr.io/distroless/base:latest";

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            printHelp();
            System.exit(0);
        }
        String jreDir = null, appDir = null, moduleName = null, outDir = "oci-image";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jre":
                    if (i + 1 >= args.length) fail("Missing value for --jre");
                    jreDir = args[++i];
                    break;
                case "--app":
                    if (i + 1 >= args.length) fail("Missing value for --app");
                    appDir = args[++i];
                    break;
                case "--module":
                    if (i + 1 >= args.length) fail("Missing value for --module");
                    moduleName = args[++i];
                    break;
                case "--out":
                    if (i + 1 >= args.length) fail("Missing value for --out");
                    outDir = args[++i];
                    break;
                default:
                    fail("Unknown argument: " + args[i]);
            }
        }
        if (jreDir == null || appDir == null || moduleName == null) {
            fail("Missing required arguments.");
        }

        Path OUT = Paths.get(outDir);
        Path BLOBS = OUT.resolve("blobs/sha256");
        Files.createDirectories(BLOBS);

        // Step 1: oci-layout
        Files.writeString(OUT.resolve("oci-layout"),
                "{ \"imageLayoutVersion\": \"1.0.0\" }\n", StandardCharsets.UTF_8);

        // Step 2: Download distroless base layer without crane (assume one layer)
        String baseLayerTar = outDir + "/base-layer.tar";
        Path baseLayerPath = Paths.get(baseLayerTar);
        if (!Files.exists(baseLayerPath)) {
            System.out.println("Downloading base layer from " + DISTROLESS_IMAGE + " via registry API...");
            downloadDistrolessBaseLayer(baseLayerTar);
        } else {
            System.out.println("Base layer tar already present, skipping download.");
        }

        // Compute digest and diff_id for base layer
        byte[] baseLayerTarBytes = Files.readAllBytes(baseLayerPath);
        String baseLayerDigest = sha256Hex(baseLayerTarBytes);
        String baseLayerDiffId = "sha256:" + baseLayerDigest;
        Files.write(BLOBS.resolve(baseLayerDigest), baseLayerTarBytes);
        Files.deleteIfExists(baseLayerPath);

        // Step 3: JRE layer as /opt/jre
        String jreTar = outDir + "/jre.tar";
        createTarWithDir(jreDir, "/opt/jre", jreTar);
        Path jreTarPath = Paths.get(jreTar);
        byte[] jreTarBytes = Files.readAllBytes(jreTarPath);
        String jreLayerDigest = sha256Hex(jreTarBytes);
        String jreLayerDiffId = "sha256:" + jreLayerDigest;
        Files.write(BLOBS.resolve(jreLayerDigest), jreTarBytes);
        Files.deleteIfExists(jreTarPath);

        // Step 4: App layer as /opt/app
        String appTar = outDir + "/app.tar";
        createTarWithDir(appDir, "/opt/app", appTar);
        Path appTarPath = Paths.get(appTar);
        byte[] appTarBytes = Files.readAllBytes(appTarPath);
        String appLayerDigest = sha256Hex(appTarBytes);
        String appLayerDiffId = "sha256:" + appLayerDigest;
        Files.write(BLOBS.resolve(appLayerDigest), appTarBytes);
        Files.deleteIfExists(appTarPath);

        // Step 5: config.json with proper diff_ids
        String configJson = """
        {
          "architecture": "amd64",
          "os": "linux",
          "rootfs": { "type": "layers", "diff_ids": [
              "%s",
              "%s",
              "%s"
          ] },
          "config": {
            "Env": [],
            "Entrypoint": ["/opt/jre/bin/java", "-p", "/opt/app", "-m", "%s/%s.HelloWorld"]
          }
        }
        """.formatted(baseLayerDiffId, jreLayerDiffId, appLayerDiffId, moduleName, moduleName);
        String configDigest = writeBlob(configJson, BLOBS);

        // Step 6: manifest.json
        String manifestJson = String.format("""
        {
          "schemaVersion": 2,
          "config": {
            "mediaType": "application/vnd.oci.image.config.v1+json",
            "digest": "sha256:%s",
            "size": %d
          },
          "layers": [
            {
              "mediaType": "application/vnd.oci.image.layer.v1.tar",
              "digest": "sha256:%s",
              "size": %d
            },
            {
              "mediaType": "application/vnd.oci.image.layer.v1.tar",
              "digest": "sha256:%s",
              "size": %d
            },
            {
              "mediaType": "application/vnd.oci.image.layer.v1.tar",
              "digest": "sha256:%s",
              "size": %d
            }
          ]
        }
        """,
                configDigest, Files.size(BLOBS.resolve(configDigest)),
                baseLayerDigest, Files.size(BLOBS.resolve(baseLayerDigest)),
                jreLayerDigest, Files.size(BLOBS.resolve(jreLayerDigest)),
                appLayerDigest, Files.size(BLOBS.resolve(appLayerDigest))
        );
        String manifestDigest = writeBlob(manifestJson, BLOBS);

        // Step 7: index.json
        String indexJson = String.format("""
        {
          "schemaVersion": 2,
          "manifests": [
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "digest": "sha256:%s",
              "size": %d,
              "annotations": { "org.opencontainers.image.ref.name": "latest" }
            }
          ]
        }
        """, manifestDigest, Files.size(BLOBS.resolve(manifestDigest)));
        Files.writeString(OUT.resolve("index.json"), indexJson);

        System.out.println("\nOCI image created at: " + OUT.toAbsolutePath());
        System.out.println("Inspect or load with umoci, skopeo, or podman. See --help for details.");
    }

    static void printHelp() {
        System.out.println("""
        OCIImageBuilder - Minimal OCI Image Builder (with Distroless Base using crane)
        -----------------------------------------------------------------------------
        Builds a three-layer OCI image for a Java app: distroless base (from crane), custom JRE, application code.

        Usage:
          java com.assense.OCIImageBuilder.OCIImageBuilder --jre <custom-jre-dir> --app <app-layer-dir> --module <module-name> [--out <output-dir>]
        """);
    }

    static void fail(String msg) {
        System.err.println(msg);
        printHelp();
        System.exit(1);
    }

    // Runs a shell command, throwing on error
    static void runCmd(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.inheritIO();
        Process p = pb.start();
        if (p.waitFor() != 0) throw new RuntimeException("Command failed: " + String.join(" ", args));
    }

    // Runs a command and returns its stdout as String
    static String runCmdCapture(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(baos);
        }
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", args));
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    static void downloadDistrolessBaseLayer(String outTar) throws Exception {
        Path tmpDir = Files.createTempDirectory("distroless-base");

        // First get the image index
        String imageIndex = runCmdCapture(
                "curl", "-L",
                "-H", "Accept: application/vnd.oci.image.index.v1+json",
                "https://gcr.io/v2/distroless/base/manifests/latest");

        // Extract the manifest digest for linux/amd64 from the image index
        java.util.regex.Pattern manifestPattern = java.util.regex.Pattern.compile(
                "\"digest\"\\s*:\\s*\"(sha256:[a-f0-9]+)\".*?\"platform\"\\s*:\\s*\\{.*?\"architecture\"\\s*:\\s*\"amd64\".*?\"os\"\\s*:\\s*\"linux\"",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher manifestMatcher = manifestPattern.matcher(imageIndex);
        if (!manifestMatcher.find()) {
            throw new RuntimeException("Could not find linux/amd64 manifest in image index");
        }
        String manifestDigest = manifestMatcher.group(1);

        // Get the actual manifest using the digest, accepting the OCI manifest format
        String manifest = runCmdCapture(
                "curl", "-L",
                "-H", "Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json",
                "https://gcr.io/v2/distroless/base/manifests/" + manifestDigest);

        List<String> digests = new ArrayList<>();
        int layersIdx = manifest.indexOf("\"layers\"");
        if (layersIdx != -1) {
            String layersPart = manifest.substring(layersIdx);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"digest\"\\s*:\\s*\"(sha256:[a-f0-9]+)\"")
                    .matcher(layersPart);
            while (m.find()) digests.add(m.group(1));
        }

        for (String digest : digests) {
            runCmd("bash", "-c",
                    "curl --silent -L https://gcr.io/v2/distroless/base/blobs/" + digest +
                            " | tar -xz -C " + tmpDir.toAbsolutePath());
        }

        runCmd("tar", "cf", outTar, "-C", tmpDir.toString(), ".");

        // the app exits after this, no need to close resources
        //noinspection resource
        Files.walk(tmpDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
    }

    // Write a blob from String, returns digest (hex, no 'sha256:')
    static String writeBlob(String json, Path BLOBS) throws Exception {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        String digest = sha256Hex(data);
        Files.write(BLOBS.resolve(digest), data);
        return digest;
    }

    // Compute SHA-256 as a hex string (no "sha256:" prefix)
    static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // Create a tar archive from srcDir, placing its content at targetDir in tar
    static void createTarWithDir(String srcDir, String targetDir, String outTar) throws Exception {
        List<String> cmd = List.of("tar", "cf", outTar, "-C", srcDir, ".");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Transform the root dir in tar: /foo -> /opt/jre or /opt/app
        Map<String,String> env = pb.environment();
        env.put("TAR_OPTIONS", "--transform=s,^," + targetDir.replaceFirst("^/", "") + "/,");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            br.lines().forEach(System.out::println);
        }
        if (p.waitFor() != 0) throw new RuntimeException("tar failed for " + srcDir);
    }
}