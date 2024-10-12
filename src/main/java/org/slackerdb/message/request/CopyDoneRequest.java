package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.duckdb.DuckDBAppender;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.response.CommandComplete;
import org.slackerdb.message.response.ErrorResponse;
import org.slackerdb.message.response.ReadyForQuery;
import org.slackerdb.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

public class CopyDoneRequest extends PostgresRequest {
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
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long nCopiedRows = 0;
        try {
            DuckDBAppender duckDBAppender = DBInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender;

            String  sourceStr;
            // 和上次没有解析完全的字符串要拼接起来
            if (!DBInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained.isEmpty())
            {
                sourceStr = DBInstance.getSession(getCurrentSessionId(ctx)).copyLastRemained;
                List<Integer> copyTableDbColumnMapPos = DBInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos;
                Iterable<CSVRecord> parsedRecords = CSVFormat.DEFAULT.parse(new StringReader(sourceStr));
                for (CSVRecord record : parsedRecords) {
                    duckDBAppender.beginRow();
                    for (Integer copyTableDbColumnMapPo : copyTableDbColumnMapPos) {
                        if (copyTableDbColumnMapPo == -1) {
                            duckDBAppender.append(null);
                        } else {
                            duckDBAppender.append(record.get(copyTableDbColumnMapPo));
                        }
                    }
                    duckDBAppender.endRow();
                    nCopiedRows++;
                }
            }
            duckDBAppender.close();
            DBInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender = null;
            nCopiedRows = DBInstance.getSession(getCurrentSessionId(ctx)).copyAffectedRows + nCopiedRows;
            DBInstance.getSession(getCurrentSessionId(ctx)).copyAffectedRows = 0;

            // 发送CommandComplete
            CommandComplete commandComplete = new CommandComplete();
            commandComplete.setCommandResult("COPY " + nCopiedRows);
            commandComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out);

            // 发送ReadyForQuery
            ReadyForQuery readyForQuery = new ReadyForQuery();
            readyForQuery.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out);
        }
        catch (SQLException se) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorResponse(String.valueOf(se.getErrorCode()), se.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);
        }
        finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        DBInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        DBInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
