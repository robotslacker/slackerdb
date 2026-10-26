package org.slackerdb.dbdesk.access.http;

/**
 * 异步备份任务状态（{@code GET /backups/{id}}）。
 */
public record BackupInfo(
        String id,
        String backupTag,
        String status,
        String filename,
        long sizeBytes,
        String startedAt,
        String finishedAt,
        String error) {

    public boolean running() {
        return "RUNNING".equalsIgnoreCase(status);
    }

    public boolean completed() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "FAILED".equalsIgnoreCase(status);
    }
}
