package org.robotslacker.tools.offline;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A resolved Maven artifact together with its local file.
 */
public final class ArtifactRecord {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String classifier;
    private final String extension;
    private final Path file;
    private final String source;

    public ArtifactRecord(String groupId, String artifactId, String version,
                          String classifier, String extension, Path file, String source) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.version = Objects.requireNonNull(version, "version");
        this.classifier = classifier == null ? "" : classifier;
        this.extension = extension == null ? "" : extension;
        this.file = file;
        this.source = source == null ? "" : source;
    }

    public String groupId() { return groupId; }
    public String artifactId() { return artifactId; }
    public String version() { return version; }
    public String classifier() { return classifier; }
    public String extension() { return extension; }
    public Path file() { return file; }
    public String source() { return source; }

    public String ga() { return groupId + ":" + artifactId; }
    public String gav() { return groupId + ":" + artifactId + ":" + version; }

    public String key() {
        return gav() + ":" + classifier + ":" + extension;
    }

    public String fileName() {
        if (file != null) {
            return file.getFileName().toString();
        }
        return artifactId + "-" + version
                + (classifier.isEmpty() ? "" : "-" + classifier)
                + (extension.isEmpty() ? "" : "." + extension);
    }

    /** Path inside a Maven repository, using '/' separators. */
    public String repositoryPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName();
    }

    public boolean hasFile() {
        return file != null;
    }
}
