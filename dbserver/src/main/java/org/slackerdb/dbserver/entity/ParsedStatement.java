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
    /**
     * Bind 中客户端请求的<b>结果列</b>格式码（PG 语义：结果格式属于 portal，不属于语句）。
     *
     * <p>协议规则：长度为 0 → 所有列用文本(0)；长度为 1 → 该值广播到所有列；
     * 长度为 n → 逐列取值。dbserver 历史上从不解析这段，一律按服务端默认的二进制下发，
     * 于是请求 text 的 libpq 客户端（例如 postgres_fdw 的 DML RETURNING）会把
     * 4 字节二进制当成文本去解码。RowDescription 与实际 DataRow 必须用同一份格式，
     * 因此这里保存的是"客户端请求"，具体落到哪一列由 {@code RowEncoder} 按类型能力解析。</p>
     */
    public short[] resultFormats = new short[0];
    public long nRowsAffected = 0;
}
