package org.slackerdb.dbserver.sql;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.Column;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.CommandComplete;
import org.slackerdb.dbserver.message.response.DataRow;
import org.slackerdb.dbserver.message.response.RowDescription;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;
import org.slackerdb.dbserver.server.ServerCursor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PG 游标族语句（{@code DECLARE ... CURSOR ... FOR} / {@code FETCH} / {@code MOVE} / {@code CLOSE}）的执行。
 *
 * <p>这条通路是 {@code postgres_fdw} 取远端数据的<b>唯一</b>方式
 * （{@code DECLARE cN CURSOR FOR <select>} → 反复 {@code FETCH 100 FROM cN} → {@code CLOSE cN}），
 * 也是 SlackDB 之前唯一缺失的读取路径。</p>
 *
 * <p><b>复用已有机制</b>：列描述与行编码都用 {@link RowEncoder}（与普通查询、portal 分批同源），
 * 因此"声明的格式码与发出去的字节一致"这个不变量在游标路径上同样成立；
 * 每一列的结果格式按调用方给出的 {@code resultFormats} 解析（简单查询路径 = 全文本，
 * 恰好也是 fdw 的用法）。</p>
 *
 * <p><b>错误语义</b>：游标不存在 → {@code 34000}；重名声明 → {@code 42P03}；
 * 需要可滚动结果集的方向（{@code BACKWARD}/{@code ABSOLUTE}/{@code RELATIVE}）→ {@code 0A000}
 * （DuckDB 的 JDBC 结果集不能后退，明确报错而不是假装滚动）。</p>
 */
public final class CursorHandler {

    private CursorHandler() {
    }

    /**
     * 一次游标语句的处理结果（供调用方登记审计与决定是否补 ReadyForQuery）。
     *
     * @param commandTag 成功时的命令标签（{@code DECLARE CURSOR} / {@code FETCH n} / …）
     * @param rows       影响行数（FETCH/MOVE 的移动行数）
     * @param sqlState   非空 = 出错
     */
    public record CursorResult(String commandTag, long rows, String sqlState, String errorMessage) {
        public boolean isError() {
            return sqlState != null;
        }
    }

    /**
     * 处理一条游标族语句（把应答写进 {@code out}，但不发 ReadyForQuery —— 由调用方按协议路径决定）。
     *
     * @param resultFormats 结果列格式码（简单查询路径传 {@link RowEncoder#ALL_TEXT}）
     * @return {@code null} = 不是游标族语句（调用方继续原有流程）
     */
    public static CursorResult handle(DBInstance dbInstance, ChannelHandlerContext ctx, Object request,
                                      ByteArrayOutputStream out, DBSession session, String sql,
                                      short[] resultFormats, boolean withRowDescription) throws IOException {
        CursorSql.CursorStatement statement = CursorSql.parse(sql);
        if (statement == null) {
            return null;
        }
        try {
            CursorResult result = switch (statement.kind()) {
                case DECLARE -> declare(dbInstance, session, statement.declare());
                case FETCH -> fetch(dbInstance, ctx, request, out, session, statement.step(), resultFormats,
                        withRowDescription);
                case MOVE -> move(session, statement.step());
                case CLOSE -> close(session, statement);
            };
            // FETCH 自己渲染（行数要随行一起写），其余成功语句在这里统一补 CommandComplete
            if (result != null && !result.isError() && statement.kind() != CursorSql.CursorStatement.Kind.FETCH) {
                CommandComplete commandComplete = new CommandComplete(dbInstance);
                commandComplete.setCommandResult(result.commandTag());
                commandComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, dbInstance.logger);
            }
            return result;
        } catch (SQLException e) {
            return new CursorResult(null, 0, SqlStateMapper.fromException(e), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ DECLARE

    private static CursorResult declare(DBInstance dbInstance, DBSession session, CursorSql.Declare declare)
            throws SQLException {
        if (session.getCursor(declare.name()) != null) {
            return new CursorResult(null, 0, "42P03",
                    "cursor \"" + declare.name() + "\" already exists");
        }

        // 内层查询与普通查询走同一套改写（pg_catalog 重定向、search_path 处理等）
        String rewritten = SQLReplacer.replaceSQL(dbInstance, declare.query());
        PreparedStatement prepared = session.dbConnection.prepareStatement(rewritten);
        ResultSet resultSet = prepared.executeQuery();
        ResultSetMetaData metaData = resultSet.getMetaData();

        if (declare.withHold()) {
            // WITH HOLD 要跨 COMMIT 存活：JDBC 结果集做不到，声明时就物化（文本编码）
            List<byte[][]> rows = materialize(dbInstance, resultSet, metaData);
            resultSet.close();
            prepared.close();
            session.addCursor(new ServerCursor(declare.name(), true, rewritten, metaData, null, null, rows));
        } else {
            session.addCursor(new ServerCursor(declare.name(), false, rewritten, metaData,
                    prepared, resultSet, null));
        }
        return new CursorResult("DECLARE CURSOR", 0, null, null);
    }

    /** 把结果集全部取出并按文本编码存成 {@code byte[][]}（物化模式）。 */
    private static List<byte[][]> materialize(DBInstance dbInstance, ResultSet resultSet,
                                              ResultSetMetaData metaData) throws SQLException {
        RowEncoder encoder = new RowEncoder(metaData, RowEncoder.ALL_TEXT, dbInstance.logger);
        List<byte[][]> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<Column> encoded = encoder.encode(resultSet);
            byte[][] row = new byte[encoded.size()][];
            for (int i = 0; i < encoded.size(); i++) {
                Column column = encoded.get(i);
                // encode() 返回的对象与字节数组是复用的，必须深拷贝
                row[i] = column.columnLength < 0 ? null : Arrays.copyOf(column.columnValue, column.columnLength);
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ FETCH

    private static CursorResult fetch(DBInstance dbInstance, ChannelHandlerContext ctx, Object request,
                                      ByteArrayOutputStream out, DBSession session, CursorSql.Step step,
                                      short[] resultFormats, boolean withRowDescription)
            throws SQLException, IOException {
        ServerCursor cursor = session.getCursor(step.name());
        if (cursor == null) {
            return new CursorResult(null, 0, "34000", "cursor \"" + step.name() + "\" does not exist");
        }
        if (step.backward()) {
            return new CursorResult(null, 0, "0A000",
                    "scrollable cursors (BACKWARD/ABSOLUTE/RELATIVE) are not supported by SlackerDB");
        }

        // 简单查询路径每次 FETCH 都要发 RowDescription；扩展协议路径不发
        // （客户端已经从 Describe('P') 拿到行描述，重复发是协议违规）。
        // 两条路径的格式码都必须与下面 DataRow 的字节一致。
        RowEncoder encoder = new RowEncoder(cursor.metaData, resultFormats, dbInstance.logger);
        if (withRowDescription) {
            RowDescription rowDescription = new RowDescription(dbInstance);
            rowDescription.setFields(encoder.describe());
            rowDescription.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, dbInstance.logger);
            rowDescription.setFields(null);
        }

        long sent = 0;
        DataRow dataRow = new DataRow(dbInstance);
        if (cursor.materialized != null) {
            int limit = step.count() < 0 ? cursor.materialized.size() : step.count();
            while (sent < limit && cursor.position < cursor.materialized.size()) {
                dataRow.setColumns(columnsOf(cursor.materialized.get(cursor.position++)));
                dataRow.process(ctx, request, out);
                dataRow.setColumns(null);
                PostgresMessage.write(ctx, DataRow.class.getSimpleName(), out, dbInstance.logger);
                sent++;
            }
        } else {
            int limit = step.count() < 0 ? Integer.MAX_VALUE : step.count();
            while (sent < limit && cursor.resultSet.next()) {
                dataRow.setColumns(encoder.encode(cursor.resultSet));
                dataRow.process(ctx, request, out);
                dataRow.setColumns(null);
                PostgresMessage.write(ctx, DataRow.class.getSimpleName(), out, dbInstance.logger);
                sent++;
            }
        }

        CommandComplete commandComplete = new CommandComplete(dbInstance);
        commandComplete.setCommandResult("FETCH " + sent);
        commandComplete.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, dbInstance.logger);

        return new CursorResult("FETCH " + sent, sent, null, null);
    }

    // ------------------------------------------------------------------ MOVE

    private static CursorResult move(DBSession session, CursorSql.Step step) throws SQLException {
        ServerCursor cursor = session.getCursor(step.name());
        if (cursor == null) {
            return new CursorResult(null, 0, "34000", "cursor \"" + step.name() + "\" does not exist");
        }
        if (step.backward()) {
            return new CursorResult(null, 0, "0A000",
                    "scrollable cursors (BACKWARD/ABSOLUTE/RELATIVE) are not supported by SlackerDB");
        }

        long moved = 0;
        if (cursor.materialized != null) {
            int limit = step.count() < 0 ? cursor.materialized.size() : step.count();
            while (moved < limit && cursor.position < cursor.materialized.size()) {
                cursor.position++;
                moved++;
            }
        } else {
            int limit = step.count() < 0 ? Integer.MAX_VALUE : step.count();
            while (moved < limit && cursor.resultSet.next()) {
                moved++;
            }
        }
        return new CursorResult("MOVE " + moved, moved, null, null);
    }

    // ------------------------------------------------------------------ CLOSE

    private static CursorResult close(DBSession session, CursorSql.CursorStatement statement) {
        if (statement.closeAll()) {
            session.closeAllCursors();
            return new CursorResult("CLOSE CURSOR", 0, null, null);
        }
        if (!session.closeCursor(statement.closeName())) {
            return new CursorResult(null, 0, "34000",
                    "cursor \"" + statement.closeName() + "\" does not exist");
        }
        return new CursorResult("CLOSE CURSOR", 0, null, null);
    }

    // ------------------------------------------------------------------ 工具

    /** 把物化行（字节数组）还原成 {@link DataRow} 需要的 Column 列表。 */
    private static List<Column> columnsOf(byte[][] row) {
        List<Column> columns = new ArrayList<>(row.length);
        for (byte[] value : row) {
            Column column = new Column();
            if (value == null) {
                column.columnLength = -1;
                column.columnValue = null;
            } else {
                column.columnValue = value;
                column.columnLength = value.length;
            }
            columns.add(column);
        }
        return columns;
    }

    /** 供扩展协议的 {@code Describe} 复用：给出某游标当前应报告的行描述。 */
    public static List<org.slackerdb.dbserver.entity.Field> describeCursor(DBInstance dbInstance,
                                                                           ServerCursor cursor,
                                                                           short[] resultFormats)
            throws SQLException {
        return new RowEncoder(cursor.metaData, resultFormats, dbInstance.logger).describe();
    }
}
