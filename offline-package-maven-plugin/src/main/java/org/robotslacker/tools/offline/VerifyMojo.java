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
import java.util.LinkedHashSet;
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
            List<String> missing = auditPomClosure(javarepo, report, lines);
            if (!missing.isEmpty()) {
                lines.append("MISSING POMs (an offline build would print \"The POM ... is missing\""
                        + " and could fail to resolve dependencies):").append('\n');
                for (String entry : missing) {
                    lines.append("  - ").append(entry).append('\n');
                }
                throw new IllegalStateException("Offline repository is missing " + missing.size()
                        + " POM(s) referenced by its own POMs; see "
                        + report.getFileName());
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

    /**
     * Verifies the POM descriptor closure of the offline repository with no resolver involved:
     * every parent, imported BOM and compile/runtime dependency POM referenced by a packaged POM
     * must be packaged too. Maven reads those descriptors during collection (including versions
     * that lose mediation), so a gap here is exactly the "The POM for ... is missing, no
     * dependency information available" warning an offline build would print.
     *
     * <p>Dependencies declared inside POM profiles are only reported as warnings: Maven activates
     * those profiles itself (OS, JDK, user properties), so a POM that is absent for an inactive
     * profile such as {@code m2e} cannot break an offline build.</p>
     */
    private List<String> auditPomClosure(Path javarepo, Path report, StringBuilder lines) throws Exception {
        PomGraph graph = new PomGraph((groupId, artifactId, version) -> {
            Path pom = PomGraph.pomPath(javarepo, groupId, artifactId, version);
            if (!Files.isRegularFile(pom)) {
                throw new PomGraph.MissingPomException(groupId + ":" + artifactId + ":" + version, null);
            }
            return pom;
        });
        List<Path> poms;
        try (var stream = Files.walk(javarepo)) {
            poms = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pom"))
                    .toList();
        }
        Set<String> missing = new LinkedHashSet<>();
        Set<String> profileOnlyGaps = new LinkedHashSet<>();
        Set<String> checked = new HashSet<>();
        for (Path pom : poms) {
            String[] gav = PomGraph.gavFromPath(javarepo, pom);
            if (gav == null) {
                lines.append("SKIP non-standard POM path: ")
                        .append(javarepo.relativize(pom).toString().replace('\\', '/')).append('\n');
                continue;
            }
            checked.add(gav[0] + ":" + gav[1] + ":" + gav[2]);
            try {
                for (PomGraph.Reference reference : graph.references(gav[0], gav[1], gav[2])) {
                    Path target = PomGraph.pomPath(javarepo, reference.groupId(),
                            reference.artifactId(), reference.version());
                    if (Files.isRegularFile(target)) {
                        continue;
                    }
                    String entry = reference.gav() + " (" + reference.kind().name().toLowerCase()
                            + " of " + reference.origin() + ")";
                    if (reference.kind() == PomGraph.Kind.PROFILE_DEPENDENCY) {
                        profileOnlyGaps.add(entry);
                    } else {
                        missing.add(entry);
                    }
                }
            } catch (PomGraph.MissingPomException e) {
                missing.add(e.gav() + " (needed by " + gav[0] + ":" + gav[1] + ":" + gav[2] + ")");
            }
        }
        for (String problem : graph.problems()) {
            lines.append("WARN ").append(problem).append('\n');
        }
        for (String note : graph.notes()) {
            lines.append("WARN ").append(note).append('\n');
        }
        for (String gap : profileOnlyGaps) {
            lines.append("WARN profile-only POM not packaged: ").append(gap).append('\n');
        }
        if (!profileOnlyGaps.isEmpty()) {
            getLog().warn("POM closure: " + profileOnlyGaps.size()
                    + " POM(s) are only referenced from inactive/side POM profiles and are not"
                    + " packaged (they cannot break an offline build); see " + report.getFileName());
        }
        lines.append("POM descriptor closure: ").append(checked.size())
                .append(" POMs checked, ").append(missing.size()).append(" missing")
                .append(", ").append(profileOnlyGaps.size()).append(" profile-only gaps")
                .append('\n');
        return List.copyOf(missing);
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
