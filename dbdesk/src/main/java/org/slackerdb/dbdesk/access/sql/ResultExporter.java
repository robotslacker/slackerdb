package org.slackerdb.dbdesk.access.sql;

import org.slackerdb.dbdesk.access.JdbcDrivers;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 结果集导出（CSV / JSON），**流式**写盘：不把结果集读进内存。
 *
 * <p>导出的语义与界面上的"预览"不同：</p>
 * <ul>
 *   <li><b>不截断</b>：界面为了不撑爆浏览器，单值超过 1 MB 会截断；导出要原值；</li>
 *   <li><b>不分页</b>：界面最多显示 1000 行；导出是全部行；</li>
 *   <li><b>允许长跑</b>：调用方在后台线程里跑（1M×50 实测约 12 s），结果用 {@code ui.access} 回填。</li>
 * </ul>
 *
 * <p>CSV 带 UTF-8 BOM（Excel 打开中文才不乱码），行尾 {@code \n}；JSON 是"一行一条记录"的数组，
 * 便于流式写、也便于 grep。</p>
 */
public final class ResultExporter {

    /** 导出格式。 */
    public enum Format {
        CSV("csv"),
        JSON("json");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    /** 导出结果：文件、行数、字节数、耗时。 */
    public record ExportResult(Path file, long rows, long bytes, long elapsedMillis) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResultExporter() {
    }

    /**
     * 把 {@code sql} 的完整结果写到 {@code target}。
     *
     * @param sql 单条查询（调用方负责从脚本里取出最后一条查询语句）
     */
    public static ExportResult export(String jdbcUrl, String sql, Format format, Path target)
            throws SQLException, IOException {
        return export(jdbcUrl, sql, format, target, null, null);
    }

    /** 导出被取消时抛出。调用方负责删掉写了一半的文件。 */
    public static final class ExportCancelledException extends RuntimeException {
        ExportCancelledException(long rowsWritten) {
            super("导出已取消（已写 " + rowsWritten + " 行）");
        }
    }

    /**
     * 带进度与取消的导出。
     *
     * @param cancelled 每 1000 行检查一次；为 true 时中止并抛 {@link ExportCancelledException}
     * @param progress  每 1000 行回调一次"已写行数"（在导出线程上调用，UI 要自己切回 UI 线程）
     */
    public static ExportResult export(String jdbcUrl, String sql, Format format, Path target,
                                      BooleanSupplier cancelled, LongConsumer progress)
            throws SQLException, IOException {
        JdbcDrivers.ensureLoaded();
        long start = System.nanoTime();
        long rows = 0;

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "", "");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.setFetchSize(1_000);
            try (ResultSet rs = statement.executeQuery(sql);
                 Writer writer = new BufferedWriter(Files.newBufferedWriter(target, StandardCharsets.UTF_8))) {

                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                String[] labels = new String[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    labels[i] = meta.getColumnLabel(i + 1);
                }

                if (format == Format.CSV) {
                    writer.write('\uFEFF'); // BOM：Excel 识别 UTF-8
                    writeCsvRow(writer, labels);
                } else {
                    writer.write("[\n");
                }

                boolean first = true;
                while (rs.next()) {
                    // 每 1000 行检查一次取消、回报一次进度：足够及时，又不至于每行都跨线程
                    if ((rows & 0x3FF) == 0) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            throw new ExportCancelledException(rows);
                        }
                        if (progress != null && rows > 0) {
                            progress.accept(rows);
                        }
                    }
                    Object[] values = new Object[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        values[i] = rs.getObject(i + 1);
                    }
                    if (format == Format.CSV) {
                        writeCsvRow(writer, values);
                    } else {
                        writer.write(first ? "  " : ",\n  ");
                        writeJsonObject(writer, labels, values);
                    }
                    first = false;
                    rows++;
                }

                if (format == Format.JSON) {
                    writer.write("\n]\n");
                }
            }
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        return new ExportResult(target, rows, Files.size(target), elapsed);
    }

    // ------------------------------------------------------------------ CSV

    private static void writeCsvRow(Writer writer, Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(text(values[i])));
        }
        writer.write('\n');
    }

    private static String escapeCsv(String value) {
        if (value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ----------------------------------------------------------------- JSON

    private static void writeJsonObject(Writer writer, String[] labels, Object[] values) throws IOException {
        writer.write('{');
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(JSON.writeValueAsString(labels[i]));
            writer.write(':');
            writer.write(jsonValue(values[i]));
        }
        writer.write('}');
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return '"' + Base64.getEncoder().encodeToString(bytes) + '"';
        }
        try {
            return JSON.writeValueAsString(String.valueOf(value));
        } catch (RuntimeException e) {
            return "\"\"";
        }
    }

    // ----------------------------------------------------------------- 工具

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }
}
