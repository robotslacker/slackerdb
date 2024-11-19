package org.slackerdb.dbserver.plsql;

public class ParseSQLException extends RuntimeException {
    public ParseSQLException(String msg, Exception ex) {
        super(msg, ex);
    }
}

