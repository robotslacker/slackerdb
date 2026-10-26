package org.robotslacker.tools.offline;

import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;

import java.util.List;
import java.util.Set;

/**
 * Excludes reactor modules from a dependency graph. Each module is resolved on its own,
 * and the reactor artifacts are intentionally not part of javarepo; without this filter
 * Maven tries to download a not-yet-built module from a remote repository during validate.
 */
final class ProjectGavFilter implements DependencyFilter {
    private final Set<String> projectGavs;

    ProjectGavFilter(Set<String> projectGavs) {
        this.projectGavs = projectGavs;
    }

    @Override
    public boolean accept(DependencyNode node, List<DependencyNode> parents) {
        if (node == null || node.getArtifact() == null) {
            return true;
        }
        String gav = node.getArtifact().getGroupId() + ":"
                + node.getArtifact().getArtifactId() + ":"
                + node.getArtifact().getVersion();
        return !projectGavs.contains(gav);
    }
}
