package org.robotslacker.tools.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The npm collector must consume local caches before it ever starts an npm process. These tests
 * build a fake cache and use an npm executable that cannot exist, so a successful run proves that
 * the network path was never taken.
 */
class NpmCollectorTest {
    private static final String NAME = "left-pad";
    private static final String VERSION = "1.3.0";
    private static final String RESOLVED =
            "https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz";
    private static final byte[] TARBALL =
            "pretend this is a gzipped npm tarball".getBytes(StandardCharsets.UTF_8);
    private static final String INTEGRITY = integrity(TARBALL);

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OfflineLog log = new OfflineLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    };

    @Test
    void reusesContentAddressedCacheWithoutRunningNpm() throws Exception {
        Path cache = root.resolve("cache");
        writeContent(cache, INTEGRITY, TARBALL);
        Path project = projectWithLockfile();

        Path output = root.resolve("npmrepo");
        collector(List.of(), List.of(cache)).collect(output, List.of(asProject(project)),
                List.of(Platform.byId("windows-x86_64")), true);

        assertArrayEquals(TARBALL,
                Files.readAllBytes(output.resolve("tarballs").resolve("left-pad-1.3.0.tgz")));
        assertArrayEquals(TARBALL, Files.readAllBytes(contentPath(output, INTEGRITY)));
        assertEquals(NAME, packages(output).get(0).path("name").asText());
        assertEquals("tarballs/left-pad-1.3.0.tgz",
                packages(output).get(0).path("tarball").asText());
    }

    @Test
    void copiesIndexEntriesAndPackumentMetadata() throws Exception {
        Path cache = root.resolve("cache");
        writeContent(cache, INTEGRITY, TARBALL);
        writeIndex(cache, RESOLVED, INTEGRITY);
        String packument = """
                {"name":"left-pad","dist-tags":{"latest":"1.3.0"},
                 "versions":{"1.3.0":{"name":"left-pad","version":"1.3.0",
                 "dist":{"integrity":"%s","tarball":"%s"}}}}
                """.formatted(INTEGRITY, RESOLVED);
        String packumentIntegrity = integrity(packument.getBytes(StandardCharsets.UTF_8));
        writeContent(cache, packumentIntegrity, packument.getBytes(StandardCharsets.UTF_8));
        writeIndex(cache, "https://registry.npmjs.org/left-pad", packumentIntegrity);

        Path output = root.resolve("npmrepo");
        collector(List.of(), List.of(cache)).collect(output, List.of(asProject(projectWithLockfile())),
                List.of(Platform.byId("windows-x86_64")), true);

        assertTrue(Files.isRegularFile(contentPath(output, packumentIntegrity)));
        assertTrue(indexContains(output, "make-fetch-happen:request-cache:" + RESOLVED));
        assertTrue(indexContains(output, "make-fetch-happen:request-cache:https://registry.npmjs.org/left-pad"));
    }

    @Test
    void reusesPlainTarballMirrorWithoutCacheIndex() throws Exception {
        Path mirror = root.resolve("mirror");
        Files.createDirectories(mirror.resolve("tarballs"));
        Files.write(mirror.resolve("tarballs").resolve("left-pad-1.3.0.tgz"), TARBALL);

        Path output = root.resolve("npmrepo");
        collector(List.of(), List.of(mirror)).collect(output,
                List.of(asProject(projectWithLockfile())),
                List.of(Platform.byId("windows-x86_64")), true);

        assertArrayEquals(TARBALL,
                Files.readAllBytes(output.resolve("tarballs").resolve("left-pad-1.3.0.tgz")));
        assertArrayEquals(TARBALL, Files.readAllBytes(contentPath(output, INTEGRITY)));
    }

    @Test
    void doesNotBorrowPackumentOfAnotherScopedPackage() throws Exception {
        String resolved = "https://registry.npmjs.org/core/-/core-1.0.0.tgz";
        byte[] tarball = "a tarball named core".getBytes(StandardCharsets.UTF_8);
        String integrity = integrity(tarball);
        Path cache = root.resolve("cache");
        writeContent(cache, integrity, tarball);
        writeIndex(cache, resolved, integrity);

        String unrelated = """
                {"name":"@babel/core","dist-tags":{"latest":"7.0.0"},
                 "versions":{"7.0.0":{"name":"@babel/core","version":"7.0.0"}}}
                """;
        String unrelatedIntegrity = integrity(unrelated.getBytes(StandardCharsets.UTF_8));
        writeContent(cache, unrelatedIntegrity, unrelated.getBytes(StandardCharsets.UTF_8));
        writeIndex(cache, "https://registry.npmjs.org/@babel/core", unrelatedIntegrity);

        Path output = root.resolve("npmrepo");
        collector(List.of(), List.of(cache)).collect(output,
                List.of(asProject(projectWithLockfile("core", "1.0.0", resolved, integrity))),
                List.of(Platform.byId("windows-x86_64")), true);

        assertArrayEquals(tarball, Files.readAllBytes(contentPath(output, integrity)));
        assertFalse(indexContains(output,
                "make-fetch-happen:request-cache:https://registry.npmjs.org/@babel/core"));
    }

    @Test
    void collectsAliasedPackageUnderItsRegistryName() throws Exception {
        // package.json maps "@typescript/native" to "npm:typescript@7.0.2"; the lockfile keeps
        // the alias as the install key and the registry package in "name".
        String resolved = "https://registry.npmjs.org/typescript/-/typescript-7.0.2.tgz";
        byte[] tarball = "a tarball fetched as a typescript alias".getBytes(StandardCharsets.UTF_8);
        String integrity = integrity(tarball);
        Path cache = root.resolve("cache");
        writeContent(cache, integrity, tarball);

        String lockfile = """
                {
                  "name": "test-project",
                  "version": "1.0.0",
                  "lockfileVersion": 3,
                  "packages": {
                    "": {"name": "test-project", "version": "1.0.0"},
                    "node_modules/@typescript/native": {
                      "name": "typescript",
                      "version": "7.0.2",
                      "resolved": "%s",
                      "integrity": "%s"
                    }
                  }
                }
                """.formatted(resolved, integrity);
        Path project = projectWithLockfile(lockfile);

        Path output = root.resolve("npmrepo");
        collector(List.of(), List.of(cache)).collect(output, List.of(asProject(project)),
                List.of(Platform.byId("windows-x86_64")), true);

        JsonNode pkg = packages(output).get(0);
        assertEquals("typescript", pkg.path("name").asText());
        assertEquals("@typescript/native", pkg.path("installName").asText());
        assertEquals("tarballs/typescript-7.0.2.tgz", pkg.path("tarball").asText());
    }

    @Test
    void failsWhenOnlyPackageJsonExistsAndFailureIsRequested() throws Exception {
        Path project = root.resolve("frontend-module");
        Files.createDirectories(project);
        Files.writeString(project.resolve("package.json"), "{\"name\":\"frontend-module\"}",
                StandardCharsets.UTF_8);
        MavenProject mavenProject = asProject(project);
        mavenProject.setArtifactId("frontend-module");

        Path output = root.resolve("npmrepo");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new NpmCollector(log, "npm-executable-that-does-not-exist",
                        List.of(), List.of(), false, true)
                        .collect(output, List.of(mavenProject),
                                List.of(Platform.byId("windows-x86_64")), true));

        String message = failure.getMessage();
        assertTrue(message.contains("package-lock.json"), message);
        assertTrue(message.contains("frontend-module"), message);
        assertTrue(message.contains("-Doffline.npm.allow.empty=true"), message);
    }

    @Test
    void reportsEmptyNpmRepositoryAsWarningWhenFailureIsNotRequested() throws Exception {
        Path project = root.resolve("no-npm-module");
        Files.createDirectories(project);
        Files.writeString(project.resolve("package.json"), "{\"name\":\"plain\"}",
                StandardCharsets.UTF_8);

        Path output = root.resolve("npmrepo");
        NpmCollector.NpmSummary summary = collector(List.of(), List.of())
                .collect(output, List.of(asProject(project)),
                        List.of(Platform.byId("windows-x86_64")), true);

        assertEquals(0, summary.packageCount());
        assertFalse(summary.warnings().isEmpty());
        assertTrue(summary.warnings().get(0).contains("package-lock.json"));
    }

    private NpmCollector collector(List<Path> seeds, List<Path> extraCaches) {
        return new NpmCollector(log, "npm-executable-that-does-not-exist",
                seeds, extraCaches, false);
    }

    private Path projectWithLockfile() throws Exception {
        return projectWithLockfile(NAME, VERSION, RESOLVED, INTEGRITY);
    }

    private Path projectWithLockfile(String name, String version, String resolved,
                                     String integrity) throws Exception {
        return projectWithLockfile("""
                {
                  "name": "test-project",
                  "version": "1.0.0",
                  "lockfileVersion": 3,
                  "packages": {
                    "": {"name": "test-project", "version": "1.0.0"},
                    "node_modules/%s": {
                      "version": "%s",
                      "resolved": "%s",
                      "integrity": "%s"
                    }
                  }
                }
                """.formatted(name, version, resolved, integrity));
    }

    private Path projectWithLockfile(String lockfile) throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("package-lock.json"), lockfile,
                StandardCharsets.UTF_8);
        return project;
    }

    private static MavenProject asProject(Path basedir) {
        MavenProject project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        return project;
    }

    private static JsonNode packages(Path npmrepo) throws Exception {
        return new ObjectMapper().readTree(npmrepo.resolve("packages.json").toFile())
                .path("packages");
    }

    private static void writeContent(Path cache, String integrity, byte[] bytes)
            throws Exception {
        Path content = contentPath(cache, integrity);
        Files.createDirectories(content.getParent());
        Files.write(content, bytes);
    }

    /**
     * Writes one cacache index entry. The file path is sha256(key); the line prefix is
     * sha1(JSON), exactly what cacache's entry-index verifies when reading.
     */
    private void writeIndex(Path cache, String url, String integrity) throws Exception {
        String key = "make-fetch-happen:request-cache:" + url;
        var metadata = mapper.createObjectNode();
        metadata.put("time", System.currentTimeMillis());
        metadata.put("url", url);
        var entry = mapper.createObjectNode();
        entry.put("key", key);
        entry.put("integrity", integrity);
        entry.put("time", System.currentTimeMillis());
        entry.put("size", TARBALL.length);
        entry.set("metadata", metadata);
        String json = entry.toString();
        String line = sha1(json) + "\t" + json;
        Path file = cache.resolve("_cacache").resolve("index-v5")
                .resolve(segment(sha256(key), 0)).resolve(segment(sha256(key), 2))
                .resolve(sha256(key).substring(4));
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8);
    }

    private static boolean indexContains(Path npmrepo, String text) throws Exception {
        Path index = npmrepo.resolve("_cacache").resolve("index-v5");
        try (var files = Files.walk(index)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path contentPath(Path cacheRoot, String integrity) {
        int dash = integrity.indexOf('-');
        byte[] digest = Base64.getDecoder().decode(integrity.substring(dash + 1));
        String hex = HexFormat.of().formatHex(digest);
        return cacheRoot.resolve("_cacache").resolve("content-v2")
                .resolve(integrity.substring(0, dash))
                .resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4))
                .resolve(hex.substring(4));
    }

    private static String segment(String hex, int from) {
        return hex.substring(from, from + 2);
    }

    private static String hexDigest(String algorithm, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String sha1(String text) throws Exception {
        return hexDigest("SHA-1", text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String text) throws Exception {
        return hexDigest("SHA-256", text.getBytes(StandardCharsets.UTF_8));
    }

    private static String integrity(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            return "sha512-" + Base64.getEncoder().encodeToString(digest.digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
