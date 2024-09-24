package org.slackerdb.entity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ParsedStatement {
    public String sql;
    public boolean isPlSql = false;
    public PreparedStatement preparedStatement;
    public ResultSet resultSet;
    public int[] parameterDataTypeIds;
    public long nRowsAffected = 0;
}
