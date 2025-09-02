package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.duckdb.DuckDBAppender;
import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.CommandComplete;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CopyDoneRequest extends PostgresRequest {
    public CopyDoneRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    //  CopyDone (F & B)
    //    Byte1('c')
    //      Identifies the message as a COPY-complete indicator.
    //    Int32(4)
    //      Length of message contents in bytes, including self.
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long nCopiedRows = 0;
        if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.size() != 0) {
            try {
                if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat.equalsIgnoreCase("CSV")) {
                    String sourceStr = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.toString();
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.reset();
                    // 解析CSV内容
                    Iterable<CSVRecord> parsedRecords = CSVFormat.DEFAULT.parse(new StringReader(sourceStr));
                    DuckDBAppender duckDBAppender = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos;
                    for (CSVRecord csvRecord : parsedRecords) {
                        if (csvRecord.size() != copyTableDbColumnMapPos.size()) {
                            // CSV字节数量不对等
                            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                            errorResponse.setErrorResponse("SLACKER-0099",
                                    "CSV Format error (column size not match." +
                                            " [" + csvRecord.size() + "] vs [" + copyTableDbColumnMapPos.size() + "])." +
                                            " [" + csvRecord + "].");
                            errorResponse.process(ctx, request, out);

                            // 发送并刷新返回消息
                            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                            // 发送ReadyForQuery
                            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                            readyForQuery.process(ctx, request, out);

                            // 发送并刷新返回消息
                            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                            return;
                        }
                        duckDBAppender.beginRow();
                        for (Integer nPos : copyTableDbColumnMapPos) {
                            if (nPos == -1) {
                                duckDBAppender.appendDefault();
                            } else {
                                // 数据类型
                                String columnType = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnType.get(nPos);
                                if (columnType.equals("SMALLINT")) {
                                    duckDBAppender.append(Short.parseShort(csvRecord.get(nPos)));
                                }
                                else if (columnType.equals("INTEGER")) {
                                    duckDBAppender.append(Integer.parseInt(csvRecord.get(nPos)));
                                }
                                else if (columnType.equals("BIGINT")) {
                                    duckDBAppender.append(Long.parseLong(csvRecord.get(nPos)));
                                }
                                else if (columnType.equals("VARCHAR"))
                                {
                                    duckDBAppender.append(csvRecord.get(nPos));
                                }
                                else if (columnType.equals("FLOAT"))
                                {
                                    duckDBAppender.append(Float.parseFloat(csvRecord.get(nPos)));
                                }
                                else if (columnType.equals("DOUBLE"))
                                {
                                    duckDBAppender.append(Double.parseDouble(csvRecord.get(nPos)));
                                }
                                else if (columnType.startsWith("DECIMAL"))
                                {
                                    duckDBAppender.append(new BigDecimal(csvRecord.get(nPos)));
                                }
                                else if (columnType.equals("TIMESTAMP"))
                                {
                                    duckDBAppender.append(
                                            LocalDateTime.parse(
                                                    csvRecord.get(nPos),
                                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                    );
                                }
                                else if (columnType.equals("BOOLEAN"))
                                {
                                    duckDBAppender.append(Boolean.parseBoolean(csvRecord.get(nPos)));
                                }
                                else
                                {
                                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                                    errorResponse.setErrorResponse("SLACKER-0099",
                                            "Binary Format error (column type not support) . " + columnType);
                                    errorResponse.process(ctx, request, out);

                                    // 发送并刷新返回消息
                                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                                    // 发送ReadyForQuery
                                    ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                                    readyForQuery.process(ctx, request, out);

                                    // 发送并刷新返回消息
                                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                                    return;
                                }
                            }
                        }
                        duckDBAppender.endRow();
                        nCopiedRows++;
                    }
                } // CSV
                else if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat.equalsIgnoreCase("BINARY")) {
                    List<Object[]> data = DBUtil.convertPGByteToRow(this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.toByteArray());
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.reset();
                    DuckDBAppender duckDBAppender = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos;
                    for (Object[] row : data) {
                        duckDBAppender.beginRow();
                        for (Integer nPos : copyTableDbColumnMapPos) {
                            if (nPos == -1) {
                                duckDBAppender.appendDefault();
                            } else
                            {
                                // 数据内容
                                Object cell = row[nPos];

                                // 数据类型
                                String columnType = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnType.get(nPos);

                                if (cell == null)
                                {
                                    duckDBAppender.appendNull();
                                }
                                else if (columnType.equals("SMALLINT")) {
                                    duckDBAppender.append(Utils.bytesToInt16((byte[]) cell));
                                }
                                else if (columnType.equals("INTEGER")) {
                                    duckDBAppender.append(Utils.bytesToInt32((byte[]) cell));
                                }
                                else if (columnType.equals("BIGINT")) {
                                    duckDBAppender.append(BigInteger.valueOf(Utils.bytesToInt64((byte[]) cell)).longValue());
                                }
                                else if (columnType.equals("VARCHAR"))
                                {
                                    // UTF-8是BINARY COPY唯一支持的字符集，不支持其他的
                                    duckDBAppender.append(new String((byte[])cell, StandardCharsets.UTF_8));
                                }
                                else if (columnType.equals("FLOAT"))
                                {
                                    duckDBAppender.append(Utils.byteToFloat((byte [])cell));
                                }
                                else if (columnType.equals("DOUBLE"))
                                {
                                    duckDBAppender.append(Utils.byteToDouble((byte [])cell));
                                }
                                else if (columnType.startsWith("DECIMAL"))
                                {
                                    duckDBAppender.append(
                                            DBUtil.convertPGByteToBigDecimal((byte[]) cell));
                                }
                                else if (columnType.equals("TIMESTAMP"))
                                {
                                    long epochMilli = Utils.bytesToInt64((byte[]) cell) / 1000;
                                    duckDBAppender.append(Instant.ofEpochMilli(epochMilli).atZone(ZoneId.of("UTC")).toLocalDateTime());
                                }
                                else if (columnType.equals("BOOLEAN"))
                                {
                                    duckDBAppender.append(((byte[]) cell)[0] == 0x01);
                                }
                                else
                                {
                                    ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                                    errorResponse.setErrorResponse("SLACKER-0099",
                                            "Binary Format error (column type not support) . " + columnType);
                                    errorResponse.process(ctx, request, out);

                                    // 发送并刷新返回消息
                                    PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                                    // 发送ReadyForQuery
                                    ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                                    readyForQuery.process(ctx, request, out);

                                    // 发送并刷新返回消息
                                    PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

                                    return;
                                }
                            }
                        }
                        duckDBAppender.endRow();
                        nCopiedRows++;
                    }
                } // BINARY

                // 提交消息
                if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender != null) {
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender.close();
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender = null;
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.reset();
                }

                // 发送CommandComplete
                CommandComplete commandComplete = new CommandComplete(this.dbInstance);
                commandComplete.setCommandResult("COPY " + nCopiedRows);
                commandComplete.process(ctx, request, out);
                PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, this.dbInstance.logger);
            }
            catch (SQLException se)
            {
                // 生成一个错误消息
                ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                errorResponse.setErrorResponse(String.valueOf(se.getErrorCode()), se.getMessage());
                errorResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
            }
        }

        // 发送ReadyForQuery
        ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
        readyForQuery.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        // 发送并刷新返回消息
        PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
        out.close();
    }
}
