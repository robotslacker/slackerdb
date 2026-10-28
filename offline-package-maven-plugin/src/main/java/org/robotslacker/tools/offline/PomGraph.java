package org.robotslacker.tools.offline;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Relocation;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The POM reference graph of a Maven repository.
 *
 * <p>Maven does not only read the POM of the artifacts that end up on the classpath. During
 * dependency collection it reads the descriptor of every candidate node, including the ones that
 * lose Maven's "nearest definition" mediation, and every {@code dependencyManagement} import and
 * parent POM of every POM it touches. In an online build a missing descriptor is silently fetched
 * from a remote repository (or reported as a warning and ignored); offline there is no second
 * chance, so a repository that only contains the winning artifacts is incomplete.</p>
 *
 * <p>This class walks the full reference closure (parent, imported BOMs, relocations, and
 * compile/runtime dependencies) so the collector can copy every POM an offline build may read and
 * the verifier can prove that none is missing.</p>
 */
final class PomGraph {

    /** Resolves a POM by coordinates. Implementations throw when the POM cannot be obtained. */
    interface Store {
        Path pom(String groupId, String artifactId, String version) throws Exception;
    }

    /** A POM that is referenced but cannot be read: the offline repository would be incomplete. */
    static final class MissingPomException extends Exception {
        private final String gav;

        MissingPomException(String gav, Throwable cause) {
            super("POM is not available: " + gav
                    + (cause == null || cause.getMessage() == null ? "" : " (" + cause.getMessage() + ")"), cause);
            this.gav = gav;
        }

        String gav() {
            return gav;
        }
    }

    enum Kind {
        PARENT,
        IMPORT,
        RELOCATION,
        DEPENDENCY,
        PROFILE_DEPENDENCY
    }

    /** One POM referenced by another POM. */
    record Reference(Kind kind, String groupId, String artifactId, String version, String origin) {
        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        @Override
        public String toString() {
            return gav() + " (" + kind.name().toLowerCase(Locale.ROOT) + " of " + origin + ")";
        }
    }

    private final Store store;
    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, Map<String, String>> propertyCache = new HashMap<>();
    private final Map<String, Map<String, String>> managementCache = new HashMap<>();
    private final Map<String, List<Reference>> referenceCache = new HashMap<>();
    private final Set<String> problems = new LinkedHashSet<>();
    private final Set<String> notes = new LinkedHashSet<>();
    private final Set<String> resolvingProperties = new HashSet<>();
    private final Set<String> resolvingManagement = new HashSet<>();

    PomGraph(Store store) {
        this.store = store;
    }

    /** References that could not be interpreted (unresolvable property, broken import, ...). */
    List<String> problems() {
        return List.copyOf(problems);
    }

    /** Non-blocking observations: dependencies of POM profiles that Maven may never activate. */
    List<String> notes() {
        return List.copyOf(notes);
    }

    Model model(String groupId, String artifactId, String version) throws MissingPomException {
        String gav = groupId + ":" + artifactId + ":" + version;
        Model cached = models.get(gav);
        if (cached != null) {
            return cached;
        }
        Path pom;
        try {
            pom = store.pom(groupId, artifactId, version);
        } catch (MissingPomException e) {
            throw e;
        } catch (Exception e) {
            throw new MissingPomException(gav, e);
        }
        Model model;
        try (InputStream in = Files.newInputStream(pom)) {
            model = new MavenXpp3Reader().read(in, false);
        } catch (Exception e) {
            throw new MissingPomException(gav, e);
        }
        models.put(gav, model);
        return model;
    }

    /** Every POM this POM can read: parent chain, imported BOMs, relocation and transitive deps. */
    List<Reference> references(String groupId, String artifactId, String version)
            throws MissingPomException {
        String origin = groupId + ":" + artifactId + ":" + version;
        List<Reference> cached = referenceCache.get(origin);
        if (cached != null) {
            return cached;
        }
        Model model = model(groupId, artifactId, version);
        List<Reference> references = new ArrayList<>();

        String[] parent = parentCoordinates(model);
        if (parent != null) {
            references.add(new Reference(Kind.PARENT, parent[0], parent[1], parent[2], origin));
        }

        Map<String, String> properties = properties(groupId, artifactId, version);
        Map<String, String> management = management(groupId, artifactId, version);

        List<Dependency> managed = model.getDependencyManagement() == null
                ? List.of()
                : model.getDependencyManagement().getDependencies();
        for (Dependency dependency : managed) {
            if (!isImport(dependency)) {
                continue;
            }
            String[] coordinates = coordinates(dependency, properties, management, origin, Kind.IMPORT);
            if (coordinates == null) {
                continue;
            }
            references.add(new Reference(Kind.IMPORT,
                    coordinates[0], coordinates[1], coordinates[2], origin));
        }

        Relocation relocation = model.getDistributionManagement() == null
                ? null
                : model.getDistributionManagement().getRelocation();
        if (relocation != null) {
            String relocatedGroupId = firstNonNull(interpolate(relocation.getGroupId(), properties), groupId);
            String relocatedArtifactId = firstNonNull(
                    interpolate(relocation.getArtifactId(), properties), artifactId);
            String relocatedVersion = firstNonNull(interpolate(relocation.getVersion(), properties), version);
            references.add(new Reference(Kind.RELOCATION,
                    relocatedGroupId, relocatedArtifactId, relocatedVersion, origin));
        }

        addDependencies(references, model.getDependencies(), properties, management,
                origin, Kind.DEPENDENCY);
        for (Profile profile : model.getProfiles()) {
            Map<String, String> profileProperties = new LinkedHashMap<>(properties);
            profileProperties.putAll(propertyMap(profile.getProperties()));
            Map<String, String> profileManagement = new LinkedHashMap<>(management);
            if (profile.getDependencyManagement() != null) {
                for (Dependency dependency : profile.getDependencyManagement().getDependencies()) {
                    if (isImport(dependency) || dependency.getVersion() == null) {
                        continue;
                    }
                    String managedGroupId = interpolate(dependency.getGroupId(), profileProperties);
                    String managedArtifactId = interpolate(dependency.getArtifactId(), profileProperties);
                    String managedVersion = interpolate(dependency.getVersion(), profileProperties);
                    if (managedGroupId != null && managedArtifactId != null && managedVersion != null) {
                        profileManagement.put(managementKey(managedGroupId, managedArtifactId,
                                typeOf(dependency), classifierOf(dependency)), managedVersion);
                    }
                }
            }
            addDependencies(references, profile.getDependencies(), profileProperties, profileManagement,
                    origin + " profile[" + profile.getId() + "]", Kind.PROFILE_DEPENDENCY);
        }

        List<Reference> result = List.copyOf(references);
        referenceCache.put(origin, result);
        return result;
    }

    /**
     * Maven reads descriptors for the transitive scopes only: a dependency's test/provided deps
     * and its optional deps never enter the collector's graph, so their POMs are not required.
     * Profile dependencies are included because they may be activated by OS/JDK properties.
     */
    private void addDependencies(List<Reference> references, List<Dependency> dependencies,
                                 Map<String, String> properties, Map<String, String> management,
                                 String origin, Kind kind) {
        if (dependencies == null) {
            return;
        }
        for (Dependency dependency : dependencies) {
            String scope = scopeOf(dependency);
            if (!"compile".equals(scope) && !"runtime".equals(scope)) {
                continue;
            }
            if (isOptional(dependency)) {
                continue;
            }
            String[] coordinates = coordinates(dependency, properties, management, origin, kind);
            if (coordinates == null) {
                continue;
            }
            references.add(new Reference(kind,
                    coordinates[0], coordinates[1], coordinates[2], origin));
        }
    }

    /** Resolves a dependency's coordinates, falling back to dependency management for the version. */
    private String[] coordinates(Dependency dependency, Map<String, String> properties,
                                 Map<String, String> management, String origin, Kind kind) {
        String groupId = interpolate(dependency.getGroupId(), properties);
        String artifactId = interpolate(dependency.getArtifactId(), properties);
        String version = dependency.getVersion() == null
                ? null
                : interpolate(dependency.getVersion(), properties);
        if (version == null) {
            version = managedVersion(dependency, management, properties);
        }
        if (groupId == null || artifactId == null || version == null) {
            String message = "Cannot determine coordinates of "
                    + nullToRaw(groupId, dependency.getGroupId()) + ":"
                    + nullToRaw(artifactId, dependency.getArtifactId())
                    + " declared in " + origin + " (version " + dependency.getVersion()
                    + ", scope " + scopeOf(dependency) + ")";
            if (kind == Kind.PROFILE_DEPENDENCY) {
                // POM profiles are activated by Maven itself (OS, JDK, user properties); a
                // dependency of an inactive profile is never collected, so this cannot break
                // an offline build and stays a note instead of a gap.
                notes.add(message + "; skipped (profile only)");
            } else {
                problems.add(message + "; its POM cannot be copied and may be missing offline");
            }
            return null;
        }
        return new String[]{groupId, artifactId, version};
    }

    private String managedVersion(Dependency dependency, Map<String, String> management,
                                  Map<String, String> properties) {
        String groupId = interpolate(dependency.getGroupId(), properties);
        String artifactId = interpolate(dependency.getArtifactId(), properties);
        if (groupId == null || artifactId == null) {
            return null;
        }
        String type = typeOf(dependency);
        String classifier = classifierOf(dependency);
        String[] keys = {
                managementKey(groupId, artifactId, type, classifier),
                managementKey(groupId, artifactId, type, ""),
                managementKey(groupId, artifactId, "jar", classifier),
                managementKey(groupId, artifactId, "jar", "")
        };
        for (String key : keys) {
            String version = management.get(key);
            if (version != null) {
                return interpolate(version, properties);
            }
        }
        return null;
    }

    /**
     * Effective dependency management of one POM: inherited management, then imported BOMs
     * (first import wins), then the POM's own entries.
     */
    Map<String, String> management(String groupId, String artifactId, String version)
            throws MissingPomException {
        String gav = groupId + ":" + artifactId + ":" + version;
        Map<String, String> cached = managementCache.get(gav);
        if (cached != null) {
            return cached;
        }
        if (!resolvingManagement.add(gav)) {
            return Map.of();
        }
        try {
            Model model = model(groupId, artifactId, version);
            Map<String, String> result = new LinkedHashMap<>();
            String[] parent = parentCoordinates(model);
            if (parent != null) {
                result.putAll(management(parent[0], parent[1], parent[2]));
            }
            Map<String, String> properties = properties(groupId, artifactId, version);
            DependencyManagement dependencyManagement = model.getDependencyManagement();
            if (dependencyManagement != null) {
                Map<String, String> imported = new LinkedHashMap<>();
                for (Dependency dependency : dependencyManagement.getDependencies()) {
                    if (!isImport(dependency)) {
                        continue;
                    }
                    String importGroupId = interpolate(dependency.getGroupId(), properties);
                    String importArtifactId = interpolate(dependency.getArtifactId(), properties);
                    String importVersion = interpolate(dependency.getVersion(), properties);
                    if (importGroupId == null || importArtifactId == null || importVersion == null) {
                        problems.add("Cannot resolve imported BOM "
                                + dependency.getGroupId() + ":" + dependency.getArtifactId()
                                + ":" + dependency.getVersion() + " of " + gav);
                        continue;
                    }
                    try {
                        management(importGroupId, importArtifactId, importVersion)
                                .forEach(imported::putIfAbsent);
                    } catch (MissingPomException e) {
                        problems.add("Imported BOM is missing: " + e.getMessage() + " (imported by " + gav + ")");
                    }
                }
                result.putAll(imported);
                for (Dependency dependency : dependencyManagement.getDependencies()) {
                    if (isImport(dependency) || dependency.getVersion() == null) {
                        continue;
                    }
                    String managedGroupId = interpolate(dependency.getGroupId(), properties);
                    String managedArtifactId = interpolate(dependency.getArtifactId(), properties);
                    String managedVersion = interpolate(dependency.getVersion(), properties);
                    if (managedGroupId == null || managedArtifactId == null || managedVersion == null) {
                        // Managed entries that cannot be interpolated are only a problem if a
                        // dependency actually needs them; coordinates() reports that case.
                        continue;
                    }
                    result.put(managementKey(managedGroupId, managedArtifactId,
                            typeOf(dependency), classifierOf(dependency)), managedVersion);
                }
            }
            managementCache.put(gav, result);
            return result;
        } finally {
            resolvingManagement.remove(gav);
        }
    }

    /** Model properties merged with the parent chain and the Maven built-ins. */
    Map<String, String> properties(String groupId, String artifactId, String version)
            throws MissingPomException {
        String gav = groupId + ":" + artifactId + ":" + version;
        Map<String, String> cached = propertyCache.get(gav);
        if (cached != null) {
            return cached;
        }
        if (!resolvingProperties.add(gav)) {
            return Map.of();
        }
        try {
            Model model = model(groupId, artifactId, version);
            Map<String, String> result = new LinkedHashMap<>();
            String[] parent = parentCoordinates(model);
            if (parent != null) {
                result.putAll(properties(parent[0], parent[1], parent[2]));
            }
            result.putAll(projectProperties(model));
            propertyCache.put(gav, result);
            return result;
        } finally {
            resolvingProperties.remove(gav);
        }
    }

    private String[] parentCoordinates(Model model) {
        Parent parent = model.getParent();
        if (parent == null) {
            return null;
        }
        Map<String, String> own = projectProperties(model);
        String groupId = interpolate(parent.getGroupId(), own);
        String artifactId = interpolate(parent.getArtifactId(), own);
        String version = interpolate(parent.getVersion(), own);
        if (groupId == null || artifactId == null || version == null) {
            problems.add("Cannot resolve parent of " + nullToRaw(model.getArtifactId(), "?")
                    + ": " + parent.getGroupId() + ":" + parent.getArtifactId()
                    + ":" + parent.getVersion());
            return null;
        }
        return new String[]{groupId, artifactId, version};
    }

    static Map<String, String> projectProperties(Model model) {
        Map<String, String> result = new LinkedHashMap<>(propertyMap(model.getProperties()));
        Parent parent = model.getParent();
        String groupId = model.getGroupId() != null ? model.getGroupId()
                : parent == null ? null : parent.getGroupId();
        String version = model.getVersion() != null ? model.getVersion()
                : parent == null ? null : parent.getVersion();
        if (groupId != null) {
            result.put("project.groupId", groupId);
            result.put("pom.groupId", groupId);
            result.put("groupId", groupId);
        }
        if (version != null) {
            result.put("project.version", version);
            result.put("pom.version", version);
            // Legacy alias: old POMs use ${version} for ${project.version}.
            result.put("version", version);
        }
        if (model.getArtifactId() != null) {
            result.put("project.artifactId", model.getArtifactId());
            result.put("pom.artifactId", model.getArtifactId());
            result.put("artifactId", model.getArtifactId());
        }
        if (parent != null) {
            result.put("project.parent.groupId", parent.getGroupId());
            result.put("project.parent.artifactId", parent.getArtifactId());
            result.put("project.parent.version", parent.getVersion());
            result.put("parent.groupId", parent.getGroupId());
            result.put("parent.artifactId", parent.getArtifactId());
            result.put("parent.version", parent.getVersion());
        }
        return result;
    }

    static Map<String, String> propertyMap(Properties properties) {
        Map<String, String> result = new LinkedHashMap<>();
        if (properties != null) {
            properties.stringPropertyNames()
                    .forEach(name -> result.put(name, properties.getProperty(name)));
        }
        return result;
    }

    /** Interpolates {@code ${...}} references; returns null when one of them cannot be resolved. */
    static String interpolate(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (int i = 0; i < 20 && result.contains("${"); i++) {
            int start = result.indexOf("${");
            int end = result.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String key = result.substring(start + 2, end);
            String replacement = properties == null ? null : properties.get(key);
            if (replacement == null) {
                return null;
            }
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result.contains("${") ? null : result;
    }

    /** Interpolates {@code ${...}} references and fails when one of them cannot be resolved. */
    static String interpolateRequired(String value, Map<String, String> properties) {
        String result = interpolate(value, properties);
        if (result == null) {
            throw new IllegalStateException("Cannot resolve POM property in " + value);
        }
        return result;
    }

    static boolean isImport(Dependency dependency) {
        return "pom".equals(typeOf(dependency)) && "import".equals(scopeOf(dependency));
    }

    static String scopeOf(Dependency dependency) {
        return dependency.getScope() == null || dependency.getScope().isBlank()
                ? "compile"
                : dependency.getScope();
    }

    static String typeOf(Dependency dependency) {
        return dependency.getType() == null || dependency.getType().isBlank()
                ? "jar"
                : dependency.getType();
    }

    static String classifierOf(Dependency dependency) {
        return dependency.getClassifier() == null ? "" : dependency.getClassifier();
    }

    private static boolean isOptional(Dependency dependency) {
        String optional = dependency.getOptional();
        return optional != null && Boolean.parseBoolean(optional);
    }

    private static String managementKey(String groupId, String artifactId,
                                        String type, String classifier) {
        return groupId + ":" + artifactId + ":" + type + ":" + classifier;
    }

    private static String firstNonNull(String first, String fallback) {
        return first != null ? first : fallback;
    }

    private static String nullToRaw(String value, String raw) {
        return value != null ? value : String.valueOf(raw);
    }

    static Path pomPath(Path repository, String groupId, String artifactId, String version) {
        return repository.resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
    }

    /** Recovers the coordinates of a POM from its canonical repository path, or null. */
    static String[] gavFromPath(Path repository, Path pom) {
        Path relative;
        try {
            relative = repository.relativize(pom);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int count = relative.getNameCount();
        if (count < 4) {
            return null;
        }
        String version = relative.getName(count - 2).toString();
        String artifactId = relative.getName(count - 3).toString();
        StringBuilder groupId = new StringBuilder();
        for (int i = 0; i < count - 3; i++) {
            if (i > 0) {
                groupId.append('.');
            }
            groupId.append(relative.getName(i));
        }
        String fileName = relative.getFileName().toString();
        if (!fileName.equals(artifactId + "-" + version + ".pom")) {
            return null;
        }
        return new String[]{groupId.toString(), artifactId, version};
    }
}
