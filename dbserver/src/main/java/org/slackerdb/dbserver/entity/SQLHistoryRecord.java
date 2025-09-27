package org.slackerdb.dbserver.entity;

import org.springframework.cglib.core.Local;

import java.time.LocalDateTime;

public record SQLHistoryRecord (
        String type,
        long   ID,
        long   ServerID,
        int    SessionId,
        String ClientIP,
        String SQL,
        long   SqlId,
        LocalDateTime StartTime,
        LocalDateTime EndTime,
        int     SQLCode,
        long    AffectedRows,
        String  ErrorMsg
)
{

}
