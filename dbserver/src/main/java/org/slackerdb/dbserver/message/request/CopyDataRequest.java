package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBAppender;
import org.slackerdb.common.utils.DBUtil;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public class CopyDataRequest extends PostgresRequest {
    //  CopyData (F & B)
    //    Byte1('d')
    //      Identifies the message as COPY data.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Byten
    //      Data that forms part of a COPY data stream.
    //      Messages sent from the backend will always correspond to single data rows,
    //      but messages sent by frontends might divide the data stream arbitrarily.

    byte[]  copyData;

    public CopyDataRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        copyData = data;
        super.decode(data);
    }

    private static int lastIndexOf(byte[] array, byte target)
    {
        for (int i = array.length - 1; i>=0; i--)
        {
            if (array[i] == target)
            {
                return  i;
            }
        }
        return -1;
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long nCopiedRows = 0;
        try {
            if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat.equalsIgnoreCase("CSV")) {
                String sourceStr;
                if (copyData[copyData.length - 1] == (byte) 0x0A) {
                    // 本次传输结束，和上次没有解析完全的字符串要拼接起来
                    if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.length != 0) {
                        sourceStr = new String(this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained) + new String(copyData);
                    } else {
                        sourceStr = new String(copyData);
                    }
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained = new byte[0];
                }
                else
                {
                    // 本次传输还有尾巴
                    int lastIndex = lastIndexOf(copyData, (byte) 0x0A);
                    if (lastIndex == -1)
                    {
                        // 本次也就传输了一半，啥也不是. 合并两个一半数组
                        if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.length != 0) {
                            byte[] ret = Arrays.copyOf(this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained,
                                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.length + copyData.length);
                            System.arraycopy(copyData, 0, ret, this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.length, copyData.length);
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained = ret;
                        }
                        else
                        {
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained = copyData;
                        }
                        return;
                    }
                    else
                    {
                        // 拆分。 前半部分合并，后半部分单独留下
                        if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.length != 0) {
                            sourceStr = new String(this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained) +
                                    new String(Arrays.copyOfRange(copyData, 0, lastIndex));
                        }
                        else
                        {
                            sourceStr = new String(Arrays.copyOfRange(copyData, 0, lastIndex));
                        }
                        this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained =
                                Arrays.copyOfRange(copyData, lastIndex + 1, copyData.length);
                    }
                }

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
                                        " [" + csvRecord +"].");
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
                            duckDBAppender.append((String)null);
                        } else {
                            duckDBAppender.append(csvRecord.get(nPos));
                        }
                    }
                    duckDBAppender.endRow();
                    nCopiedRows ++;
                }
            }
            else if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat.equalsIgnoreCase("BINARY")) {
                // 导入二进制数据
                List<Object[]> data = DBUtil.convertPGByteToRow(copyData);
                DuckDBAppender duckDBAppender = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender;
                List<Integer> copyTableDbColumnMapPos = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos;
                for (Object[] row : data) {
                    duckDBAppender.beginRow();
                    for (Integer nPos : copyTableDbColumnMapPos) {
                        if (nPos == -1) {
                            duckDBAppender.append((String) null);
                        } else
                        {
                            // 数据内容
                            Object cell = row[nPos];
                            // 数据类型
                            String columnType = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnType.get(nPos);

                            if (cell == null)
                            {
                                duckDBAppender.append((String)null);
                            }
                            else if (columnType.equals("BIGINT")) {
                                duckDBAppender.appendBigDecimal(BigDecimal.valueOf(Utils.bytesToInt64((byte[]) cell)));
                            }
                            else if (columnType.equals("VARCHAR"))
                            {
                                duckDBAppender.append(new String((byte[])cell));
                            }
                            else if (columnType.equals("DOUBLE"))
                            {
                                duckDBAppender.append(Utils.byteToDouble((byte [])cell));
                            }
                            else if (columnType.startsWith("DECIMAL"))
                            {
                                duckDBAppender.appendBigDecimal(DBUtil.convertPGByteToBigDecimal((byte [])cell));
                            }
                            else if (columnType.equals("TIMESTAMP"))
                            {
                                long epochMilli = Utils.bytesToInt64((byte[]) cell) / 1000;
                                duckDBAppender.appendLocalDateTime(Instant.ofEpochMilli(epochMilli).atZone(ZoneId.of("UTC")).toLocalDateTime());
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
            }
            else {
                // 不认识的查询语句， 生成一个错误消息
                ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                errorResponse.setErrorResponse("SLACKER-0099", "Not supported format. " +
                        this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat);
                errorResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

                // 发送ReadyForQuery
                ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
                readyForQuery.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);
            }
            this.dbInstance.getSession(getCurrentSessionId(ctx)).copyAffectedRows += nCopiedRows;
        } catch (SQLException se) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorResponse(String.valueOf(se.getErrorCode()), se.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery(this.dbInstance);
            readyForQuery.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, this.dbInstance.logger);

        } finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
