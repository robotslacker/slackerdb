package org.slackerdb.dbserver.entity;

import org.slackerdb.plsql.detect.PlSqlStatement;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

public class ParsedStatement {
    public String sql;
    /**
     * 客户端发来的语句原文（未经 SQLReplacer 改写）。
     *
     * <p>审计需要它：{@code SET}/{@code SHOW} 这类语句会被改写成空串，
     * 只留改写后的 {@link #sql} 会让历史表里出现一条"空语句"，看不出客户端到底发了什么。</p>
     */
    public String originalSql;
    public boolean isPlSql = false;
    /**
     * 多语句脚本（SQL 与匿名块混合）。
     *
     * <p>非 null 时由 Execute 阶段顺序执行每条语句。历史实现用正则识别 {@code $$...$$}，
     * 会把包装之外的语句<b>静默丢弃</b>；现在改为携带完整语句列表。</p>
     */
    public List<PlSqlStatement> plSqlScript;
    public PreparedStatement preparedStatement;
    public ResultSet resultSet;
    public int[] parameterDataTypeIds;
    public long nRowsAffected = 0;
}
