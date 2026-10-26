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

    @Parameter(property = "offline.zip", defaultValue = "true")
    private boolean zip;

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
                    dependenciesResolver, pluginManager, log, platforms);
            MavenCollector.MavenCollectionResult mavenResult =
                    maven.collect(staging.resolve("javarepo"));

            NpmCollector.NpmSummary npmResult = null;
            if (!skipNpm) {
                NpmCollector npm = new NpmCollector(log, npmExecutable);
                npmResult = npm.collect(staging.resolve("npmrepo"),
                        session.getProjects(), platforms, npmTarballs);
            }

            if (zip) {
                getLog().info("Packing javarepo.zip ...");
                new ZipPackager().zip(staging.resolve("javarepo"),
                        staging.resolve("javarepo.zip"));
                getLog().info("Packing npmrepo.zip ...");
                new ZipPackager().zip(staging.resolve("npmrepo"),
                        staging.resolve("npmrepo.zip"));
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
