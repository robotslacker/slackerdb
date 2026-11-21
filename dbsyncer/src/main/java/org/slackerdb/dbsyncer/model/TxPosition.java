package org.slackerdb.dbsyncer.model;

import java.util.Objects;

/**
 * 统一位点：PG 的 commit LSN，或 MySQL 的 (binlog file, next position)。
 */
public final class TxPosition {

    public enum Kind {
        PG_LSN, MYSQL_BINLOG
    }

    public final Kind kind;
    public final long lsn;
    public final String binlogFile;
    public final long binlogPosition;

    private TxPosition(Kind kind, long lsn, String binlogFile, long binlogPosition) {
        this.kind = kind;
        this.lsn = lsn;
        this.binlogFile = binlogFile;
        this.binlogPosition = binlogPosition;
    }

    public static TxPosition pg(long lsn) {
        return new TxPosition(Kind.PG_LSN, lsn, null, 0);
    }

    public static TxPosition mysql(String file, long position) {
        return new TxPosition(Kind.MYSQL_BINLOG, 0, file, position);
    }

    public boolean isPg() {
        return kind == Kind.PG_LSN;
    }

    public String typeName() {
        return kind == Kind.PG_LSN ? "pg_lsn" : "mysql_binlog";
    }

    public String display() {
        if (kind == Kind.PG_LSN) {
            return String.format("%X/%X", (lsn >>> 32) & 0xFFFFFFFFL, lsn & 0xFFFFFFFFL);
        }
        return binlogFile + ":" + binlogPosition;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TxPosition that)) {
            return false;
        }
        return kind == that.kind && lsn == that.lsn && binlogPosition == that.binlogPosition
                && Objects.equals(binlogFile, that.binlogFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, lsn, binlogFile, binlogPosition);
    }

    @Override
    public String toString() {
        return display();
    }
}
