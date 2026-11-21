package org.robotslacker.tools.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * npm-side collector. The lockfile is the cross-platform source of truth;
 * npm CLI is only used for packages that no local cache can provide.
 */
public final class NpmCollector {
    private static final String CACACHE = "_cacache";
    private static final String CONTENT = "content-v2";
    private static final String INDEX = "index-v5";

    private final OfflineLog log;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String npmExecutable;
    private final List<Path> seedDirectories;
    private final List<Path> extraCacheDirectories;
    private final boolean useGlobalCache;
    private final boolean failOnEmpty;

    public NpmCollector(OfflineLog log, String npmExecutable) {
        this(log, npmExecutable, List.of(), List.of(), true, false);
    }

    public NpmCollector(OfflineLog log, String npmExecutable, List<Path> seedDirectories,
                        List<Path> extraCacheDirectories, boolean useGlobalCache) {
        this(log, npmExecutable, seedDirectories, extraCacheDirectories, useGlobalCache, false);
    }

    /**
     * @param failOnEmpty turn "no npm dependency could be determined" into a build failure. An
     *                    empty npm repository looks like a successful package but leaves the
     *                    offline machine without a single frontend dependency, so the packaging
     *                    build asks for a failure while a pure Java reactor may pass
     *                    {@code offline.npm.allow.empty=true} to keep the lenient behaviour.
     */
    public NpmCollector(OfflineLog log, String npmExecutable, List<Path> seedDirectories,
                        List<Path> extraCacheDirectories, boolean useGlobalCache,
                        boolean failOnEmpty) {
        this.log = log;
        this.npmExecutable = npmExecutable == null || npmExecutable.isBlank()
                ? defaultNpm() : npmExecutable;
        this.seedDirectories = seedDirectories == null ? List.of() : List.copyOf(seedDirectories);
        this.extraCacheDirectories = extraCacheDirectories == null
                ? List.of() : List.copyOf(extraCacheDirectories);
        this.useGlobalCache = useGlobalCache;
        this.failOnEmpty = failOnEmpty;
    }

    public NpmSummary collect(Path npmrepo, List<MavenProject> projects,
                              List<Platform> platforms, boolean writeTarballs)
            throws Exception {
        Files.createDirectories(npmrepo);
        Files.createDirectories(npmrepo.resolve(CACACHE));
        Path tarballs = npmrepo.resolve("tarballs");
        Files.createDirectories(tarballs);

        List<Path> lockfiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> modulesWithoutLockfile = new ArrayList<>();
        for (MavenProject project : projects) {
            Path basedir = project.getBasedir().toPath();
            Path lock = basedir.resolve("package-lock.json");
            if (Files.isRegularFile(lock)) {
                lockfiles.add(lock);
            } else if (Files.isRegularFile(basedir.resolve("package.json"))) {
                // A package.json without a lockfile is never usable here: npm only writes the
                // lockfile on install, and the offline package needs the lockfile as its source
                // of truth. Naming the module makes the gap actionable instead of mysterious.
                String module = project.getArtifactId() == null || project.getArtifactId().isBlank()
                        ? basedir.toString() : project.getArtifactId();
                modulesWithoutLockfile.add(module + " (" + basedir + ")");
            }
        }
        if (lockfiles.isEmpty()) {
            String message = emptyNpmReason(modulesWithoutLockfile, projects);
            if (failOnEmpty) {
                throw new IllegalStateException(message);
            }
            log.warn(message);
            return new NpmSummary(0, 0, "", List.of(message));
        }

        Map<String, SelectedPackage> selected = new LinkedHashMap<>();
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

        if (selected.isEmpty() && failOnEmpty) {
            throw new IllegalStateException("Read " + lockfiles.size() + " package-lock.json "
                    + "file(s) but no package matched the configured platforms ("
                    + platforms.stream().map(Platform::id).toList() + "). Check offline.platforms "
                    + "or the os/cpu fields of the lockfile, or pass "
                    + "-Doffline.npm.allow.empty=true.");
        }

        // Prefer an existing local cache over the network: the previous offline package, the
        // npm cache named by NPM_CONFIG_CACHE, explicit -Doffline.npm.cache.dirs entries, and
        // (by default) the machine's own npm cache. Only a package no cache can provide is
        // handed to `npm pack`.
        List<NpmCache> caches = writeTarballs ? localCaches(npmrepo) : List.of();
        if (writeTarballs && !caches.isEmpty()) {
            List<String> roots = new ArrayList<>();
            for (NpmCache cache : caches) {
                roots.add(cache.root.toString());
            }
            log.info("npm local caches: " + String.join(", ", roots));
        }

        int tarballCount = 0;
        int localTarballCount = 0;
        int downloadedTarballCount = 0;
        if (writeTarballs) {
            int index = 0;
            for (SelectedPackage pkg : selected.values()) {
                index++;
                Path tarball = tarballs.resolve(tarballName(pkg.name, pkg.version));
                if (!Files.isRegularFile(tarball)) {
                    if (materialize(pkg, caches, npmrepo, tarball)) {
                        localTarballCount++;
                    } else {
                        pack(pkg, npmrepo, tarballs);
                        downloadedTarballCount++;
                    }
                } else {
                    localTarballCount++;
                }
                checkIntegrity(tarball, pkg);
                pkg.tarball = "tarballs/" + tarball.getFileName();
                tarballCount++;
                if (index % 25 == 0 || index == selected.size()) {
                    log.info("npm tarballs: " + index + "/" + selected.size());
                }
            }
            log.info("npm tarballs: " + localTarballCount + " reused from local cache, "
                    + downloadedTarballCount + " fetched with npm pack");
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

    /**
     * Explains why no npm dependency could be determined. The usual cause is a Vaadin/Flow
     * module: Flow writes package.json and package-lock.json only while the frontend build runs
     * and deletes both again when it created them itself, so a module whose npm files are not
     * committed has no lockfile between builds and the offline package ends up empty.
     */
    private static String emptyNpmReason(List<String> modulesWithoutLockfile,
                                         List<MavenProject> projects) {
        StringBuilder sb = new StringBuilder();
        sb.append("No package-lock.json in the scanned Maven modules (")
                .append(projects.size()).append(" module(s)), so the npm repository is empty.");
        if (!modulesWithoutLockfile.isEmpty()) {
            sb.append(" These modules have package.json but no package-lock.json: ")
                    .append(String.join(", ", modulesWithoutLockfile))
                    .append(". Run one full build so npm writes the lockfile, and commit both")
                    .append(" package.json and package-lock.json in the module root; otherwise")
                    .append(" every build starts without a lockfile.")
                    .append(" A Vaadin/Flow module also drops the pair when Flow generated it")
                    .append(" itself, so only a committed copy survives the build.");
        } else {
            sb.append(" Add the module that owns the frontend (package.json), or commit its")
                    .append(" package-lock.json; no reactor module declares one today.");
        }
        sb.append(" Pass -Doffline.npm.allow.empty=true if this reactor has no npm dependencies.");
        return sb.toString();
    }

    /**
     * Finds a selected tarball in the local caches. On a hit the tarball mirror and the matching
     * npm cache entries are copied (hard-linked when possible) into the new repository, so the
     * generated package stays usable with {@code NPM_CONFIG_CACHE} and no npm process is needed.
     */
    private boolean materialize(SelectedPackage pkg, List<NpmCache> caches,
                                Path npmrepo, Path tarball) {
        Path targetCacache = npmrepo.resolve(CACACHE);
        for (NpmCache cache : caches) {
            Path content = contentPath(cache.cacache(), pkg.integrity);
            if (content != null && Files.isRegularFile(content)) {
                if (!hasIntegrity(content, pkg)) {
                    log.warn("Ignoring npm cache entry with a bad digest: " + content
                            + " for " + pkg.name + "@" + pkg.version);
                    continue;
                }
                try {
                    linkOrCopy(content, tarball);
                    Path targetContent = contentPath(targetCacache, pkg.integrity);
                    if (targetContent != null) {
                        linkOrCopy(content, targetContent);
                    }
                } catch (IOException | RuntimeException failure) {
                    log.warn("Cannot reuse npm cache entry " + content + " for "
                            + pkg.name + "@" + pkg.version + ": " + failure);
                    deleteQuietly(tarball);
                    continue;
                }
                copyIndexEntriesQuietly(cache, pkg, npmrepo);
                return true;
            }
            Path mirror = cache.mirror(pkg);
            if (mirror != null) {
                if (!hasIntegrity(mirror, pkg)) {
                    log.warn("Ignoring npm tarball with a bad digest: " + mirror
                            + " for " + pkg.name + "@" + pkg.version);
                    continue;
                }
                try {
                    linkOrCopy(mirror, tarball);
                    Path targetContent = contentPath(targetCacache, pkg.integrity);
                    if (targetContent != null) {
                        linkOrCopy(mirror, targetContent);
                    }
                } catch (IOException | RuntimeException failure) {
                    log.warn("Cannot reuse npm tarball " + mirror + " for "
                            + pkg.name + "@" + pkg.version + ": " + failure);
                    deleteQuietly(tarball);
                    continue;
                }
                copyIndexEntriesQuietly(cache, pkg, npmrepo);
                return true;
            }
        }
        return false;
    }

    /**
     * Copies the npm cache index entries that belong to the package: the tarball entry (matched
     * by integrity) and the packument entry (matched by URL). Their content blobs are copied as
     * well, so the result is a normal npm cache a later offline {@code npm install} can use.
     */
    private void copyIndexEntries(NpmCache cache, SelectedPackage pkg, Path npmrepo)
            throws IOException {
        NpmCacheIndex index = cache.index(mapper);
        if (index == null) {
            return;
        }
        List<IndexEntry> entries = new ArrayList<>(index.tarballs(pkg.integrity));
        entries.addAll(index.packuments(pkg.name, pkg.resolved));
        Path targetCacache = npmrepo.resolve(CACACHE);
        for (IndexEntry entry : entries) {
            Path sourceContent = contentPath(cache.cacache(), entry.integrity());
            if (sourceContent == null || !Files.isRegularFile(sourceContent)) {
                continue;
            }
            Path targetFile = targetCacache.resolve(INDEX).resolve(entry.relativePath());
            appendIndexLine(targetFile, entry.line());
            Path targetContent = contentPath(targetCacache, entry.integrity());
            if (targetContent != null) {
                linkOrCopy(sourceContent, targetContent);
            }
        }
    }

    /** Index metadata is nice to have; a copy failure must not discard a valid tarball hit. */
    private void copyIndexEntriesQuietly(NpmCache cache, SelectedPackage pkg, Path npmrepo) {
        try {
            copyIndexEntries(cache, pkg, npmrepo);
        } catch (IOException | RuntimeException failure) {
            log.warn("Cannot copy npm cache index entries for " + pkg.name + "@"
                    + pkg.version + ": " + failure);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort; the final integrity check will fail if the file is unusable
        }
    }

    private List<NpmCache> localCaches(Path npmrepo) {
        List<NpmCache> caches = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        Path target = npmrepo.toAbsolutePath().normalize();
        addCache(caches, seen, npmCacheSeed(), target);
        for (Path directory : seedDirectories) {
            addCache(caches, seen, directory, target);
        }
        for (Path directory : extraCacheDirectories) {
            addCache(caches, seen, directory, target);
        }
        if (useGlobalCache) {
            addCache(caches, seen, globalNpmCache(), target);
        }
        return caches;
    }

    private static void addCache(List<NpmCache> caches, Set<Path> seen,
                                 Path candidate, Path target) {
        if (candidate == null) {
            return;
        }
        Path path;
        try {
            path = candidate.toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return;
        }
        if (!Files.isDirectory(path) || path.startsWith(target) || !seen.add(path)) {
            return;
        }
        caches.add(new NpmCache(path));
    }

    /**
     * The previous npm repository named by {@code NPM_CONFIG_CACHE}. The variable is what the
     * offline build scripts already export, so it stays a convenient way to point the collector
     * at a known-good cache.
     */
    private static Path npmCacheSeed() {
        String configured = System.getenv("NPM_CONFIG_CACHE");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("npm_config_cache");
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return Path.of(configured);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * The machine-wide npm cache. Asking npm is the only reliable way to honour a {@code cache=}
     * entry in .npmrc; when npm cannot be queried, fall back to npm's documented platform default.
     */
    private Path globalNpmCache() {
        Path configured = npmConfiguredCache();
        if (configured != null) {
            return configured;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                try {
                    return Path.of(localAppData).resolve("npm-cache");
                } catch (RuntimeException invalid) {
                    // fall through to ~/.npm
                }
            }
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        try {
            return Path.of(home).resolve(".npm");
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Path npmConfiguredCache() {
        try {
            ProcessBuilder builder = new ProcessBuilder(npmExecutable, "config", "get", "cache");
            builder.redirectErrorStream(true);
            // Do not let the seed variable override the machine-wide cache we are asking about.
            builder.environment().remove("NPM_CONFIG_CACHE");
            builder.environment().remove("npm_config_cache");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String[] lines = output.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(line);
                if (candidate.isAbsolute() && Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            // npm missing or not runnable: fall back to the platform default.
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    private void pack(SelectedPackage pkg, Path npmrepo, Path tarballs) throws Exception {
        List<String> command = List.of(
                npmExecutable, "pack", pkg.name + "@" + pkg.version,
                "--pack-destination", tarballs.toAbsolutePath().toString(),
                "--cache", npmrepo.toAbsolutePath().toString(),
                "--prefer-offline",
                "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel", "error");
        CommandResult result;
        try {
            result = run(command, npmrepo);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Cannot run npm (" + npmExecutable + ") to pack "
                    + pkg.name + "@" + pkg.version + ", and no local npm cache contains it. "
                    + "Put npm on PATH, set -Doffline.npm.executable, or point "
                    + "-Doffline.npm.cache.dirs at a cache that has this tarball.\n"
                    + unavailable.getMessage(), unavailable);
        } catch (IllegalStateException failure) {
            throw new IllegalStateException("Cannot pack " + pkg.name + "@" + pkg.version
                    + " and no local npm cache contains it. Point NPM_CONFIG_CACHE at a "
                    + "previous package's npmrepo or pass -Doffline.npm.cache.dirs to a local "
                    + "cache, or make sure the machine can reach the npm registry.\n"
                    + failure.getMessage(), failure);
        }
        Path expected = tarballs.resolve(tarballName(pkg.name, pkg.version));
        if (!Files.isRegularFile(expected)) {
            throw new IllegalStateException("npm pack did not produce " + expected
                    + " for " + pkg.name + "@" + pkg.version + "\n" + result.output());
        }
    }

    private void checkIntegrity(Path tarball, SelectedPackage pkg) throws Exception {
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
            throw new IllegalStateException("Integrity mismatch for " + tarball
                    + " (" + pkg.name + "@" + pkg.version + ")");
        }
    }

    private boolean hasIntegrity(Path file, SelectedPackage pkg) {
        try {
            checkIntegrity(file, pkg);
            return true;
        } catch (Exception mismatch) {
            return false;
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

    /** Maps an SRI string ({@code sha512-<base64>}) to its cacache content file. */
    private static Path contentPath(Path cacache, String integrity) {
        int dash = integrity.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        String algorithm = integrity.substring(0, dash);
        byte[] digest;
        try {
            digest = Base64.getDecoder().decode(integrity.substring(dash + 1));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String hex = HexFormat.of().formatHex(digest);
        if (hex.length() < 5) {
            return null;
        }
        return cacache.resolve(CONTENT).resolve(algorithm)
                .resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4))
                .resolve(hex.substring(4));
    }

    private static void appendIndexLine(Path file, String line) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            if (existing.contains(line)) {
                return;
            }
        }
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Hard-link a cache blob into the new repository; fall back to a copy when the two paths are
     * on different volumes or the file system does not allow links.
     */
    private static void linkOrCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            try {
                if (Files.isSameFile(source, target)) {
                    return;
                }
            } catch (IOException notSame) {
                // fall through and attempt the link
            }
        }
        try {
            Files.createLink(target, source);
            return;
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            Files.delete(target);
        } catch (IOException | UnsupportedOperationException cannotLink) {
            // copy below
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "npm.cmd" : "npm";
    }

    private static boolean isPackumentUrl(String url, String name) {
        String normalized = url.toLowerCase(Locale.ROOT).replace("%2f", "/");
        if (normalized.endsWith(".tgz") || normalized.contains("/-/")) {
            return false;
        }
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String plain = name.toLowerCase(Locale.ROOT);
        String suffix = "/" + plain;
        if (!normalized.endsWith(suffix)) {
            return false;
        }
        if (plain.contains("/")) {
            return true;
        }
        // An unscoped name must not be the last segment of a scoped packument URL
        // (e.g. "core" must not match ".../@babel/core").
        String prefix = normalized.substring(0, normalized.length() - suffix.length());
        int slash = prefix.lastIndexOf('/');
        return !prefix.substring(slash + 1).startsWith("@");
    }

    private static String origin(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT)
                    + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public record NpmSummary(int packageCount, int tarballCount,
                             String registry, List<String> warnings) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record IndexEntry(Path relativePath, String line, String integrity,
                              String url, long time) {
    }

    /** One npm cache root (a directory that may contain {@code _cacache/} and {@code tarballs/}). */
    private static final class NpmCache {
        private final Path root;
        private NpmCacheIndex index;
        private boolean indexLoaded;

        private NpmCache(Path root) {
            this.root = root;
        }

        private Path cacache() {
            return root.resolve(CACACHE);
        }

        private Path mirror(SelectedPackage pkg) {
            String name = tarballName(pkg.name, pkg.version);
            Path nested = root.resolve("tarballs").resolve(name);
            if (Files.isRegularFile(nested)) {
                return nested;
            }
            Path flat = root.resolve(name);
            return Files.isRegularFile(flat) ? flat : null;
        }

        private NpmCacheIndex index(ObjectMapper mapper) {
            if (!indexLoaded) {
                indexLoaded = true;
                index = NpmCacheIndex.load(cacache(), mapper);
            }
            return index;
        }
    }

    /** The parts of {@code _cacache/index-v5} the collector needs, loaded on first use. */
    private static final class NpmCacheIndex {
        private final Map<String, List<IndexEntry>> byIntegrity;
        private final Map<String, List<IndexEntry>> byUrl;

        private NpmCacheIndex(Map<String, List<IndexEntry>> byIntegrity,
                              Map<String, List<IndexEntry>> byUrl) {
            this.byIntegrity = byIntegrity;
            this.byUrl = byUrl;
        }

        private static NpmCacheIndex load(Path cacache, ObjectMapper mapper) {
            Path indexDirectory = cacache.resolve(INDEX);
            if (!Files.isDirectory(indexDirectory)) {
                return null;
            }
            Map<String, List<IndexEntry>> byIntegrity = new HashMap<>();
            Map<String, List<IndexEntry>> byUrl = new HashMap<>();
            try (var files = Files.walk(indexDirectory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path relative = indexDirectory.relativize(file);
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    for (String line : lines) {
                        int tab = line.indexOf('\t');
                        if (tab < 0) {
                            continue;
                        }
                        JsonNode node;
                        try {
                            node = mapper.readTree(line.substring(tab + 1));
                        } catch (IOException invalid) {
                            continue;
                        }
                        String integrity = node.path("integrity").asText("");
                        String url = node.path("metadata").path("url").asText("");
                        if (integrity.isEmpty() || url.isEmpty()) {
                            continue;
                        }
                        IndexEntry entry = new IndexEntry(relative, line, integrity, url,
                                node.path("time").asLong(0));
                        byIntegrity.computeIfAbsent(integrity, k -> new ArrayList<>()).add(entry);
                        byUrl.computeIfAbsent(url, k -> new ArrayList<>()).add(entry);
                    }
                }
            } catch (IOException unreadableCache) {
                return null;
            }
            return new NpmCacheIndex(byIntegrity, byUrl);
        }

        private List<IndexEntry> tarballs(String integrity) {
            List<IndexEntry> result = new ArrayList<>();
            for (IndexEntry entry : byIntegrity.getOrDefault(integrity, List.of())) {
                if (entry.url().endsWith(".tgz")) {
                    result.add(entry);
                }
            }
            return result;
        }

        /**
         * One packument entry per registry URL. When the lockfile registry is present in the
         * cache only those entries are used; otherwise every known registry is carried over.
         */
        private List<IndexEntry> packuments(String name, String resolved) {
            Map<String, IndexEntry> latest = new LinkedHashMap<>();
            for (Map.Entry<String, List<IndexEntry>> candidate : byUrl.entrySet()) {
                if (!isPackumentUrl(candidate.getKey(), name)) {
                    continue;
                }
                candidate.getValue().stream()
                        .max(Comparator.comparingLong(IndexEntry::time))
                        .ifPresent(entry -> latest.put(candidate.getKey(), entry));
            }
            if (latest.isEmpty()) {
                return List.of();
            }
            String wanted = origin(resolved);
            if (wanted != null) {
                List<IndexEntry> sameOrigin = new ArrayList<>();
                for (Map.Entry<String, IndexEntry> entry : latest.entrySet()) {
                    if (wanted.equals(origin(entry.getKey()))) {
                        sameOrigin.add(entry.getValue());
                    }
                }
                if (!sameOrigin.isEmpty()) {
                    return sameOrigin;
                }
            }
            return new ArrayList<>(latest.values());
        }
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
