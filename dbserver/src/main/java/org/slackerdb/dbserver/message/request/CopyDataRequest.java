package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBAppender;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long nCopiedRows = 0;
        try {
            if (this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat.equalsIgnoreCase("CSV")) {
                List<CSVRecord> records = new ArrayList<>();
                String sourceStr;

                // 和上次没有解析完全的字符串要拼接起来
                if (!this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.isEmpty()) {
                    sourceStr = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained + new String(copyData);
                } else {
                    sourceStr = new String(copyData);
                }

                Iterable<CSVRecord> parsedRecords = CSVFormat.DEFAULT.parse(new StringReader(sourceStr));
                for (CSVRecord record : parsedRecords) {
                    records.add(record);
                }
                if (copyData[copyData.length - 1] == (byte) 0x10) {
                    // 如果最后一个字符是换行符号，则所有信息都要处理
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained = "";
                } else {
                    // 如果最后一个字符不是换行符号，则消息无法处理，留待下次或者结束时候处理
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained =
                            sourceStr.substring((int) records.get(records.size() - 1).getCharacterPosition());
                    records.remove(records.size() - 1);
                }
                for (CSVRecord csvRecord : records) {
                    DuckDBAppender duckDBAppender = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender;
                    List<Integer> copyTableDbColumnMapPos = this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos;
                    duckDBAppender.beginRow();
                    for (Integer copyTableDbColumnMapPo : copyTableDbColumnMapPos) {
                        if (copyTableDbColumnMapPo == -1) {
                            duckDBAppender.append((String)null);
                        } else {
                            duckDBAppender.append(csvRecord.get(copyTableDbColumnMapPo));
                        }
                    }
                    duckDBAppender.endRow();
                    nCopiedRows ++;
                }
            } else {
                // 不认识的查询语句， 生成一个错误消息
                ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
                errorResponse.setErrorResponse("SLACKER-0099", "Not supported format. " +
                        this.dbInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat);
                errorResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
            }
            this.dbInstance.getSession(getCurrentSessionId(ctx)).copyAffectedRows += nCopiedRows;
        } catch (SQLException se) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorResponse(String.valueOf(se.getErrorCode()), se.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
        } finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
