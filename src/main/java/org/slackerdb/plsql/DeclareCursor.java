package org.slackerdb.plsql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DeclareCursor {
    String              sql;
    PreparedStatement pStmt;
    ResultSet rs;
    boolean   fetchEOF = false;
}
