package org.robotslacker.tools.offline;

import java.util.List;

/**
 * One target platform for both Maven (os.name/os.arch) and npm (os/cpu/libc).
 */
public record Platform(
        String id,
        String osName,
        String osArch,
        String osVersion,
        String detectedClassifier,
        String npmOs,
        String npmCpu,
        boolean glibc) {

    public static final List<Platform> DEFAULTS = List.of(
            new Platform("windows-x86_64", "Windows 10", "amd64", "10.0",
                    "windows-x86_64", "win32", "x64", true),
            new Platform("linux-amd64", "Linux", "amd64", "5.15.0",
                    "linux-x86_64", "linux", "x64", true),
            new Platform("linux-arm64", "Linux", "aarch64", "5.15.0",
                    "linux-aarch64", "linux", "arm64", true)
    );

    public static Platform byId(String id) {
        return DEFAULTS.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown offline platform: " + id));
    }
}
