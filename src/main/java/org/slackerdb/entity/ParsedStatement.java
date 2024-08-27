package org.slackerdb.entity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ParsedStatement {
    public String sql;
    public PreparedStatement preparedStatement;
    public ResultSet resultSet;
    public int[] parameterDataTypeIds;
}
