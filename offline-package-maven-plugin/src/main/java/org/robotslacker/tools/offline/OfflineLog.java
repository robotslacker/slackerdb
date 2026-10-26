package org.robotslacker.tools.offline;

/** Minimal logging abstraction so collectors do not depend on Maven's Log. */
public interface OfflineLog {
    void info(String message);
    void warn(String message);
}
