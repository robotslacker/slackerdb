package org.slackerdb.dbserver.sql;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.Field;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.NoData;
import org.slackerdb.dbserver.message.response.ParameterDescription;
import org.slackerdb.dbserver.message.response.RowDescription;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Locale;

/**
 * {@code Describe} 报文的即时应答。
 *
 * <p><b>协议要求</b>：Describe 必须<b>立即</b>产生应答，与 Execute 无关：</p>
 * <ul>
 *   <li>{@code Describe('S')}（语句）→ {@code ParameterDescription('t')} + RowDescription('T') 或 NoData('n')</li>
 *   <li>{@code Describe('P')}（门户）→ RowDescription('T') 或 NoData('n')（<b>不</b>回 ParameterDescription）</li>
 * </ul>
 *
 * <p><b>改造前的行为</b>：{@code DescribeRequest.process()} 只置一个
 * {@code hasDescribeRequest=true} 标记，把 RowDescription/NoData 推迟到 Execute 才发，
 * 并且从不发送 ParameterDescription。后果：</p>
 * <ol>
 *   <li>"只 Describe 不 Execute"（协议上合法）的客户端永远等不到回应 —— 同步阻塞；</li>
 *   <li>驱动拿不到参数类型（{@code getParameterMetaData()} 失效，参数绑定只能靠猜）。</li>
 * </ol>
 *
 * <p><b>为什么不能直接照搬 {@code getMetaData()}</b>：DuckDB 的 JDBC 对"不返回结果集"的语句
 * 也会给出 1 列<b>伪列</b>（DML → {@code Count:BIGINT}，工具类 → {@code Success:BOOLEAN}），
 * 因为它无法在执行前把"没有结果集"表达出来（{@code StatementReturnType} 只在执行后才有）。
 * 照搬就会把 {@code INSERT}/{@code SET} 描述成"有一个结果集"，驱动随即认为语句返回了结果集
 * （{@code Statement.execute()} 返回 true、{@code executeUpdate()} 抛
 * "A result was returned when none was expected"）。改造前这些语句在 Execute 阶段由
 * {@code ps.execute()} 的布尔值判定、回的是 NoData，因此这里必须按 SQL 语句种类先做判断
 * （{@link #returnsResultSet(String)}），把伪列挡在门外。</p>
 *
 * <p>另外两类语句无法从 JDBC 取到列元数据，同样按"没有结果集"回 NoData：
 * PL/SQL 块（{@code isPlSql}）与 COPY（{@code preparedStatement == null}）。
 * 这与它们的实际行为一致（都不产生结果集）。</p>
 */
public final class DescribeHandler {

    private DescribeHandler() {
    }

    /**
     * 处理 {@code Describe('S')}：回 ParameterDescription + RowDescription/NoData。
     *
     * <p>注意语句级 RowDescription 的 format 码按协议<b>固定为 0（文本）</b> ——
     * 此时还没 Bind，客户端尚未指定结果格式。</p>
     */
    public static void describeStatement(DBInstance dbInstance, ChannelHandlerContext ctx,
                                         ParsedStatement parsedStatement, int[] declaredParameterTypeOids)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 1) ParameterDescription：参数个数 + 每个参数的 PG 类型 OID
        ParameterDescription parameterDescription = new ParameterDescription(dbInstance);
        parameterDescription.setParameterTypeOids(
                resolveParameterTypeOids(parsedStatement, declaredParameterTypeOids, dbInstance));
        parameterDescription.process(ctx, null, out);
        PostgresMessage.writeAndFlush(ctx, ParameterDescription.class.getSimpleName(), out, dbInstance.logger);

        // 2) RowDescription 或 NoData
        writeRowDescriptionOrNoData(dbInstance, ctx, out, parsedStatement, false);

        out.close();
    }

    /**
     * 处理 {@code Describe('P')}：只回 RowDescription/NoData。
     *
     * @param binaryAllowed 门户级描述：结果格式在 Bind 时已确定，可带二进制格式码
     */
    public static void describePortal(DBInstance dbInstance, ChannelHandlerContext ctx,
                                      ParsedStatement parsedStatement, boolean binaryAllowed)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRowDescriptionOrNoData(dbInstance, ctx, out, parsedStatement, binaryAllowed);
        out.close();
    }

    /** 写 RowDescription（有结果集列）或 NoData（无结果集 / 取不到元数据）。 */
    private static void writeRowDescriptionOrNoData(DBInstance dbInstance, ChannelHandlerContext ctx,
                                                    ByteArrayOutputStream out,
                                                    ParsedStatement parsedStatement,
                                                    boolean binaryAllowed)
            throws IOException {
        List<Field> fields = describeColumns(parsedStatement, binaryAllowed, dbInstance);
        if (fields == null || fields.isEmpty()) {
            NoData noData = new NoData(dbInstance);
            noData.process(ctx, null, out);
            PostgresMessage.writeAndFlush(ctx, NoData.class.getSimpleName(), out, dbInstance.logger);
            return;
        }

        RowDescription rowDescription = new RowDescription(dbInstance);
        rowDescription.setFields(fields);
        rowDescription.process(ctx, null, out);
        rowDescription.setFields(null);
        PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, dbInstance.logger);
    }

    /**
     * 取出语句的结果列元数据。
     *
     * <p>用 {@code PreparedStatement.getMetaData()} —— DuckDB 在"已 prepare、未 execute"
     * 阶段就能给出列名与类型，因此 Describe 无需真的执行语句。</p>
     *
     * @return 列描述；无法取到、或语句本来就不返回结果集时返回 null（调用方回 NoData）
     */
    private static List<Field> describeColumns(ParsedStatement parsedStatement, boolean binaryAllowed,
                                               DBInstance dbInstance) {
        if (parsedStatement == null || parsedStatement.isPlSql) {
            return null;
        }
        PreparedStatement ps = parsedStatement.preparedStatement;
        if (ps == null) {
            // COPY 等不创建 PreparedStatement 的语句：没有结果集
            return null;
        }
        if (!returnsResultSet(parsedStatement.sql)) {
            // 语句本身不返回结果集（DML 无 RETURNING / DDL / SET / 事务控制等）：
            // DuckDB 的 getMetaData() 在这里给的是伪列，必须丢掉。
            return null;
        }
        try {
            ResultSetMetaData metaData = ps.getMetaData();
            if (metaData == null) {
                return null;
            }
            // 语句级描述固定文本格式；门户级按 binaryAllowed 决定
            return new RowEncoder(metaData, binaryAllowed, dbInstance.logger).describe();
        }
        catch (Exception e) {
            dbInstance.logger.debug("[SERVER][DESCRIBE   ] Unable to read column metadata: {}", e.getMessage());
            return null;
        }
    }

    /** 不返回结果集的语句（按首个关键字判断，与 {@link CommandTag} 的分类保持一致）。 */
    private static final String[] NON_ROW_STATEMENTS = {
            "SET", "RESET", "BEGIN", "START", "COMMIT", "END", "ROLLBACK", "ABORT",
            "CREATE", "DROP", "ALTER", "TRUNCATE", "COMMENT", "GRANT", "REVOKE",
            "VACUUM", "ANALYZE", "ATTACH", "DETACH", "INSTALL", "LOAD", "CHECKPOINT",
            "EXPORT", "IMPORT", "COPY", "DO", "CALL", "USE", "FORCE"
    };

    /** 带行数后缀的 DML（只有配 {@code RETURNING} 时才返回结果集）。 */
    private static final String[] DML_STATEMENTS = {"INSERT", "UPDATE", "DELETE", "MERGE"};

    /** 直接就是查询（返回结果集）的语句。 */
    private static final String[] QUERY_STATEMENTS = {
            "SELECT", "VALUES", "TABLE", "FROM", "WITH", "SHOW", "DESCRIBE", "DESC",
            "EXPLAIN", "SUMMARIZE", "PIVOT", "UNPIVOT", "PRAGMA"
    };

    /**
     * 该 SQL 是否会返回结果集（决定 Describe 回 RowDescription 还是 NoData）。
     *
     * <p>DuckDB 的 JDBC 拿不到"执行前的返回类型"，因此只能按语句种类判断：
     * 查询系列直接算；DML 只有带 {@code RETURNING} 才返回行；
     * 其余（DDL / 工具类 / 事务控制 / {@code COPY}）都不返回结果集。</p>
     *
     * <p>{@code WITH} 要单独处理：{@code WITH ... SELECT} 返回结果集，
     * 而 {@code WITH ... INSERT/UPDATE/DELETE} 不返回 —— 后者靠"括号外深度 0 处的
     * DML 关键字"识别（CTE 体都在括号里）。</p>
     */
    public static boolean returnsResultSet(String sql) {
        String normalized = SqlCommentStripper.stripComments(sql == null ? "" : sql).trim();
        if (normalized.isEmpty()) {
            // 空语句（纯注释、或 SQLReplacer 改写后的空串）不返回结果集
            return false;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);

        if (startsWithAny(upper, NON_ROW_STATEMENTS)) {
            return false;
        }
        if (startsWithAny(upper, DML_STATEMENTS)) {
            return containsTopLevelKeyword(upper, "RETURNING");
        }
        if (startsWithKeyword(upper, "WITH")) {
            // CTE 之后如果跟的是 DML，则只在有 RETURNING 时才有结果集
            if (containsTopLevelKeyword(upper, "INSERT")
                    || containsTopLevelKeyword(upper, "UPDATE")
                    || containsTopLevelKeyword(upper, "DELETE")
                    || containsTopLevelKeyword(upper, "MERGE")) {
                return containsTopLevelKeyword(upper, "RETURNING");
            }
            return true;
        }
        if (startsWithAny(upper, QUERY_STATEMENTS)) {
            return true;
        }
        // 未识别的语句：按"可能返回结果集"处理（宁可给出行描述，也别把查询当命令）
        return true;
    }

    private static boolean startsWithAny(String upperSql, String[] keywords) {
        for (String keyword : keywords) {
            if (startsWithKeyword(upperSql, keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否以某个关键字开头（按单词边界匹配，避免 {@code SETTING} 被当成 {@code SET}）。
     */
    private static boolean startsWithKeyword(String upperSql, String keyword) {
        if (!upperSql.startsWith(keyword)) {
            return false;
        }
        if (upperSql.length() == keyword.length()) {
            return true;
        }
        char next = upperSql.charAt(keyword.length());
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    /**
     * 在<b>括号深度 0</b> 处查找关键字（整个标识符匹配）。
     *
     * <p>用于识别 {@code WITH ... INSERT} 这类"首个关键字是 WITH、真正在做事的是 DML"的语句：
     * CTE 体一定在括号里，深度 0 处出现的 DML 关键字才是主语句。</p>
     */
    private static boolean containsTopLevelKeyword(String upperSql, String keyword) {
        int depth = 0;
        int i = 0;
        int length = upperSql.length();
        while (i < length) {
            char c = upperSql.charAt(i);
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (depth == 0 && Character.isLetter(c)) {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(upperSql.charAt(i)) || upperSql.charAt(i) == '_')) {
                    i++;
                }
                if (upperSql.regionMatches(start, keyword, 0, keyword.length())
                        && i - start == keyword.length()) {
                    return true;
                }
                continue;
            }
            // 跳过单引号字符串，避免把字符串字面量里的关键字当成语句关键字
            if (c == '\'') {
                i++;
                while (i < length) {
                    if (upperSql.charAt(i) == '\'') {
                        if (i + 1 < length && upperSql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
            }
            i++;
        }
        return false;
    }

    /**
     * 计算参数的 PG 类型 OID 列表。
     *
     * <p>优先使用客户端在 Parse 中显式声明的类型（PG 语义：客户端声明即权威）；
     * 未声明（0）时用 DuckDB 的 {@code ParameterMetaData} 推断。</p>
     */
    private static int[] resolveParameterTypeOids(ParsedStatement parsedStatement,
                                                  int[] declaredParameterTypeOids,
                                                  DBInstance dbInstance) {
        if (declaredParameterTypeOids != null && declaredParameterTypeOids.length > 0) {
            // 客户端声明了类型：原样回填（0 表示该参数由服务端推断，驱动可接受）
            return declaredParameterTypeOids.clone();
        }
        if (parsedStatement == null || parsedStatement.preparedStatement == null) {
            return new int[0];
        }
        try {
            ParameterMetaData parameterMetaData = parsedStatement.preparedStatement.getParameterMetaData();
            if (parameterMetaData == null) {
                return new int[0];
            }
            int count = parameterMetaData.getParameterCount();
            int[] oids = new int[count];
            for (int i = 0; i < count; i++) {
                oids[i] = PgTypeOids.fromJdbcType(parameterMetaData.getParameterType(i + 1));
            }
            return oids;
        }
        catch (Exception e) {
            dbInstance.logger.debug("[SERVER][DESCRIBE   ] Unable to read parameter metadata: {}", e.getMessage());
            return new int[0];
        }
    }
}
