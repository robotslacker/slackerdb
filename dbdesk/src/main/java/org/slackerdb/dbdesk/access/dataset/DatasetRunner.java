package org.slackerdb.dbdesk.access.dataset;

import org.slackerdb.dbdesk.access.JdbcDrivers;
import org.slackerdb.dbdesk.access.sql.StatementSplitter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据集执行：把 {@code :name} 参数换成 JDBC 占位符并绑定，**只读**执行。
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li><b>不做字符串拼接</b>：参数一律走 {@code ?}，值再脏也只是值（注入测试见 DatasetRunnerTest）；</li>
 *   <li><b>只允许单条查询</b>：多语句、非 SELECT/WITH 直接拒绝 —— 数据集是给展示层用的，不该有写权限；</li>
 *   <li><b>必须有行数上限</b>：图表最多几千个点，拉一百万行既慢又没意义。</li>
 * </ol>
 *
 * <p><b>聚合放在 SQL 里</b>（{@code group by}），不要拉明细到浏览器再聚合 —— AP 库的明细量级
 * 不在浏览器能承受的范围内。</p>
 */
public final class DatasetRunner {

    /** 一次执行的结果。{@code truncated} 表示撞到了行数上限。 */
    public record Result(List<String> columns, List<String> columnTypes, List<List<Object>> rows,
                         long elapsedMillis, boolean truncated) {

        /** 逻辑类型（number / date / text）：给图表编辑器做角色建议用。 */
        public static String logicalType(String jdbcTypeName, int jdbcType) {
            if (jdbcTypeName != null) {
                String upper = jdbcTypeName.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("INT") || upper.contains("DEC") || upper.contains("NUM" )
                        || upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("REAL")
                        || upper.contains("HUGEINT") || upper.contains("UBIGINT")) {
                    return "number";
                }
                if (upper.contains("DATE") || upper.contains("TIME")) {
                    return "date";
                }
            }
            return switch (jdbcType) {
                case java.sql.Types.BIGINT, java.sql.Types.INTEGER, java.sql.Types.SMALLINT,
                     java.sql.Types.TINYINT, java.sql.Types.DECIMAL, java.sql.Types.NUMERIC,
                     java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL -> "number";
                case java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIMESTAMP,
                     java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "date";
                default -> "text";
            };
        }
    }

    /** 编译后的 SQL：占位符化 + 参数顺序。 */
    public record Compiled(String sql, List<String> parameterOrder) {
    }

    private DatasetRunner() {
    }

    /**
     * 执行数据集。{@code values} 里没有的参数用定义里的默认值；默认值也没有就绑 NULL。
     *
     * @param maxRows 行数上限；<=0 时用 5000
     */
    public static Result run(Dataset dataset, Map<String, Object> values, int maxRows)
            throws SQLException {
        if (dataset.jdbcUrl() == null || dataset.jdbcUrl().isBlank()) {
            throw new IllegalArgumentException("数据集没有绑定连接串");
        }
        ensureSingleQuery(dataset.sql());
        Compiled compiled = compile(dataset.sql());
        int limit = maxRows <= 0 ? 5_000 : maxRows;
        long start = System.nanoTime();

        JdbcDrivers.ensureLoaded();
        try (Connection connection = DriverManager.getConnection(dataset.jdbcUrl(), "", "");
             PreparedStatement statement = connection.prepareStatement(compiled.sql())) {
            statement.setMaxRows(limit + 1); // 多取一行用来判断"是否还有更多"
            statement.setQueryTimeout(60);
            Map<String, Dataset.Param> declared = new LinkedHashMap<>();
            for (Dataset.Param param : dataset.params()) {
                declared.put(param.name(), param);
            }
            List<String> columns = List.of();
            List<String> columnTypesOut = List.of();
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;

            for (int i = 0; i < compiled.parameterOrder().size(); i++) {
                String name = compiled.parameterOrder().get(i);
                Object value = values == null ? null : values.get(name);
                if (value == null) {
                    Dataset.Param param = declared.get(name);
                    value = param == null ? null : param.defaultValue();
                }
                bind(statement, i + 1, value, declared.get(name));
            }

            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> labels = new ArrayList<>(columnCount);
                int[] columnTypes = new int[columnCount];
                List<String> logicalTypes = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    labels.add(meta.getColumnLabel(i));
                    int jdbcType = meta.getColumnType(i);
                    columnTypes[i - 1] = jdbcType;
                    // 参数列（如 select :p as x）的类型名是 unknown，pgjdbc 在这里会断言失败；
                    // 拿不到类型名就按 JDBC 类型推断，绝不能因为一个列把整次查询炸掉。
                    String typeName = null;
                    try {
                        typeName = meta.getColumnTypeName(i);
                    } catch (RuntimeException | AssertionError ignored) {
                        typeName = null;
                    }
                    logicalTypes.add(Result.logicalType(typeName, jdbcType));
                }
                columns = List.copyOf(labels);
                columnTypesOut = List.copyOf(logicalTypes);
                while (rs.next()) {
                    if (rows.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        // 未声明类型的列（例如 `select :p as x` 里的参数列）OID 是 unknown，
                        // pgjdbc 的 getObject 会在内部断言上炸（Misuse of castNonNull）；按字符串读。
                        int type = columnTypes[i - 1];
                        boolean unknown = type == java.sql.Types.OTHER || type == java.sql.Types.NULL || type == 0;
                        row.add(unknown ? rs.getString(i) : rs.getObject(i));
                    }
                    rows.add(row);
                }
            }

            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return new Result(columns, columnTypesOut, rows, elapsed, truncated);
        }
    }

    /** 校验"只有一条查询，且是 SELECT/WITH"（注释与引号里的分号不算）。 */
    public static void ensureSingleQuery(String sql) {
        List<Integer> semicolons = StatementSplitter.topLevelSemicolons(sql);
        // 允许结尾一个分号：开头关键字检查会把它排除在"语句"之外
        String trimmed = sql.strip();
        if (!trimmed.isEmpty() && trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        if (StatementSplitter.topLevelSemicolons(trimmed).size() > 0) {
            throw new IllegalArgumentException("数据集只允许单条查询");
        }
        String upper = stripLeadingComments(trimmed).toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH") && !upper.startsWith("FROM")) {
            throw new IllegalArgumentException("数据集只允许查询语句（SELECT / WITH）");
        }
        if (semicolons.size() > 1) {
            throw new IllegalArgumentException("数据集只允许单条查询");
        }
    }

    /** 去掉开头的空白与注释，用来判断首个关键字。 */
    private static String stripLeadingComments(String sql) {
        String text = sql.stripLeading();
        while (true) {
            if (text.startsWith("--")) {
                int newline = text.indexOf('\n');
                if (newline < 0) {
                    return "";
                }
                text = text.substring(newline + 1).stripLeading();
            } else if (text.startsWith("/*")) {
                int end = text.indexOf("*/");
                if (end < 0) {
                    return "";
                }
                text = text.substring(end + 2).stripLeading();
            } else {
                return text;
            }
        }
    }

    /**
     * 把 {@code :name} 编译成 {@code ?}。
     *
     * <p>跳过：单引号字符串、双引号标识符、{@code --} 行注释、{@code /* *}{@code /} 块注释，
     * 以及 PostgreSQL 的类型转换 {@code ::int}。同一个参数出现两次就绑定两次（顺序记录）。</p>
     */
    public static Compiled compile(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> order = new ArrayList<>();
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = i + 1;
                while (end < length) {
                    if (sql.charAt(end) == '\'' ) {
                        if (end + 1 < length && sql.charAt(end + 1) == '\'') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                out.append(sql, i, Math.min(end + 1, length));
                i = end + 1;
            } else if (c == '"') {
                int end = i + 1;
                while (end < length) {
                    if (sql.charAt(end) == '"') {
                        if (end + 1 < length && sql.charAt(end + 1) == '"') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                out.append(sql, i, Math.min(end + 1, length));
                i = end + 1;
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    out.append(sql, i, length);
                    i = length;
                } else {
                    out.append(sql, i, end);
                    i = end;
                }
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    out.append(sql, i, length);
                    i = length;
                } else {
                    out.append(sql, i, end + 2);
                    i = end + 2;
                }
            } else if (c == ':' && i + 1 < length && sql.charAt(i + 1) == ':') {
                out.append("::"); // 类型转换，不是参数
                i += 2;
            } else if (c == ':' && i + 1 < length && isParamStart(sql.charAt(i + 1))) {
                int end = i + 1;
                while (end < length && isParamPart(sql.charAt(end))) {
                    end++;
                }
                order.add(sql.substring(i + 1, end));
                out.append('?');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return new Compiled(out.toString(), List.copyOf(order));
    }

    private static boolean isParamStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isParamPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void bind(PreparedStatement statement, int index, Object value, Dataset.Param param)
            throws SQLException {
        if (value == null || (value instanceof String text && text.isEmpty())) {
            statement.setObject(index, null);
            return;
        }
        String type = param == null ? "text" : param.type();
        try {
            if ("number".equals(type)) {
                statement.setBigDecimal(index, new java.math.BigDecimal(String.valueOf(value)));
            } else if ("date".equals(type)) {
                statement.setObject(index, value instanceof java.time.LocalDate
                        ? value
                        : java.time.LocalDate.parse(String.valueOf(value)));
            } else {
                statement.setString(index, String.valueOf(value));
            }
        } catch (RuntimeException conversionFailure) {
            throw new SQLException("参数 " + (param == null ? "" : param.name())
                    + " 的值不合法：" + value, conversionFailure);
        }
    }
}
