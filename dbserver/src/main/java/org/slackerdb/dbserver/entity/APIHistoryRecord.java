package org.slackerdb.dbserver.entity;

import java.time.LocalDateTime;

public record APIHistoryRecord (
        String type,
        long   ID,
        long   ServerID,
        String ClientIP,
        LocalDateTime StartTime,
        LocalDateTime EndTime,
        String  Method,
        String  Path,
        String  RequestHeader,
        String  RequestBody,
        long    AffectedRows,
        Boolean Cached,
        int     RetCode,
        String  ErrorMsg
)
{
}
