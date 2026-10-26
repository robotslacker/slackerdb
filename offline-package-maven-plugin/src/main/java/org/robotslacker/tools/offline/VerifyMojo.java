package org.robotslacker.tools.offline;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.util.filter.AndDependencyFilter;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies that the generated javarepo can resolve the whole reactor offline.
 * The full build/test/install/deploy matrix is executed on each target platform by CI.
 */
@Mojo(name = "verify", aggregator = true, requiresProject = true)
public class VerifyMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySystemSession;

    @Parameter(property = "offline.dir",
            defaultValue = "${project.basedir}/offline", required = true)
    private File outputDirectory;

    @Component
    private ProjectDependenciesResolver dependenciesResolver;

    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        MavenProject top = session.getTopLevelProject();
        if (top != null && project.getBasedir() != null
                && !project.getBasedir().equals(top.getBasedir())) {
            return;
        }
        Path javarepo = outputDirectory.toPath().toAbsolutePath().normalize().resolve("javarepo");
        if (!Files.isDirectory(javarepo)) {
            throw new MojoFailureException("Missing offline Maven repository: " + javarepo);
        }
        Path report = outputDirectory.toPath().toAbsolutePath().normalize()
                .resolve("verify-report-" + osId() + "-" + archId() + ".txt");
        StringBuilder lines = new StringBuilder();
        lines.append("Offline verify").append('\n')
                .append("time: ").append(OffsetDateTime.now()).append('\n')
                .append("javarepo: ").append(javarepo).append('\n');

        try {
            DefaultRepositorySystemSession rs = new DefaultRepositorySystemSession(repositorySystemSession);
            rs.setOffline(true);
            rs.setCache(new org.eclipse.aether.DefaultRepositoryCache());
            rs.setData(new org.eclipse.aether.DefaultSessionData());
            rs.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(
                    rs, new LocalRepository(javarepo.toFile())));

            Set<String> projectGavs = new HashSet<>();
            for (MavenProject p : session.getProjects()) {
                projectGavs.add(p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion());
            }
            for (MavenProject p : session.getProjects()) {
                DefaultDependencyResolutionRequest request =
                        new DefaultDependencyResolutionRequest(p, rs);
                request.setResolutionFilter(new AndDependencyFilter(
                        new ScopeDependencyFilter(
                                List.of("compile", "runtime", "test", "provided"), List.of("system")),
                        new ProjectGavFilter(projectGavs)));
                DependencyResolutionResult result = dependenciesResolver.resolve(request);
                if (!result.getUnresolvedDependencies().isEmpty()
                        || !result.getCollectionErrors().isEmpty()) {
                    throw new IllegalStateException("Offline resolution failed for "
                            + p.getArtifactId() + ": " + result.getUnresolvedDependencies()
                            + " " + result.getCollectionErrors());
                }
                lines.append("OK ").append(p.getArtifactId())
                        .append(" (").append(result.getResolvedDependencies().size())
                        .append(" resolved dependencies)").append('\n');
            }
            lines.append("RESULT: OK").append('\n');
            Files.writeString(report, lines.toString(), StandardCharsets.UTF_8);
            getLog().info("Offline verify passed: " + report);
        } catch (Exception e) {
            lines.append("RESULT: FAIL").append('\n').append(e).append('\n');
            try {
                Files.writeString(report, lines.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // best effort
            }
            throw new MojoExecutionException("Offline verify failed: " + report, e);
        }
    }

    private static String osId() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        return "linux";
    }

    private static String archId() {
        String arch = System.getProperty("os.arch", "unknown").toLowerCase();
        return switch (arch) {
            case "amd64", "x86_64" -> "x86-64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }
}
