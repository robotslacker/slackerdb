package org.robotslacker.tools.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Writes manifest, checksums, and human-readable reports into the staging directory.
 */
public final class ManifestWriter {
    private final ObjectMapper mapper = new ObjectMapper();

    public void write(Path staging,
                      MavenCollector.MavenCollectionResult maven,
                      NpmCollector.NpmSummary npm,
                      List<Platform> platforms,
                      String mavenVersion,
                      String javaVersion) throws Exception {
        Path javarepo = staging.resolve("javarepo");
        Path npmrepo = staging.resolve("npmrepo");
        Path javarepoZip = staging.resolve("javarepo.zip");
        Path npmrepoZip = staging.resolve("npmrepo.zip");
        FileStats mavenStats = stats(javarepo);
        FileStats npmStats = stats(npmrepo);
        long javarepoZipBytes = size(javarepoZip);
        long npmrepoZipBytes = size(npmrepoZip);

        List<String> warnings = new ArrayList<>(maven.warnings());
        if (npm != null) {
            warnings.addAll(npm.warnings());
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("generatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        root.put("mavenVersion", mavenVersion);
        root.put("javaVersion", javaVersion);
        root.put("javarepoFileCount", mavenStats.files);
        root.put("javarepoSizeBytes", mavenStats.bytes);
        root.put("npmrepoFileCount", npmStats.files);
        root.put("npmrepoSizeBytes", npmStats.bytes);
        root.put("javarepoZipBytes", javarepoZipBytes);
        root.put("npmrepoZipBytes", npmrepoZipBytes);
        root.put("javarepoArchived", Files.isRegularFile(javarepoZip));
        root.put("npmrepoArchived", Files.isRegularFile(npmrepoZip));

        ArrayNode platformArray = root.putArray("platforms");
        for (Platform platform : platforms) {
            ObjectNode p = platformArray.addObject();
            p.put("id", platform.id());
            p.put("osName", platform.osName());
            p.put("osArch", platform.osArch());
            p.put("npmOs", platform.npmOs());
            p.put("npmCpu", platform.npmCpu());
        }

        ObjectNode families = root.putObject("platformFamilies");
        for (Map.Entry<String, MavenCollector.Family> entry : maven.families().entrySet()) {
            MavenCollector.Family family = entry.getValue();
            ObjectNode node = families.putObject(entry.getKey());
            node.put("base", family.base().gav());
            node.put("token", family.token());
            ObjectNode status = node.putObject("targets");
            for (Map.Entry<String, String> s : family.status().entrySet()) {
                if (s.getValue() == null) {
                    status.putNull(s.getKey());
                } else {
                    status.put(s.getKey(), s.getValue());
                }
            }
        }

        ArrayNode acceleratorArray = root.putArray("optionalAccelerators");
        maven.optionalAccelerators().forEach(acceleratorArray::add);

        ObjectNode npmNode = root.putObject("npm");
        if (npm == null) {
            npmNode.put("enabled", false);
        } else {
            npmNode.put("enabled", true);
            npmNode.put("packageCount", npm.packageCount());
            npmNode.put("tarballCount", npm.tarballCount());
            npmNode.put("registry", npm.registry());
        }

        ArrayNode warningArray = root.putArray("warnings");
        warnings.forEach(warningArray::add);

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(staging.resolve("manifest.json").toFile(), root);

        Files.writeString(staging.resolve("MANIFEST.txt"), textManifest(root, platforms),
                StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("gaps.txt"),
                warnings.isEmpty() ? "No gaps recorded.\n" : String.join("\n", warnings) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("README-offline.md"), readme(), StandardCharsets.UTF_8);
        List<Path> checksumTargets = new ArrayList<>();
        checksumTargets.add(Files.isRegularFile(javarepoZip) ? javarepoZip : javarepo);
        checksumTargets.add(Files.isRegularFile(npmrepoZip) ? npmrepoZip : npmrepo);
        writeChecksums(staging, checksumTargets);
    }

    private String textManifest(ObjectNode root, List<Platform> platforms) {
        StringBuilder sb = new StringBuilder();
        sb.append("SlackerDB Offline Package\n");
        sb.append("========================\n\n");
        sb.append("Generated: ").append(root.path("generatedAt").asText()).append('\n');
        sb.append("Maven    : ").append(root.path("mavenVersion").asText()).append('\n');
        sb.append("Java     : ").append(root.path("javaVersion").asText()).append('\n');
        sb.append("Targets  : ");
        sb.append(String.join(", ", platforms.stream().map(Platform::id).toList()));
        sb.append("\n\n");
        sb.append("Maven repository: ").append(root.path("javarepoFileCount").asInt())
                .append(" files, ").append(mb(root.path("javarepoSizeBytes").asLong())).append('\n');
        sb.append("npm repository  : ").append(root.path("npmrepoFileCount").asInt())
                .append(" files, ").append(mb(root.path("npmrepoSizeBytes").asLong())).append('\n');
        sb.append("javarepo.zip    : ").append(mb(root.path("javarepoZipBytes").asLong())).append('\n');
        sb.append("npmrepo.zip     : ").append(mb(root.path("npmrepoZipBytes").asLong())).append('\n');
        if (root.path("optionalAccelerators").isArray()
                && !root.path("optionalAccelerators").isEmpty()) {
            sb.append("Optional native accelerators (OS-specific, NIO fallback elsewhere):\n");
            root.path("optionalAccelerators").forEach(a -> sb.append("  - ").append(a.asText()).append('\n'));
        }
        sb.append('\n').append("Transfer: copy javarepo.zip and npmrepo.zip, then unzip into this directory.\n");
        sb.append("The uncompressed javarepo/ and npmrepo/ directories are removed by default;\n");
        sb.append("use -Doffline.zip=false if you want to keep them.\n");
        sb.append('\n').append("Usage (offline; <offline> is this directory):\n");
        sb.append("  mvn -o -Dmaven.repo.local=/tmp/m2 -Dmaven.repo.local.tail=<offline>/javarepo clean package\n\n");
        sb.append("  NPM_CONFIG_CACHE=<offline>/npmrepo NPM_CONFIG_OFFLINE=true ")
                .append("mvn -o -Dmaven.repo.local=/tmp/m2 ")
                .append("-Dmaven.repo.local.tail=<offline>/javarepo package\n");
        return sb.toString();
    }

    private static String readme() {
        return """
                # Offline package

                This directory is generated by `offline-package-maven-plugin`.

                * `javarepo.zip` - clean Maven local repository. Unzip it to `javarepo/`, then use
                  `-Dmaven.repo.local.tail=<dir>/javarepo` with a writable main repository.
                * `npmrepo.zip` - npm cache root plus tarball mirror. Unzip it to `npmrepo/`, then use
                  `NPM_CONFIG_CACHE=<dir>/npmrepo`.
                * `manifest.json` - machine-readable inventory.
                * `gaps.txt` - warnings that need manual review.

                The uncompressed `javarepo/` and `npmrepo/` directories are removed by default.
                Use `-Doffline.zip=false` when generating the package if you want to keep them.

                Usage (from this directory, with `javarepo.zip` and `npmrepo.zip` unzipped):

                    mvn -o -Dmaven.repo.local=/tmp/m2 -Dmaven.repo.local.tail=<offline>/javarepo clean package

                    NPM_CONFIG_CACHE=<offline>/npmrepo NPM_CONFIG_OFFLINE=true mvn -o -Dmaven.repo.local=/tmp/m2 -Dmaven.repo.local.tail=<offline>/javarepo package

                Deploy to `file://` must NOT use `-o`; Maven's deploy plugin rejects offline mode.
                All other commands can use `-o`.
                """;
    }

    private void writeChecksums(Path staging, List<Path> roots) throws Exception {
        Map<String, String> checksums = new TreeMap<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.walk(root)) {
                    for (Path path : stream.filter(Files::isRegularFile).toList()) {
                        String relative = staging.relativize(path).toString().replace('\\', '/');
                        checksums.put(relative, sha256(path));
                    }
                }
            } else if (Files.isRegularFile(root)) {
                String relative = staging.relativize(root).toString().replace('\\', '/');
                checksums.put(relative, sha256(root));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            sb.append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
        }
        Files.writeString(staging.resolve("checksums.sha256"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long size(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.size(path) : 0L;
    }

    private static FileStats stats(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return new FileStats(0, 0);
        }
        long[] values = new long[2];
        try (Stream<Path> stream = Files.walk(path)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                values[0]++;
                try {
                    values[1] += Files.size(p);
                } catch (IOException ignored) {
                    // size is informational only
                }
            });
        }
        return new FileStats(values[0], values[1]);
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private record FileStats(long files, long bytes) {
    }
}
