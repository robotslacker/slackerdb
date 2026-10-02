package org.slackerdb.dbserver.sql;

import java.util.Locale;
import java.util.Set;

/**
 * COPY 列类型的支持判定（块 Appender 单通道）。
 *
 * <p>服务端只用 {@code DuckDBAppender} 写数据：它是按列物理类型分派的二进制写入器，
 * <b>不做隐式转换</b>，所以每个字段的文本都要由 {@link CopyValueWriters} 解析成精确的 Java 类型。
 * 文本解析能覆盖的类型很多，但有一小组类型<b>连 {@code createAppender()} 都建不起来</b>
 * （DuckDB 的 JDBC Appender 没有对应的 C API 类型），这类列必须明确报错。</p>
 *
 * <p>实测（duckdb_jdbc 1.5.6.0 / duckdb v1.5.6，{@code select version()} 实测）：对下列类型的列，
 * {@code conn.createAppender(...)} 直接抛 {@code Appender error ... unsupported C API type: N}：</p>
 *
 * <ul>
 *   <li>{@code BIT}（29）</li>
 *   <li>{@code INTERVAL}（15）</li>
 *   <li>{@code TIME_NS}（39）</li>
 *   <li>{@code BIGNUM}（35）</li>
 *   <li>{@code VARIANT}（41）</li>
 * </ul>
 *
 * <p>这五个之外的 DuckDB 类型都可以通过块 Appender 的公开 API 写入：
 * 标量用对应的 {@code append(...)} 重载，{@code LIST/ARRAY} 用 {@code append(Collection)}，
 * {@code STRUCT} 用 {@code beginStruct/endStruct}（作为集合元素时用 {@code Map}），
 * {@code MAP} 用 {@code append(Map)}，{@code UNION} 顶层用 {@code beginUnion(tag)/endUnion}
 * （出现在集合/结构体内部时只能用 {@code SimpleEntry(tag, 值)}：Appender 没有 beginList/beginMap，
 * 内部值必须一次性给全，插不进 beginUnion）。</p>
 */
public final class CopyColumnTypes {

    /** 块 Appender 连 createAppender 都建不起来的类型（duckdb_jdbc 1.5.6.0 实测）。 */
    private static final Set<String> APPENDER_UNSUPPORTED = Set.of(
            "BIT", "INTERVAL", "TIME_NS", "BIGNUM", "VARIANT");

    private CopyColumnTypes() {
    }

    private static String normalize(String columnTypeName) {
        return columnTypeName == null ? "" : columnTypeName.toUpperCase(Locale.ROOT).trim();
    }

    /** 该列类型是否连块 Appender 都建不起来。 */
    public static boolean isAppenderUnsupported(String columnTypeName) {
        return APPENDER_UNSUPPORTED.contains(normalize(columnTypeName));
    }

    /**
     * 不支持时的错误文案：说明是哪个列、哪个类型、为什么，以及可以怎么做。
     *
     * <p>刻意写清楚这是"Appender 通道的类型级不支持"，客户端不必逐行试错。</p>
     */
    public static String unsupportedMessage(String columnName, String columnTypeName) {
        String typeName = normalize(columnTypeName);
        return "COPY does not support column [" + columnName + "] of type [" + typeName + "]: "
                + "DuckDB's JDBC appender cannot be created for " + typeName
                + " columns (unsupported C API type). "
                + "Remove the column from the COPY column list, or write it with INSERT/UPDATE.";
    }
}
