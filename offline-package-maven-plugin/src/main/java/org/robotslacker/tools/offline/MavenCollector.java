package org.robotslacker.tools.offline;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.mapping.LifecycleMapping;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
                          List<Platform> platforms) {
        this.session = session;
        this.repositorySystem = repositorySystem;
        this.dependenciesResolver = dependenciesResolver;
        this.pluginManager = pluginManager;
        this.log = log;
        this.platforms = platforms;
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
        materialize(javarepo);
        return new MavenCollectionResult(artifacts.size(), families, warnings,
                optionalAccelerators);
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

    private void materialize(Path javarepo) throws Exception {
        Path localRepo = Path.of(session.getLocalRepository().getBasedir());
        Set<String> copiedPoms = new HashSet<>();
        copyProjectBoms(javarepo, localRepo, copiedPoms);
        for (ArtifactRecord record : artifacts.values()) {
            if (record.extension().equals("pom")) {
                continue;
            }
            Path target = javarepo.resolve(record.repositoryPath());
            Files.createDirectories(target.getParent());
            Files.copy(record.file(), target, StandardCopyOption.REPLACE_EXISTING);

            Path pom = localRepo.resolve(pomPath(record));
            if (!Files.isRegularFile(pom)) {
                try {
                    pom = ensurePom(record.groupId(), record.artifactId(), record.version(),
                            localRepo, session.getRepositorySession());
                } catch (Exception e) {
                    warnings.add("POM missing for " + record.gav() + ": " + e.getMessage());
                }
            }
            if (Files.isRegularFile(pom)) {
                copyPom(pom, javarepo, localRepo, copiedPoms, session.getRepositorySession());
            }
        }
    }

    /**
     * Effective models do not expose imported BOM coordinates. The offline build still
     * needs those POMs to build the root model, so copy every import (and its parent chain)
     * from the reactor POMs without copying the project POMs themselves.
     */
    private void copyProjectBoms(Path javarepo, Path localRepo, Set<String> visited)
            throws Exception {
        for (MavenProject p : session.getProjects()) {
            if (p.getFile() == null || !Files.isRegularFile(p.getFile().toPath())) {
                continue;
            }
            Model model;
            try (InputStream in = Files.newInputStream(p.getFile().toPath())) {
                model = new MavenXpp3Reader().read(in);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot parse project POM " + p.getFile(), e);
            }
            if (model.getDependencyManagement() == null) {
                continue;
            }
            for (org.apache.maven.model.Dependency dependency
                    : model.getDependencyManagement().getDependencies()) {
                if (!"pom".equals(dependency.getType())
                        || !"import".equals(dependency.getScope())) {
                    continue;
                }
                java.util.Map<String, String> projectProperties = propertyMap(p.getProperties());
                String groupId = interpolate(dependency.getGroupId(), projectProperties);
                String artifactId = interpolate(dependency.getArtifactId(), projectProperties);
                String version = interpolate(dependency.getVersion(), projectProperties);
                Path imported = ensurePom(groupId, artifactId, version, localRepo,
                        session.getRepositorySession());
                copyPom(imported, javarepo, localRepo, visited, session.getRepositorySession());
            }
        }
    }

    private void copyPom(Path pom, Path javarepo, Path localRepo, Set<String> visited,
                         RepositorySystemSession rs) throws Exception {
        if (!visited.add(pom.toString())) {
            return;
        }
        Path relative = localRepo.relativize(pom);
        Path target = javarepo.resolve(relative.toString().replace('\\', '/'));
        Files.createDirectories(target.getParent());
        Files.copy(pom, target, StandardCopyOption.REPLACE_EXISTING);

        Model model;
        try (InputStream in = Files.newInputStream(pom)) {
            model = new MavenXpp3Reader().read(in);
        } catch (IOException | XmlPullParserException e) {
            warnings.add("Cannot parse POM " + pom + ": " + e.getMessage());
            return;
        }
        Map<String, String> properties = modelProperties(
                model, localRepo, rs, new HashSet<>());
        Parent parent = model.getParent();
        if (parent != null) {
            Map<String, String> raw = ownProperties(model);
            Path parentPom = ensurePom(
                    interpolate(parent.getGroupId(), raw),
                    interpolate(parent.getArtifactId(), raw),
                    interpolate(parent.getVersion(), raw), localRepo, rs);
            copyPom(parentPom, javarepo, localRepo, visited, rs);
        }
        if (model.getDependencyManagement() != null) {
            for (org.apache.maven.model.Dependency dependency
                    : model.getDependencyManagement().getDependencies()) {
                if ("pom".equals(dependency.getType()) && "import".equals(dependency.getScope())) {
                    String groupId = interpolate(dependency.getGroupId(), properties);
                    String artifactId = interpolate(dependency.getArtifactId(), properties);
                    String version = interpolate(dependency.getVersion(), properties);
                    Path imported = ensurePom(groupId, artifactId, version, localRepo, rs);
                    copyPom(imported, javarepo, localRepo, visited, rs);
                }
            }
        }
    }

    /** Merge parent properties into the model's own properties. */
    private Map<String, String> modelProperties(Model model, Path localRepo,
                                                RepositorySystemSession rs,
                                                Set<String> visiting) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Parent parent = model.getParent();
        if (parent != null) {
            Map<String, String> raw = ownProperties(model);
            String groupId = interpolate(parent.getGroupId(), raw);
            String artifactId = interpolate(parent.getArtifactId(), raw);
            String version = interpolate(parent.getVersion(), raw);
            String key = groupId + ":" + artifactId + ":" + version;
            if (visiting.add(key)) {
                Path parentPom = ensurePom(groupId, artifactId, version, localRepo, rs);
                Model parentModel;
                try (InputStream in = Files.newInputStream(parentPom)) {
                    parentModel = new MavenXpp3Reader().read(in);
                } catch (IOException | XmlPullParserException e) {
                    throw new IllegalStateException("Cannot parse parent POM " + parentPom, e);
                }
                result.putAll(modelProperties(parentModel, localRepo, rs, visiting));
            }
        }
        result.putAll(ownProperties(model));
        return result;
    }

    private static Map<String, String> ownProperties(Model model) {
        Map<String, String> result = propertyMap(model.getProperties());
        if (model.getVersion() != null) {
            result.putIfAbsent("project.version", model.getVersion());
            result.putIfAbsent("pom.version", model.getVersion());
        }
        if (model.getGroupId() != null) {
            result.putIfAbsent("project.groupId", model.getGroupId());
            result.putIfAbsent("pom.groupId", model.getGroupId());
        }
        if (model.getArtifactId() != null) {
            result.putIfAbsent("project.artifactId", model.getArtifactId());
            result.putIfAbsent("pom.artifactId", model.getArtifactId());
        }
        return result;
    }

    private static Map<String, String> propertyMap(java.util.Properties properties) {
        Map<String, String> result = new LinkedHashMap<>();
        if (properties != null) {
            properties.stringPropertyNames()
                    .forEach(name -> result.put(name, properties.getProperty(name)));
        }
        return result;
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
            // without copying it into the writable main repository. Materialize it there so
            // copyPom() can walk a normal local-repository path.
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

    private static String interpolate(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (int i = 0; i < 10 && result.contains("${"); i++) {
            int start = result.indexOf("${");
            int end = result.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String key = result.substring(start + 2, end);
            String replacement = properties == null ? null : properties.get(key);
            if (replacement == null) {
                throw new IllegalStateException("Cannot resolve POM property ${" + key
                        + "} in " + value);
            }
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private static String pomPath(ArtifactRecord record) {
        return pomPath(record.groupId(), record.artifactId(), record.version());
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
                                        Map<String, Family> families,
                                        List<String> warnings,
                                        List<String> optionalAccelerators) {
    }

}
