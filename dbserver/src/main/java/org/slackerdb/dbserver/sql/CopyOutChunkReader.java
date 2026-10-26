package org.slackerdb.dbserver.sql;

import org.duckdb.DuckDBChunkedResult;
import org.duckdb.DuckDBColumnType;
import org.duckdb.DuckDBDataChunkReader;
import org.duckdb.DuckDBReadableVector;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@code COPY ... TO STDOUT} 的 chunk 读取器。
 *
 * <p><b>为什么需要它</b>：{@link CopyOutEncoder} 原来逐行走 JDBC {@link java.sql.ResultSet}，
 * 每读一个值都要经过一次 JDBC/JNI 转换。DuckDB Java 驱动 1.5.3.0 起提供
 * {@link DuckDBChunkedResult}：查询结果按引擎原生的 2048 行列向量批次返回，
 * 可以直接按列批量读取，避免逐值过 JNI。对大结果集的导出来说，这是数量级的差别。</p>
 *
 * <p><b>为什么不能无条件使用</b>：chunk 读取器只覆盖部分类型。DuckDB 2.0.0.0 的
 * {@link DuckDBReadableVector} 没有 BLOB、TIME、UUID、INTERVAL、BIT、ENUM、LIST、STRUCT、
 * MAP、ARRAY、UNION、VARIANT、GEOMETRY 等类型的读取方法；对这些列调用
 * {@code chunk.vector(i)} 会直接报 {@code Failed to resolve vector type info}。
 * 因此调用方必须先用 {@link #supports(ResultSetMetaData)} 判断：只要有一列不支持，
 * 整条 COPY 就回退到原来的 ResultSet 路径（语义完全不变）。</p>
 *
 * <p><b>文本形态必须与 ResultSet 完全一致</b>：{@link CopyOutEncoder} 的转义依赖
 * "同一个值导出成同一段文本"，否则往返会被破坏。这里的每个 getter 都对应
 * {@code DuckDBResultSet.getString()} 内部 {@code getObject().toString()} 的类型分派，
 * 输出逐字符一致（时间戳的 {@code Timestamp.toString()}、时区的
 * {@code OffsetDateTime.toString()}、DECIMAL 的 {@code BigDecimal.toString()} 等）。</p>
 *
 * @see <a href="https://duckdb.org/2026/08/21/chunked-query-results-java-driver">
 *      Chunked Query Results in the DuckDB Java Driver</a>
 */
public final class CopyOutChunkReader implements AutoCloseable {

    /**
     * 元数据里可以安全交给 chunk 路径的类型名（DuckDB 2.0.0.0 的规范名）。
     *
     * <p>DECIMAL 带精度/标度（如 {@code DECIMAL(18,3)}），因此单独用前缀判断。
     * JSON 在这里也列出：DuckDB 2.0.0.0 把 JSON 暴露成 VARCHAR 向量，{@code getString}
     * 与 ResultSet 的 {@code JsonNode.toString()} 输出一致；下面的向量类型校验会兜底。</p>
     *
     * <p>刻意不包含：BLOB、TIME、TIME_NS、TIME WITH TIME ZONE、UUID、INTERVAL、BIT、ENUM、
     * LIST/STRUCT/MAP/ARRAY/UNION/VARIANT、GEOMETRY、BIGNUM。这些类型要么读取器没有
     * getter，要么文本表示与 ResultSet 不同，必须走原路径。</p>
     */
    private static final Set<String> SUPPORTED_TYPE_NAMES = Set.of(
            "BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT",
            "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT",
            "HUGEINT", "UHUGEINT", "FLOAT", "DOUBLE",
            "VARCHAR", "JSON", "DATE",
            "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS",
            "TIMESTAMP WITH TIME ZONE"
    );

    /** 向量类型白名单：与 {@link #getString(int, long)} 的分支一一对应。 */
    private static final Set<DuckDBColumnType> SUPPORTED_VECTOR_TYPES = EnumSet.of(
            DuckDBColumnType.BOOLEAN,
            DuckDBColumnType.TINYINT, DuckDBColumnType.SMALLINT,
            DuckDBColumnType.INTEGER, DuckDBColumnType.BIGINT,
            DuckDBColumnType.UTINYINT, DuckDBColumnType.USMALLINT,
            DuckDBColumnType.UINTEGER, DuckDBColumnType.UBIGINT,
            DuckDBColumnType.HUGEINT, DuckDBColumnType.UHUGEINT,
            DuckDBColumnType.FLOAT, DuckDBColumnType.DOUBLE,
            DuckDBColumnType.DECIMAL, DuckDBColumnType.VARCHAR,
            DuckDBColumnType.DATE,
            DuckDBColumnType.TIMESTAMP, DuckDBColumnType.TIMESTAMP_S,
            DuckDBColumnType.TIMESTAMP_MS, DuckDBColumnType.TIMESTAMP_NS,
            DuckDBColumnType.TIMESTAMP_WITH_TIME_ZONE
    );

    private final DuckDBChunkedResult result;
    private final int expectedColumnCount;

    private DuckDBReadableVector[] vectors;
    private DuckDBColumnType[] vectorTypes;
    private long rowCount;

    public CopyOutChunkReader(DuckDBChunkedResult result, int expectedColumnCount) {
        this.result = result;
        this.expectedColumnCount = expectedColumnCount;
    }

    /**
     * 判断结果集的所有列是否都能走 chunk 路径。
     *
     * <p>返回 false 时调用方必须继续使用原来的 {@code executeQuery()/ResultSet} 逻辑。</p>
     */
    public static boolean supports(ResultSetMetaData metaData) throws SQLException {
        if (metaData == null) {
            return false;
        }
        int columnCount = metaData.getColumnCount();
        if (columnCount <= 0) {
            return false;
        }
        for (int i = 1; i <= columnCount; i++) {
            if (!supportsTypeName(metaData.getColumnTypeName(i))) {
                return false;
            }
        }
        return true;
    }

    /** 单列类型名是否在 chunk 支持范围内（供测试与日志使用）。 */
    public static boolean supportsTypeName(String typeName) {
        if (typeName == null) {
            return false;
        }
        String normalized = typeName.trim().toUpperCase(Locale.ROOT);
        // DECIMAL 带精度/标度（如 DECIMAL(18,3)），单独判断；不要用宽泛的 startsWith("DECIMAL")，
        // 否则未来出现 DECIMAL128 之类的名字会被误判。
        if (normalized.equals("DECIMAL")
                || normalized.startsWith("DECIMAL(")
                || normalized.startsWith("DECIMAL (")) {
            return true;
        }
        return SUPPORTED_TYPE_NAMES.contains(normalized);
    }

    /**
     * 前进到下一块。
     *
     * <p>这里会立刻解析并把所有列向量缓存下来，而不是等到第一次 {@link #getString}：
     * 这样"某一列的类型其实读不了"的失败会发生在调用方发出 {@code CopyOutResponse}
     * <b>之前</b>，客户端拿到的是干净的 ErrorResponse，而不是半截 COPY 流。</p>
     *
     * @return 是否还有数据块
     * @throws SQLException 元数据列数与块列数不一致，或向量类型不在白名单内
     */
    public boolean nextChunk() throws SQLException {
        if (!result.nextChunk()) {
            vectors = null;
            vectorTypes = null;
            rowCount = 0;
            return false;
        }

        DuckDBDataChunkReader chunk = result.chunk();
        int columnCount = Math.toIntExact(chunk.columnCount());
        if (expectedColumnCount > 0 && columnCount != expectedColumnCount) {
            throw new SQLException("COPY OUT chunk column count mismatch: expected "
                    + expectedColumnCount + ", got " + columnCount);
        }

        DuckDBReadableVector[] resolvedVectors = new DuckDBReadableVector[columnCount];
        DuckDBColumnType[] resolvedTypes = new DuckDBColumnType[columnCount];
        for (int i = 0; i < columnCount; i++) {
            DuckDBReadableVector vector = chunk.vector(i);
            DuckDBColumnType type = vector.getType();
            if (!SUPPORTED_VECTOR_TYPES.contains(type)) {
                // 元数据说能走、实际向量不支持：宁可明确失败，也不能发出一段客户端解不开的数据。
                throw new SQLException("COPY OUT chunk reader does not support column "
                        + (i + 1) + " of type [" + type + "]");
            }
            resolvedVectors[i] = vector;
            resolvedTypes[i] = type;
        }

        vectors = resolvedVectors;
        vectorTypes = resolvedTypes;
        rowCount = chunk.rowCount();
        return true;
    }

    /** 当前块的数据行数；没有块时为 0。 */
    public long rowCount() {
        return rowCount;
    }

    /** 当前块列数；没有块时为 0。 */
    public int columnCount() {
        return vectors == null ? 0 : vectors.length;
    }

    /**
     * 读取当前块中某列的文本形态。
     *
     * @param columnIndex 0 基列下标（与 DuckDB chunk API 一致）
     * @param row         0 基行下标
     * @return 与 {@code ResultSet.getString(columnIndex + 1)} 完全一致的文本；SQL NULL 返回 null
     */
    public String getString(int columnIndex, long row) {
        DuckDBReadableVector vector = vectors[columnIndex];
        if (vector.isNull(row)) {
            return null;
        }
        return switch (vectorTypes[columnIndex]) {
            case BOOLEAN -> Boolean.toString(vector.getBoolean(row));
            case TINYINT -> Byte.toString(vector.getByte(row));
            case UTINYINT -> Short.toString(vector.getUint8(row));
            case SMALLINT -> Short.toString(vector.getShort(row));
            case USMALLINT -> Integer.toString(vector.getUint16(row));
            case INTEGER -> Integer.toString(vector.getInt(row));
            case UINTEGER -> Long.toString(vector.getUint32(row));
            case BIGINT -> Long.toString(vector.getLong(row));
            case UBIGINT -> vector.getUint64(row).toString();
            case HUGEINT -> vector.getHugeInt(row).toString();
            case UHUGEINT -> vector.getUHugeInt(row).toString();
            case FLOAT -> Float.toString(vector.getFloat(row));
            case DOUBLE -> Double.toString(vector.getDouble(row));
            case DECIMAL -> vector.getBigDecimal(row).toString();
            case VARCHAR -> vector.getString(row);
            case DATE -> vector.getLocalDate(row).toString();
            case TIMESTAMP, TIMESTAMP_S, TIMESTAMP_MS, TIMESTAMP_NS -> {
                Timestamp timestamp = vector.getTimestamp(row);
                yield timestamp == null ? null : timestamp.toString();
            }
            case TIMESTAMP_WITH_TIME_ZONE -> {
                OffsetDateTime value = vector.getOffsetDateTime(row);
                yield value == null ? null : value.toString();
            }
            default -> throw new IllegalStateException(
                    "Unsupported COPY OUT chunk vector type: " + vectorTypes[columnIndex]);
        };
    }

    @Override
    public void close() throws SQLException {
        result.close();
    }
}
