package org.robotslacker.tools.offline;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.mapping.LifecycleMapping;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.filter.AndDependencyFilter;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maven-side collector: resolves the reactor closure for three target platforms,
 * expands platform families, and materializes a clean local repository.
 */
public final class MavenCollector {
    private static final List<String> SUPPORTED_SCOPES =
            List.of("compile", "runtime", "test", "provided");

    private static final Map<String, List<String>> TARGET_TOKENS = Map.of(
            "windows-x86_64", List.of("windows-x86_64", "win32-x86-64", "windows-amd64", "win-x64"),
            "linux-amd64", List.of("linux-x86_64", "linux-amd64", "linux-x64"),
            "linux-arm64", List.of("linux-aarch64", "linux-arm64", "linux-aarch_64")
    );

    /** Tokens that are native/platform-style but outside the three targets. */
    private static final List<String> UNSUPPORTED_TOKENS = List.of(
            "osx-aarch_64", "osx-x86_64", "macos-aarch64", "macos-x86_64",
            "darwin-arm64", "darwin-x86_64", "linux-riscv64", "linux-armv7",
            "linux-s390x", "linux-ppc64le", "win32-arm64", "windows-aarch64"
    );

    private static final List<String> ALL_TOKENS = allTokens();

    private final MavenSession session;
    private final RepositorySystem repositorySystem;
    private final ProjectDependenciesResolver dependenciesResolver;
    private final MavenPluginManager pluginManager;
    private final OfflineLog log;
    private final List<Platform> platforms;
    private final boolean candidateJars;
    private final Set<String> projectGavs = new HashSet<>();
    private final Map<String, ArtifactRecord> artifacts = new LinkedHashMap<>();
    private final Map<String, Family> families = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> optionalAccelerators = new ArrayList<>();

    public MavenCollector(MavenSession session,
                          RepositorySystem repositorySystem,
                          ProjectDependenciesResolver dependenciesResolver,
                          MavenPluginManager pluginManager,
                          OfflineLog log,
                          List<Platform> platforms,
                          boolean candidateJars) {
        this.session = session;
        this.repositorySystem = repositorySystem;
        this.dependenciesResolver = dependenciesResolver;
        this.pluginManager = pluginManager;
        this.log = log;
        this.platforms = platforms;
        this.candidateJars = candidateJars;
        for (MavenProject p : session.getProjects()) {
            projectGavs.add(gav(p.getGroupId(), p.getArtifactId(), p.getVersion()));
        }
    }

    public MavenCollectionResult collect(Path javarepo) throws Exception {
        log.info("Collecting Maven closure for " + platforms.size() + " platform(s)");
        for (Platform platform : platforms) {
            RepositorySystemSession rs = sessionFor(platform);
            collectDependencies(rs);
            collectPlugins(rs);
            collectSurefireProvider(rs);
        }
        expandPlatformFamilies();
        MaterializeResult materialized = materialize(javarepo);
        return new MavenCollectionResult(artifacts.size(), materialized.poms(),
                materialized.candidateJars(), materialized.candidateJarCandidates(),
                families, warnings, optionalAccelerators);
    }

    private RepositorySystemSession sessionFor(Platform platform) {
        DefaultRepositorySystemSession rs =
                new DefaultRepositorySystemSession(session.getRepositorySession());
        Map<String, String> props = new LinkedHashMap<>(rs.getSystemProperties());
        props.put("os.name", platform.osName());
        props.put("os.arch", platform.osArch());
        props.put("os.version", platform.osVersion());
        props.put("os.detected.name", detectedName(platform));
        props.put("os.detected.arch", detectedArch(platform));
        props.put("os.detected.classifier", platform.detectedClassifier());
        rs.setSystemProperties(props);
        Map<String, String> userProps = new LinkedHashMap<>(rs.getUserProperties());
        userProps.putAll(props);
        rs.setUserProperties(userProps);
        // POM models and descriptor results are cached per session. Never share them across platforms.
        rs.setCache(new org.eclipse.aether.DefaultRepositoryCache());
        rs.setData(new org.eclipse.aether.DefaultSessionData());
        return rs;
    }

    private static String detectedName(Platform p) {
        return switch (p.npmOs()) {
            case "win32" -> "windows";
            case "linux" -> "linux";
            default -> p.npmOs();
        };
    }

    private static String detectedArch(Platform p) {
        return switch (p.osArch()) {
            case "amd64" -> "x86_64";
            case "aarch64" -> "aarch64";
            default -> p.osArch();
        };
    }

    private void collectDependencies(RepositorySystemSession rs) throws Exception {
        for (MavenProject p : session.getProjects()) {
            DefaultDependencyResolutionRequest request =
                    new DefaultDependencyResolutionRequest(p, rs);
            request.setResolutionFilter(new AndDependencyFilter(
                    new ScopeDependencyFilter(SUPPORTED_SCOPES, List.of("system")),
                    new ProjectGavFilter(projectGavs)));
            DependencyResolutionResult result;
            try {
                result = dependenciesResolver.resolve(request);
            } catch (DependencyResolutionException e) {
                throw new IllegalStateException("Cannot resolve dependencies for " + p.getArtifactId(), e);
            }
            if (!result.getUnresolvedDependencies().isEmpty()) {
                throw new IllegalStateException("Unresolved dependencies in " + p.getArtifactId()
                        + ": " + result.getUnresolvedDependencies());
            }
            if (!result.getCollectionErrors().isEmpty()) {
                throw new IllegalStateException("Dependency collection errors in " + p.getArtifactId()
                        + ": " + result.getCollectionErrors());
            }
            for (Dependency dependency : result.getResolvedDependencies()) {
                add(dependency.getArtifact(), "dependency");
            }
        }
    }

    private void collectPlugins(RepositorySystemSession rs) throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        for (MavenProject p : session.getProjects()) {
            Map<String, Plugin> managed = managedPlugins(p);
            for (Plugin plugin : p.getBuildPlugins()) {
                collectPlugin(p, resolvePluginVersion(plugin, managed), rs, seen, "declared-plugin");
            }

            LifecycleMapping mapping = lookupLifecycleMapping(p.getPackaging());
            if (mapping != null) {
                Map<String, String> phases = mapping.getPhases("default");
                if (phases != null) {
                    for (String coordinates : phases.values()) {
                        for (String coordinate : coordinates.split(",")) {
                            Plugin plugin = parsePluginCoordinate(coordinate.trim(), managed);
                            if (plugin != null) {
                                collectPlugin(p, plugin, rs, seen, "lifecycle-plugin");
                            }
                        }
                    }
                }
            }

            // clean lifecycle is not in LifecycleMapping for packaging; pin the root version explicitly.
            Plugin clean = new Plugin();
            clean.setGroupId("org.apache.maven.plugins");
            clean.setArtifactId("maven-clean-plugin");
            clean.setVersion(versionFor(p, "maven-clean-plugin", "3.4.1"));
            collectPlugin(p, clean, rs, seen, "clean-plugin");
        }
    }

    private Map<String, Plugin> managedPlugins(MavenProject p) {
        Map<String, Plugin> result = new HashMap<>();
        if (p.getPluginManagement() != null) {
            for (Plugin plugin : p.getPluginManagement().getPlugins()) {
                result.put(plugin.getKey(), plugin);
            }
        }
        return result;
    }

    private Plugin resolvePluginVersion(Plugin plugin, Map<String, Plugin> managed) {
        Plugin copy = plugin.clone();
        if (isBlank(copy.getVersion())) {
            Plugin managedPlugin = managed.get(copy.getKey());
            if (managedPlugin != null && !isBlank(managedPlugin.getVersion())) {
                copy.setVersion(managedPlugin.getVersion());
            }
        }
        if (isBlank(copy.getVersion())) {
            throw new IllegalStateException("Plugin has no version: " + copy.getKey());
        }
        return copy;
    }

    private Plugin parsePluginCoordinate(String coordinate, Map<String, Plugin> managed) {
        String[] parts = coordinate.split(":");
        if (parts.length < 4) {
            warnings.add("Ignoring malformed lifecycle plugin coordinate: " + coordinate);
            return null;
        }
        Plugin plugin = new Plugin();
        plugin.setGroupId(parts[0]);
        plugin.setArtifactId(parts[1]);
        plugin.setVersion(parts[2]);
        Plugin managedPlugin = managed.get(plugin.getKey());
        if (managedPlugin != null && !isBlank(managedPlugin.getVersion())) {
            plugin.setVersion(managedPlugin.getVersion());
        }
        return plugin;
    }

    private String versionFor(MavenProject p, String artifactId, String fallback) {
        if (p.getPluginManagement() != null) {
            for (Plugin plugin : p.getPluginManagement().getPlugins()) {
                if (artifactId.equals(plugin.getArtifactId()) && !isBlank(plugin.getVersion())) {
                    return plugin.getVersion();
                }
            }
        }
        for (Plugin plugin : p.getBuildPlugins()) {
            if (artifactId.equals(plugin.getArtifactId()) && !isBlank(plugin.getVersion())) {
                return plugin.getVersion();
            }
        }
        return fallback;
    }

    private LifecycleMapping lookupLifecycleMapping(String packaging) {
        try {
            Object component = session.getContainer()
                    .lookup(LifecycleMapping.class.getName(), packaging);
            return (LifecycleMapping) component;
        } catch (Exception e) {
            warnings.add("No LifecycleMapping for packaging " + packaging + ": " + e.getMessage());
            return null;
        }
    }

    private void collectPlugin(MavenProject project, Plugin plugin, RepositorySystemSession rs,
                               Set<String> seen, String source) throws Exception {
        // The offline-package plugin itself is a build tool, not a product dependency.
        if ("org.robotslacker.tools".equals(plugin.getGroupId())
                && "offline-package-maven-plugin".equals(plugin.getArtifactId())) {
            return;
        }
        String key = plugin.getKey() + ":" + plugin.getVersion();
        if (!seen.add(key)) {
            return;
        }
        // Resolve the plugin artifact and its POM dependency closure. PluginDescriptor
        // alone may not resolve the plugin realm/dependencies until setupPluginRealm().
        collectWithTransitives(
                plugin.getGroupId() + ":" + plugin.getArtifactId() + ":" + plugin.getVersion(),
                "plugin-runtime", rs);
        PluginDescriptor descriptor;
        try {
            descriptor = pluginManager.getPluginDescriptor(
                    plugin, project.getRemotePluginRepositories(), rs);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve plugin " + key, e);
        }
        add(descriptor.getPluginArtifact(), source);
        if (descriptor.getArtifacts() != null) {
            for (Artifact artifact : descriptor.getArtifacts()) {
                add(artifact, "plugin-dependency");
            }
        }
        // Plugin-level <dependencies> in the POM (e.g. asciidoctor's JRuby/PDF deps)
        // are not guaranteed to be present in PluginDescriptor.getArtifacts().
        if (plugin.getDependencies() != null) {
            for (org.apache.maven.model.Dependency dependency : plugin.getDependencies()) {
                if (isBlank(dependency.getVersion())) {
                    throw new IllegalStateException("Plugin dependency has no version: "
                            + plugin.getKey() + " -> " + dependency);
                }
                collectWithTransitives(
                        dependency.getGroupId() + ":" + dependency.getArtifactId()
                                + ":" + dependency.getVersion(),
                        "plugin-declared-dependency", rs);
            }
        }
    }

    private void collectSurefireProvider(RepositorySystemSession rs) throws Exception {
        Set<String> versions = new LinkedHashSet<>();
        for (MavenProject p : session.getProjects()) {
            versions.add(versionFor(p, "maven-surefire-plugin", "3.5.5"));
        }
        for (String version : versions) {
            String gav = "org.apache.maven.surefire:surefire-junit-platform:" + version;
            collectWithTransitives(gav, "surefire-provider", rs);
        }
    }

    private void collectWithTransitives(String gav, String source, RepositorySystemSession rs)
            throws Exception {
        List<RemoteRepository> repositories =
                session.getTopLevelProject().getRemoteProjectRepositories();
        CollectRequest collect = new CollectRequest();
        collect.setRoot(new Dependency(new DefaultArtifact(gav), "runtime"));
        collect.setRepositories(repositories);
        CollectResult collected = repositorySystem.collectDependencies(rs, collect);
        DependencyResult resolved = repositorySystem.resolveDependencies(
                rs, new DependencyRequest(collected.getRoot(), null));
        for (ArtifactResult result : resolved.getArtifactResults()) {
            add(result.getArtifact(), source);
        }
    }

    private void expandPlatformFamilies() throws Exception {
        List<ArtifactRecord> snapshot = new ArrayList<>(artifacts.values());
        Map<String, Family> detected = new LinkedHashMap<>();
        for (ArtifactRecord record : snapshot) {
            if (isOptionalAccelerator(record)) {
                String label = record.gav()
                        + (record.classifier().isEmpty() ? "" : ":" + record.classifier());
                if (!optionalAccelerators.contains(label)) {
                    optionalAccelerators.add(label);
                }
                continue;
            }
            String token = detectToken(record);
            if (token == null) {
                continue;
            }
            if (UNSUPPORTED_TOKENS.contains(token)) {
                throw new IllegalStateException(
                        "Native family uses a platform outside the three targets: "
                                + record.gav() + " (token " + token + ")");
            }
            String familyKey = familyKey(record, token);
            Family family = detected.computeIfAbsent(
                    familyKey,
                    k -> new Family(familyKey, record, token));
            family.members.add(record);
        }

        for (Family family : detected.values()) {
            for (Platform platform : platforms) {
                String targetToken = platform.id();
                boolean found = false;
                for (String alias : TARGET_TOKENS.get(targetToken)) {
                    if (resolveFamilyCandidate(family.base, family.token, alias, platform)) {
                        family.status.put(platform.id(), alias);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    family.status.put(platform.id(), null);
                }
            }
            List<String> missing = family.status.entrySet().stream()
                    .filter(e -> e.getValue() == null)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "Platform family " + family.key + " is missing variants for " + missing
                                + ". This dependency is not usable on all three target platforms.");
            }
            families.put(family.key, family);
        }
    }

    private String familyKey(ArtifactRecord record, String token) {
        String artifact = record.artifactId().replace(token, "{platform}");
        String classifier = record.classifier().replace(token, "{platform}");
        return record.groupId() + ":" + artifact + ":" + classifier;
    }

    /**
     * Netty native transports are optional OS accelerators, not required platform families.
     * Linux UDS needs epoll; Windows falls back to NIO; macOS/kqueue is out of scope.
     * They are still collected into javarepo, but not forced through the 3-platform gate.
     */
    private boolean isOptionalAccelerator(ArtifactRecord record) {
        if (!"io.netty".equals(record.groupId())) {
            return false;
        }
        if (record.artifactId().equals("netty-transport-native-unix-common")) {
            return false;
        }
        return record.artifactId().startsWith("netty-transport-native-")
                || record.artifactId().startsWith("netty-resolver-dns-native-");
    }

    private String detectToken(ArtifactRecord record) {
        String artifactId = record.artifactId();
        boolean nativeName = artifactId.startsWith("native-")
                || artifactId.startsWith("natives-")
                || artifactId.contains("-native");
        String classifier = record.classifier();
        for (String token : ALL_TOKENS) {
            if (nativeName && artifactId.contains(token)) {
                return token;
            }
            if (!classifier.isEmpty() && (classifier.equals(token) || classifier.endsWith(token))) {
                return token;
            }
        }
        return null;
    }

    private boolean resolveFamilyCandidate(ArtifactRecord base, String detectedToken,
                                           String targetToken, Platform platform) {
        String artifactId = base.artifactId().replace(detectedToken, targetToken);
        String classifier = base.classifier().replace(detectedToken, targetToken);
        if (artifactId.equals(base.artifactId()) && classifier.equals(base.classifier())) {
            return true;
        }
        RepositorySystemSession rs = sessionFor(platform);
        try {
            resolveOne(base.groupId(), artifactId, base.version(), classifier,
                    base.extension(), rs, "platform-family");
            resolveOne(base.groupId(), artifactId, base.version(), "",
                    "pom", rs, "platform-family-pom");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ArtifactRecord resolveOne(String groupId, String artifactId, String version,
                                      String classifier, String extension,
                                      RepositorySystemSession rs, String source) throws Exception {
        DefaultArtifact artifact = new DefaultArtifact(
                groupId, artifactId, classifier, extension, version);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(session.getTopLevelProject().getRemoteProjectRepositories());
        ArtifactResult result = repositorySystem.resolveArtifact(rs, request);
        ArtifactRecord record = add(result.getArtifact(), source);
        if (record == null) {
            throw new IllegalStateException("Resolved artifact is not usable: " + artifact);
        }
        return record;
    }

    private ArtifactRecord add(org.eclipse.aether.artifact.Artifact artifact, String source) {
        if (artifact == null || artifact.getFile() == null) {
            return null;
        }
        return add(new ArtifactRecord(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                artifact.getClassifier(), artifact.getExtension(),
                artifact.getFile().toPath(), source));
    }

    private ArtifactRecord add(Artifact artifact, String source) {
        if (artifact == null || artifact.getFile() == null) {
            return null;
        }
        return add(new ArtifactRecord(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                artifact.getClassifier(), artifact.getType(),
                artifact.getFile().toPath(), source));
    }

    private ArtifactRecord add(ArtifactRecord record) {
        if (isProjectGav(record.groupId(), record.artifactId(), record.version())) {
            return null;
        }
        artifacts.putIfAbsent(record.key(), record);
        return artifacts.get(record.key());
    }

    private boolean isProjectGav(String groupId, String artifactId, String version) {
        return projectGavs.contains(gav(groupId, artifactId, version));
    }

    private static String gav(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> allTokens() {
        List<String> all = new ArrayList<>();
        TARGET_TOKENS.values().forEach(all::addAll);
        all.addAll(UNSUPPORTED_TOKENS);
        all.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(all);
    }

    /**
     * Copies the classpath artifacts plus the complete POM descriptor closure.
     *
     * <p>Copying only the winning versions is not enough: Maven reads the descriptor of every
     * candidate node it collects (including the versions dropped by nearest-wins mediation), of
     * every parent POM and of every imported BOM. Online the missing ones are fetched on demand;
     * offline the build only gets a "The POM for ... is missing, no dependency information
     * available" warning that hides an incomplete repository. Walking the closure makes the
     * package self-contained.</p>
     *
     * <p>With {@code offline.candidate.jars} the jars of those losing versions are shipped as
     * well. Maven does not need them for this reactor, but they keep the repository usable when
     * a module POM is edited offline or a different resolution order selects another version.</p>
     */
    private MaterializeResult materialize(Path javarepo) throws Exception {
        Path localRepo = Path.of(session.getLocalRepository().getBasedir());
        RepositorySystemSession rs = session.getRepositorySession();

        Deque<PendingPom> pending = new ArrayDeque<>();
        Set<String> jarsPresent = new HashSet<>();
        for (ArtifactRecord record : artifacts.values()) {
            if (record.extension().equals("pom")) {
                jarsPresent.add(record.key());
            } else {
                Path target = javarepo.resolve(record.repositoryPath());
                Files.createDirectories(target.getParent());
                Files.copy(record.file(), target, StandardCopyOption.REPLACE_EXISTING);
                jarsPresent.add(record.key());
            }
            pending.add(new PendingPom(record.gav(), null));
        }
        for (String bom : projectBomGavs()) {
            pending.add(new PendingPom(bom, PomGraph.Kind.IMPORT));
        }

        PomGraph graph = new PomGraph((groupId, artifactId, version) ->
                ensurePom(groupId, artifactId, version, localRepo, rs));
        Set<String> visited = new HashSet<>();
        List<String> unavailable = new ArrayList<>();
        int poms = 0;
        int candidateJarsCopied = 0;
        int candidateJarsAttempted = 0;
        while (!pending.isEmpty()) {
            PendingPom next = pending.removeFirst();
            String gav = next.gav();
            String[] parts = gav.split(":", 3);
            // A GAV can be reached both as a POM reference and as a dependency, so the jar is
            // decided per queued reference, not per visited POM.
            if (candidateJars && next.expectsJar() && parts.length == 3) {
                String key = parts[0] + ":" + parts[1] + ":" + parts[2] + "::jar";
                if (jarsPresent.add(key)) {
                    candidateJarsAttempted++;
                    if (copyCandidateJar(parts[0], parts[1], parts[2], javarepo, rs)) {
                        candidateJarsCopied++;
                    }
                }
            }
            if (!visited.add(gav)) {
                continue;
            }
            if (parts.length != 3) {
                unavailable.add("malformed coordinates: " + gav);
                continue;
            }
            Path pom;
            try {
                pom = ensurePom(parts[0], parts[1], parts[2], localRepo, rs);
            } catch (Exception e) {
                unavailable.add(gav + " (" + e.getMessage() + ")");
                continue;
            }
            copyPomFile(pom, javarepo, localRepo);
            poms++;
            try {
                for (PomGraph.Reference reference : graph.references(parts[0], parts[1], parts[2])) {
                    pending.add(new PendingPom(reference.gav(), reference.kind()));
                }
            } catch (PomGraph.MissingPomException e) {
                unavailable.add(e.gav() + " (needed by " + gav + ")");
            }
        }
        if (!graph.notes().isEmpty()) {
            log.info("POM closure: " + graph.notes().size()
                    + " profile-only dependency reference(s) skipped (Maven activates POM"
                    + " profiles itself, so they are not gaps)");
        }
        log.info("Maven repository: " + artifacts.size() + " artifacts, " + poms
                + " POMs (descriptor closure)");
        if (candidateJars) {
            log.info("Candidate jars: " + candidateJarsCopied + "/" + candidateJarsAttempted
                    + " losing dependency version(s) packaged for alternative resolution paths");
        }

        // A gap here is exactly what produces "The POM for ... is missing, no dependency
        // information available" in an offline build, so never write an incomplete package.
        List<String> gaps = new ArrayList<>(graph.problems());
        gaps.addAll(unavailable);
        if (!gaps.isEmpty()) {
            throw new IllegalStateException("The offline Maven repository would be incomplete: "
                    + gaps.size() + " POM reference(s) cannot be packaged, so an offline build"
                    + " would report \"The POM for ... is missing, no dependency information"
                    + " available\". Resolve the dependency first:\n  - "
                    + String.join("\n  - ", gaps));
        }
        return new MaterializeResult(poms, candidateJarsCopied, candidateJarsAttempted);
    }

    /**
     * Copies the jar of a dependency version that lost mediation. Such a jar is not needed by
     * this reactor (Maven never resolves it), so an unavailable jar is not a packaging gap; it is
     * only best-effort insurance. POM-only artifacts (BOMs, parent POMs) simply fail the lookup.
     */
    private boolean copyCandidateJar(String groupId, String artifactId, String version,
                                     Path javarepo, RepositorySystemSession rs) {
        DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, "", "jar", version);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(session.getTopLevelProject().getRemoteProjectRepositories());
        try {
            ArtifactResult result = repositorySystem.resolveArtifact(rs, request);
            if (result.getArtifact() == null || result.getArtifact().getFile() == null) {
                return false;
            }
            Path file = result.getArtifact().getFile().toPath();
            if (!Files.isRegularFile(file)) {
                return false;
            }
            ArtifactRecord record = new ArtifactRecord(
                    groupId, artifactId, version, "", "jar", file, "candidate-jar");
            Path target = javarepo.resolve(record.repositoryPath());
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** A POM waiting to be packaged; {@code kind} is null for the reactor's own artifacts. */
    private record PendingPom(String gav, PomGraph.Kind kind) {
        boolean expectsJar() {
            return kind == PomGraph.Kind.DEPENDENCY || kind == PomGraph.Kind.PROFILE_DEPENDENCY;
        }
    }

    private record MaterializeResult(int poms, int candidateJars, int candidateJarCandidates) {
    }


    /**
     * Effective models do not expose imported BOM coordinates. The offline build still needs
     * those POMs to build the root model, so seed them from the reactor POMs (interpolated with
     * the effective project properties) without copying the project POMs themselves.
     */
    private List<String> projectBomGavs() throws Exception {
        List<String> result = new ArrayList<>();
        for (MavenProject p : session.getProjects()) {
            if (p.getFile() == null || !Files.isRegularFile(p.getFile().toPath())) {
                continue;
            }
            Model model;
            try (InputStream in = Files.newInputStream(p.getFile().toPath())) {
                model = new MavenXpp3Reader().read(in, false);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot parse project POM " + p.getFile(), e);
            }
            if (model.getDependencyManagement() == null) {
                continue;
            }
            Map<String, String> properties = new LinkedHashMap<>(PomGraph.propertyMap(p.getProperties()));
            properties.put("project.groupId", p.getGroupId());
            properties.put("project.artifactId", p.getArtifactId());
            properties.put("project.version", p.getVersion());
            properties.put("pom.groupId", p.getGroupId());
            properties.put("pom.artifactId", p.getArtifactId());
            properties.put("pom.version", p.getVersion());
            for (org.apache.maven.model.Dependency dependency
                    : model.getDependencyManagement().getDependencies()) {
                if (!PomGraph.isImport(dependency)) {
                    continue;
                }
                result.add(PomGraph.interpolateRequired(dependency.getGroupId(), properties) + ":"
                        + PomGraph.interpolateRequired(dependency.getArtifactId(), properties) + ":"
                        + PomGraph.interpolateRequired(dependency.getVersion(), properties));
            }
        }
        return result;
    }

    private static void copyPomFile(Path pom, Path javarepo, Path localRepo) throws IOException {
        Path relative = localRepo.relativize(pom);
        Path target = javarepo.resolve(relative.toString().replace('\\', '/'));
        Files.createDirectories(target.getParent());
        Files.copy(pom, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path ensurePom(String groupId, String artifactId, String version,
                           Path localRepo, RepositorySystemSession rs) throws Exception {
        String gav = groupId + ":" + artifactId + ":" + version;
        String relative = pomPath(groupId, artifactId, version);
        Path path = localRepo.resolve(relative);
        if (Files.isRegularFile(path)) {
            return path;
        }
        if (Files.exists(path)) {
            throw new IllegalStateException("POM path exists but is not a regular file: " + path
                    + " (" + gav + "). Remove it from the local repository and retry.");
        }
        DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(session.getTopLevelProject().getRemoteProjectRepositories());
        ArtifactResult result = repositorySystem.resolveArtifact(rs, request);
        if (result.getArtifact() == null || result.getArtifact().getFile() == null) {
            throw new IllegalStateException("POM resolution returned no file: " + gav);
        }
        Path resolved = result.getArtifact().getFile().toPath();
        if (!Files.isRegularFile(resolved)) {
            // With maven.repo.local.tail.ignoreAvailability (Maven's default) the resolver can
            // "succeed" with a path that does not exist. That happens when the tail repository
            // is not the repository root (for example javarepo.zip unzipped one level too deep)
            // or when the packaged repository really lacks the POM.
            throw new IllegalStateException("POM was resolved to a missing file: " + gav
                    + ". Resolved path: " + resolved
                    + "; local repository: " + localRepo
                    + "; -Dmaven.repo.local.tail=" + tailsDescription()
                    + ". The tail must point at the directory that directly contains io/, org/, ..."
                    + " (unzip javarepo.zip one level above the javarepo/ folder it creates).");
        }
        if (!resolved.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) {
            // maven.repo.local.tail is read-only: the resolver can return a POM from the tail
            // without copying it into the writable main repository. Materialize it there so the
            // POM closure walker sees a normal local-repository path.
            Files.createDirectories(path.getParent());
            Files.copy(resolved, path, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("POM was resolved but is still missing: " + gav
                    + " (resolved: " + resolved + ", expected: " + path + ")");
        }
        return path;
    }

    /** Tail repositories from the CLI/MAVEN_ARGS, for diagnostics only (empty when none is set). */
    private String tailsDescription() {
        String tails = session.getUserProperties().getProperty("maven.repo.local.tail");
        if (isBlank(tails)) {
            tails = session.getSystemProperties().getProperty("maven.repo.local.tail");
        }
        return isBlank(tails) ? "<none>" : tails;
    }

    private static String pomPath(String groupId, String artifactId, String version) {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + ".pom";
    }

    public record Family(String key, ArtifactRecord base, String token,
                         List<ArtifactRecord> members,
                         Map<String, String> status) {
        private Family(String key, ArtifactRecord base, String token) {
            this(key, base, token, new ArrayList<>(), new LinkedHashMap<>());
        }
    }

    public record MavenCollectionResult(int artifactCount,
                                        int pomCount,
                                        int candidateJarCount,
                                        int candidateJarCandidateCount,
                                        Map<String, Family> families,
                                        List<String> warnings,
                                        List<String> optionalAccelerators) {
    }

}

