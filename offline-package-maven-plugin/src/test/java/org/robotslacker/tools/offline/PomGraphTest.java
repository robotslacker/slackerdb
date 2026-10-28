package org.robotslacker.tools.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline repository has to contain every POM Maven may read while collecting, including
 * versions that lose mediation. These tests pin the reference set the package relies on.
 */
class PomGraphTest {

    @TempDir
    Path repository;

    @Test
    void followsParentsImportsAndTransitiveDependenciesOnly() throws Exception {
        write("org/example/parent/1.0/parent-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <checker.version>3.42.0</checker.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>managed</artifactId>
                        <version>${checker.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>2.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        write("org/example/bom/2.0/bom-2.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>2.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>from-bom</artifactId>
                        <version>3.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        write("org/example/app/1.0/app-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.5</version>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>managed</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>from-bom</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>testonly</artifactId>
                      <version>1.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>providedonly</artifactId>
                      <version>1.0</version>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>optionalonly</artifactId>
                      <version>1.0</version>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                  <profiles>
                    <profile>
                      <id>on-linux</id>
                      <dependencies>
                        <dependency>
                          <groupId>org.example</groupId>
                          <artifactId>profiledep</artifactId>
                          <version>1.0</version>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """);

        PomGraph graph = new PomGraph(this::pom);
        Set<String> references = new HashSet<>();
        for (PomGraph.Reference reference : graph.references("org.example", "app", "1.0")) {
            references.add(reference.kind() + " " + reference.gav());
        }

        // Parent and imports are read by Maven, and a dependency without a version is resolved
        // through the inherited dependency management (which itself imports a BOM).
        assertEquals(Set.of(
                "PARENT org.example:parent:1.0",
                "DEPENDENCY org.example:lib:1.5",
                "DEPENDENCY org.example:managed:3.42.0",
                "DEPENDENCY org.example:from-bom:3.0",
                "PROFILE_DEPENDENCY org.example:profiledep:1.0"
        ), references);
        assertTrue(graph.problems().isEmpty(), () -> graph.problems().toString());
    }

    @Test
    void treatsTheParentChainAsImportsOfItsOwn() throws Exception {
        write("org/example/parent/1.0/parent-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>2.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        write("org/example/bom/2.0/bom-2.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>2.0</version>
                  <packaging>pom</packaging>
                </project>
                """);

        PomGraph graph = new PomGraph(this::pom);
        Set<String> references = new HashSet<>();
        for (PomGraph.Reference reference : graph.references("org.example", "parent", "1.0")) {
            references.add(reference.kind() + " " + reference.gav());
        }
        assertEquals(Set.of("IMPORT org.example:bom:2.0"), references);
    }

    @Test
    void resolvesTheLegacyVersionAlias() throws Exception {
        write("org/example/aliased/4.2/aliased-4.2.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>aliased</artifactId>
                  <version>4.2</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>sibling</artifactId>
                        <version>${version}</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>sibling</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomGraph graph = new PomGraph(this::pom);
        Set<String> references = new HashSet<>();
        for (PomGraph.Reference reference : graph.references("org.example", "aliased", "4.2")) {
            references.add(reference.gav());
        }
        assertEquals(Set.of("org.example:sibling:4.2"), references);
    }

    @Test
    void reportsAMissingParentInsteadOfSilentlySkippingIt() {
        // The repository is empty on purpose: reading a POM must fail loudly.
        PomGraph graph = new PomGraph(this::pom);
        PomGraph.MissingPomException error = assertThrows(PomGraph.MissingPomException.class,
                () -> graph.references("org.example", "app", "1.0"));
        assertTrue(error.gav().contains("org.example:app:1.0"), error.gav());
    }

    @Test
    void mapsRepositoryPathsBothWays() {
        Path pom = PomGraph.pomPath(repository, "com.example", "demo", "1.2.3");
        assertTrue(pom.endsWith(Path.of("com", "example", "demo", "1.2.3", "demo-1.2.3.pom")),
                pom::toString);
        String[] gav = PomGraph.gavFromPath(repository, pom);
        assertEquals("com.example", gav[0]);
        assertEquals("demo", gav[1]);
        assertEquals("1.2.3", gav[2]);
    }

    private Path pom(String groupId, String artifactId, String version)
            throws PomGraph.MissingPomException {
        Path pom = PomGraph.pomPath(repository, groupId, artifactId, version);
        if (!Files.isRegularFile(pom)) {
            throw new PomGraph.MissingPomException(groupId + ":" + artifactId + ":" + version, null);
        }
        return pom;
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = repository.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
