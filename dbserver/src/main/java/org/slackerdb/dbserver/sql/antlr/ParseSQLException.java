package org.slackerdb.dbserver.sql.antlr;

public class ParseSQLException extends RuntimeException {
    public ParseSQLException(String msg, Exception ex) {
        super(msg, ex);
    }
}

