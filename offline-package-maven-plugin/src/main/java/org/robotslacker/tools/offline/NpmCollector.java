package org.robotslacker.tools.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * npm-side collector. The lockfile is the cross-platform source of truth;
 * npm CLI populates the cache and the tarball mirror.
 */
public final class NpmCollector {
    private final OfflineLog log;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String npmExecutable;

    public NpmCollector(OfflineLog log, String npmExecutable) {
        this.log = log;
        this.npmExecutable = npmExecutable == null || npmExecutable.isBlank()
                ? defaultNpm() : npmExecutable;
    }

    public NpmSummary collect(Path npmrepo, List<MavenProject> projects,
                              List<Platform> platforms, boolean writeTarballs)
            throws Exception {
        Files.createDirectories(npmrepo);
        Files.createDirectories(npmrepo.resolve("_cacache"));
        Path tarballs = npmrepo.resolve("tarballs");
        Files.createDirectories(tarballs);

        // On an offline machine the npm registry is unreachable, so a fresh cache cannot fetch
        // anything. NPM_CONFIG_CACHE (set by the offline build scripts) points at the previous
        // package's npmrepo: reuse its cache and tarballs as the seed. With a complete seed
        // `npm pack` is skipped entirely (tarballs already exist) and the regenerated package
        // stays self-contained.
        Path seed = npmCacheSeed(npmrepo);
        if (seed != null) {
            log.info("Seeding npm repository from " + seed);
            seedNpmRepo(seed, npmrepo);
        }

        List<Path> lockfiles = new ArrayList<>();
        for (MavenProject project : projects) {
            Path lock = project.getBasedir().toPath().resolve("package-lock.json");
            if (Files.isRegularFile(lock)) {
                lockfiles.add(lock);
            }
        }
        if (lockfiles.isEmpty()) {
            return new NpmSummary(0, 0, "", List.of("No package-lock.json found in reactor modules"));
        }

        Map<String, SelectedPackage> selected = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Path lockfile : lockfiles) {
            JsonNode root = mapper.readTree(lockfile.toFile());
            JsonNode packages = root.get("packages");
            if (packages == null || !packages.isObject()) {
                warnings.add("No packages map in " + lockfile);
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = packages.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getKey().startsWith("node_modules/")) {
                    continue;
                }
                JsonNode value = entry.getValue();
                String installName = packageName(entry.getKey());
                // npm aliases (e.g. "@typescript/native": "npm:typescript@7.0.2") store the
                // real registry package name in "name"; the lock key is only the install alias.
                String name = value.path("name").asText("");
                if (name.isEmpty()) {
                    name = installName;
                }
                String version = value.path("version").asText("");
                String resolved = value.path("resolved").asText("");
                String integrity = value.path("integrity").asText("");
                if (version.isEmpty() || resolved.isEmpty() || integrity.isEmpty()) {
                    warnings.add("Incomplete lock entry " + name + "@" + version);
                    continue;
                }
                Set<String> matched = new LinkedHashSet<>();
                for (Platform platform : platforms) {
                    if (matches(value, platform)) {
                        matched.add(platform.id());
                    }
                }
                if (matched.isEmpty()) {
                    continue;
                }
                final String actualName = name;
                final String aliasName = installName;
                SelectedPackage pkg = selected.computeIfAbsent(
                        actualName + "@" + version,
                        k -> new SelectedPackage(actualName, aliasName, version, resolved, integrity));
                pkg.platforms.addAll(matched);
            }
        }

        int tarballCount = 0;
        if (writeTarballs) {
            int index = 0;
            for (SelectedPackage pkg : selected.values()) {
                index++;
                Path tarball = tarballs.resolve(tarballName(pkg.name, pkg.version));
                if (!Files.isRegularFile(tarball)) {
                    pack(pkg, npmrepo, tarballs);
                }
                verifyIntegrity(tarball, pkg);
                pkg.tarball = "tarballs/" + tarball.getFileName();
                tarballCount++;
                if (index % 25 == 0 || index == selected.size()) {
                    log.info("npm tarballs: " + index + "/" + selected.size());
                }
            }
        }

        Path packagesFile = npmrepo.resolve("packages.json");
        ArrayNode array = mapper.createArrayNode();
        for (SelectedPackage pkg : selected.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", pkg.name);
            if (!pkg.installName.equals(pkg.name)) {
                node.put("installName", pkg.installName);
            }
            node.put("version", pkg.version);
            node.put("resolved", pkg.resolved);
            node.put("integrity", pkg.integrity);
            node.put("tarball", pkg.tarball);
            ArrayNode platformArray = node.putArray("platforms");
            pkg.platforms.stream().sorted().forEach(platformArray::add);
            array.add(node);
        }
        ObjectNode document = mapper.createObjectNode();
        document.put("lockfileVersion", 3);
        document.set("packages", array);
        mapper.writerWithDefaultPrettyPrinter().writeValue(packagesFile.toFile(), document);

        cleanupNpmMetadata(npmrepo);
        log.info("npm collection: " + selected.size() + " package(s), "
                + tarballCount + " tarball(s) for " + platforms.size() + " platform(s)");
        return new NpmSummary(selected.size(), tarballCount, "", warnings);
    }

    private void pack(SelectedPackage pkg, Path npmrepo, Path tarballs) throws Exception {
        List<String> command = List.of(
                npmExecutable, "pack", pkg.name + "@" + pkg.version,
                "--pack-destination", tarballs.toAbsolutePath().toString(),
                "--cache", npmrepo.toAbsolutePath().toString(),
                "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel", "error");
        CommandResult result;
        try {
            result = run(command, npmrepo);
        } catch (IllegalStateException failure) {
            throw new IllegalStateException("Cannot pack " + pkg.name + "@" + pkg.version
                    + " into the new npm cache. When this machine is offline, "
                    + "NPM_CONFIG_CACHE must point at the previous package's npmrepo so its "
                    + "cache and tarballs can be reused.\n" + failure.getMessage(), failure);
        }
        Path expected = tarballs.resolve(tarballName(pkg.name, pkg.version));
        if (!Files.isRegularFile(expected)) {
            throw new IllegalStateException("npm pack did not produce " + expected
                    + " for " + pkg.name + "@" + pkg.version + "\n" + result.output());
        }
    }

    /**
     * The previous npm repository, taken from {@code NPM_CONFIG_CACHE} (the variable the offline
     * build scripts already export). Returns {@code null} when unset, not a directory, or the
     * same directory as the one being built.
     */
    private static Path npmCacheSeed(Path npmrepo) {
        String configured = System.getenv("NPM_CONFIG_CACHE");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path seed;
        try {
            seed = Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
        Path target = npmrepo.toAbsolutePath().normalize();
        if (seed.equals(target) || !Files.isDirectory(seed.resolve("_cacache"))) {
            return null;
        }
        return seed;
    }

    /** Hard-link (or copy when cross-device) the previous cache and tarballs into the new repo. */
    private static void seedNpmRepo(Path seed, Path npmrepo) throws IOException {
        copyTreeIfEmpty(seed.resolve("_cacache"), npmrepo.resolve("_cacache"));
        copyTreeIfEmpty(seed.resolve("tarballs"), npmrepo.resolve("tarballs"));
    }

    private static void copyTreeIfEmpty(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source) || !isEmptyDirectory(target)) {
            return;
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try {
                    Files.createLink(destination, path);
                } catch (IOException | UnsupportedOperationException cannotLink) {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return true;
        }
        try (var entries = Files.list(path)) {
            return !entries.findAny().isPresent();
        }
    }

    private void verifyIntegrity(Path tarball, SelectedPackage pkg) throws Exception {
        String integrity = pkg.integrity;
        if (integrity == null || !integrity.startsWith("sha512-")) {
            throw new IllegalStateException("Only sha512 integrity is supported for "
                    + pkg.name + "@" + pkg.version + ": " + integrity);
        }
        byte[] expected = Base64.getDecoder().decode(integrity.substring("sha512-".length()));
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (var in = Files.newInputStream(tarball)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        if (!java.util.Arrays.equals(expected, digest.digest())) {
            throw new IllegalStateException("Integrity mismatch for " + tarball);
        }
    }

    private CommandResult run(List<String> command, Path workdir) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workdir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("NPM_CONFIG_CACHE", workdir.toAbsolutePath().toString());
        builder.environment().put("NPM_CONFIG_IGNORE_SCRIPTS", "true");
        builder.environment().put("NPM_CONFIG_AUDIT", "false");
        builder.environment().put("NPM_CONFIG_FUND", "false");
        builder.environment().put("NPM_CONFIG_UPDATE_NOTIFIER", "false");
        builder.environment().put("NPM_CONFIG_LOGS_MAX", "0");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("npm command failed (" + exit + "): "
                    + String.join(" ", command) + "\n" + tail(output));
        }
        return new CommandResult(exit, output);
    }

    private static void cleanupNpmMetadata(Path npmrepo) throws IOException {
        deleteRecursively(npmrepo.resolve("_logs"));
        Files.deleteIfExists(npmrepo.resolve("_update-notifier-last-checked"));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String tail(String output) {
        String[] lines = output.split("\\R");
        int start = Math.max(0, lines.length - 20);
        return String.join("\n", List.of(lines).subList(start, lines.length));
    }

    private static String packageName(String lockKey) {
        int idx = lockKey.lastIndexOf("/node_modules/");
        if (idx >= 0) {
            return lockKey.substring(idx + "/node_modules/".length());
        }
        return lockKey.substring("node_modules/".length());
    }

    private static String tarballName(String name, String version) {
        String base = name.startsWith("@") ? name.substring(1) : name;
        return base.replace('/', '-') + "-" + version + ".tgz";
    }

    private static boolean matches(JsonNode value, Platform platform) {
        if (!matchesArray(value.get("os"), platform.npmOs())) {
            return false;
        }
        if (!matchesArray(value.get("cpu"), platform.npmCpu())) {
            return false;
        }
        JsonNode libc = value.get("libc");
        if (libc != null && libc.isArray() && !libc.isEmpty()) {
            boolean glibc = false;
            for (JsonNode item : libc) {
                if ("glibc".equals(item.asText())) {
                    glibc = true;
                }
            }
            return glibc && platform.glibc();
        }
        return true;
    }

    private static boolean matchesArray(JsonNode node, String expected) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return true;
        }
        for (JsonNode item : node) {
            if (expected.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String defaultNpm() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "npm.cmd" : "npm";
    }

    public record NpmSummary(int packageCount, int tarballCount,
                             String registry, List<String> warnings) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static final class SelectedPackage {
        private final String name;
        private final String installName;
        private final String version;
        private final String resolved;
        private final String integrity;
        private final Set<String> platforms = new LinkedHashSet<>();
        private String tarball = "";

        private SelectedPackage(String name, String installName, String version,
                                String resolved, String integrity) {
            this.name = name;
            this.installName = installName;
            this.version = version;
            this.resolved = resolved;
            this.integrity = integrity;
        }
    }
}
