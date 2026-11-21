package org.robotslacker.tools.offline;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Collects a clean Maven repository and an npm cache for offline development.
 */
@Mojo(name = "collect", aggregator = true, requiresProject = true)
public class CollectMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySystemSession;

    @Parameter(property = "offline.dir",
            defaultValue = "${project.basedir}/offline", required = true)
    private File outputDirectory;

    @Parameter(property = "offline.clean", defaultValue = "false")
    private boolean clean;

    @Parameter(property = "offline.npm.skip", defaultValue = "false")
    private boolean skipNpm;

    @Parameter(property = "offline.npm.tarballs", defaultValue = "true")
    private boolean npmTarballs;

    @Parameter(property = "offline.npm.executable", defaultValue = "")
    private String npmExecutable;

    /**
     * Extra local npm caches to consult before falling back to the network. Each entry may be an
     * npm cache root (containing {@code _cacache/}), an npmrepo produced by this plugin, or a
     * directory holding tarballs (either directly or under {@code tarballs/}).
     */
    @Parameter(property = "offline.npm.cache.dirs", defaultValue = "")
    private String npmCacheDirs;

    /** Also consult npm's own machine-wide cache (the directory printed by `npm config get cache`). */
    @Parameter(property = "offline.npm.cache.global", defaultValue = "true")
    private boolean npmGlobalCache;

    /**
     * Keep an empty npm repository instead of failing. The npm side can only collect what a
     * {@code package-lock.json} describes; when no module has one, the package would advertise a
     * successful build with zero frontend dependencies. A pure Java reactor has no npm
     * dependencies at all and can set this to {@code true}.
     */
    @Parameter(property = "offline.npm.allow.empty", defaultValue = "false")
    private boolean npmAllowEmpty;

    @Parameter(property = "offline.zip", defaultValue = "true")
    private boolean zip;

    /**
     * Also package the jars of dependency versions that lost Maven's nearest-wins mediation.
     * Maven reads their POMs while collecting and never resolves their jars, so they are not
     * required for this reactor; shipping them keeps the repository usable when a module POM is
     * edited offline or a different resolution order selects another version.
     */
    @Parameter(property = "offline.candidate.jars", defaultValue = "true")
    private boolean candidateJars;

    @Parameter(property = "offline.platforms",
            defaultValue = "windows-x86_64,linux-amd64,linux-arm64")
    private String platformIds;

    @Component
    private ProjectDependenciesResolver dependenciesResolver;

    @Component
    private MavenPluginManager pluginManager;

    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        MavenProject top = session.getTopLevelProject();
        if (top != null && project.getBasedir() != null
                && !project.getBasedir().equals(top.getBasedir())) {
            getLog().debug("Skipping offline-package collect on non-root module " + project.getArtifactId());
            return;
        }

        List<Platform> platforms = parsePlatforms();
        List<Path> npmCacheDirectories = skipNpm || !npmTarballs
                ? List.of() : parseNpmCacheDirs();
        for (Path directory : npmCacheDirectories) {
            if (!Files.isDirectory(directory)) {
                getLog().warn("npm cache directory does not exist: " + directory);
            }
        }
        Path output = outputDirectory.toPath().toAbsolutePath().normalize();
        if (Files.exists(output) && !clean) {
            throw new MojoFailureException("Offline directory already exists: " + output
                    + ". Use -Doffline.clean=true to replace it.");
        }

        Path parent = output.getParent() == null ? output : output.getParent();
        String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path staging = parent.resolve("." + output.getFileName() + ".staging-" + suffix);
        try {
            deleteRecursively(staging);
            Files.createDirectories(staging);
            Files.createDirectories(staging.resolve("javarepo"));
            Files.createDirectories(staging.resolve("npmrepo"));

            OfflineLog log = new OfflineLog() {
                @Override
                public void info(String message) {
                    getLog().info(message);
                }

                @Override
                public void warn(String message) {
                    getLog().warn(message);
                }
            };

            MavenCollector maven = new MavenCollector(session, repositorySystem,
                    dependenciesResolver, pluginManager, log, platforms, candidateJars);
            MavenCollector.MavenCollectionResult mavenResult =
                    maven.collect(staging.resolve("javarepo"));

            NpmCollector.NpmSummary npmResult = null;
            if (!skipNpm) {
                // The previous package in the same output directory is a good cache source
                // even when NPM_CONFIG_CACHE is not set; it is replaced only after staging
                // succeeds.
                List<Path> npmSeeds = new ArrayList<>();
                Path previousNpmRepo = output.resolve("npmrepo");
                if (Files.isDirectory(previousNpmRepo)) {
                    npmSeeds.add(previousNpmRepo);
                }
                NpmCollector npm = new NpmCollector(log, npmExecutable, npmSeeds,
                        npmCacheDirectories, npmGlobalCache, !npmAllowEmpty);
                npmResult = npm.collect(staging.resolve("npmrepo"),
                        session.getProjects(), platforms, npmTarballs);
            }

            if (zip) {
                getLog().info("Packing javarepo.zip ...");
                new ZipPackager().zip(staging.resolve("javarepo"),
                        staging.resolve("javarepo.zip"));
                if (!skipNpm) {
                    getLog().info("Packing npmrepo.zip ...");
                    new ZipPackager().zip(staging.resolve("npmrepo"),
                            staging.resolve("npmrepo.zip"));
                }
            }

            String mavenVersion = session.getSystemProperties()
                    .getProperty("maven.version", "unknown");
            String javaVersion = System.getProperty("java.version", "unknown");
            new ManifestWriter().write(staging, mavenResult, npmResult, platforms,
                    mavenVersion, javaVersion);

            if (zip) {
                // The zips are the transport artifact; do not leave thousands of small files behind.
                deleteRecursively(staging.resolve("javarepo"));
                deleteRecursively(staging.resolve("npmrepo"));
            }

            if (Files.exists(output)) {
                deleteRecursively(output);
            }
            Files.createDirectories(output);
            try (var children = Files.list(staging)) {
                for (Path child : children.toList()) {
                    Files.move(child, output.resolve(child.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            deleteRecursively(staging);
            getLog().info("Offline package written to " + output);
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Offline package collection failed. Staging directory: " + staging, e);
        }
    }

    private List<Platform> parsePlatforms() throws MojoFailureException {
        List<Platform> result = new ArrayList<>();
        for (String raw : platformIds.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            try {
                result.add(Platform.byId(id));
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("Unsupported offline platform: " + id);
            }
        }
        if (result.isEmpty()) {
            throw new MojoFailureException("No offline platform configured");
        }
        return result;
    }

    private List<Path> parseNpmCacheDirs() throws MojoFailureException {
        List<Path> result = new ArrayList<>();
        if (npmCacheDirs == null || npmCacheDirs.isBlank()) {
            return result;
        }
        for (String raw : npmCacheDirs.split("[,;]")) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                result.add(Path.of(value));
            } catch (RuntimeException invalid) {
                throw new MojoFailureException(
                        "Invalid offline.npm.cache.dirs entry: " + value);
            }
        }
        return result;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
