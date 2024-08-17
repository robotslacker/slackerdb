package org.slackerdb.protocol.postgres.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slackerdb.protocol.postgres.message.PostgresMessage;
import org.slackerdb.protocol.postgres.message.PostgresRequest;
import org.slackerdb.protocol.postgres.message.response.CopyInResponse;
import org.slackerdb.protocol.postgres.message.response.ErrorResponse;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryRequest  extends PostgresRequest {
    private String      sql = "";

    //  Query (F)
    //    Byte1('Q')
    //      Identifies the message as a simple query.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    String
    //      The query string itself.
    @Override
    public void decode(byte[] data) {
        sql = new String(data, StandardCharsets.UTF_8);

        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            String copyInClausePattern = "^^(\\s+)?COPY\\s+(.*?)((\\s+)?\\((.*)\\))?\\s+FROM\\s+STDIN\\s+WITH\\s+\\((\\s+)?FORMAT\\s+(.*)\\).*";
            Pattern copyInPattern = Pattern.compile(copyInClausePattern, Pattern.CASE_INSENSITIVE);
            Matcher m = copyInPattern.matcher(sql);
            if (m.find()) {
                // 执行COPY IN的命令
                String copyTableName = m.group(2);
                String[] columns = m.group(5).split(",");
                Map<String, Integer> targetColumnMap = new HashMap<>();
                for (int i=0; i<columns.length; i++) {

                    targetColumnMap.put(columns[i].trim().toUpperCase(), i);
                }
                String copyTableFormat = m.group(7);
                DBInstance.getSession(getCurrentSessionId(ctx)).copyTableName = copyTableName;
                DBInstance.getSession(getCurrentSessionId(ctx)).copyTableFormat = copyTableFormat;

                String targetTableName;
                String targetSchemaName;
                if (copyTableName.split("\\.").length > 1) {
                    targetSchemaName = copyTableName.split("\\.")[0];
                    targetTableName = copyTableName.split("\\.")[1];
                } else {
                    targetSchemaName = "";
                    targetTableName = copyTableName;
                }
                DuckDBConnection conn = (DuckDBConnection) DBInstance.getSession(getCurrentSessionId(ctx)).dbConnection;
                DBInstance.getSession(getCurrentSessionId(ctx)).copyTableAppender = conn.createAppender(targetSchemaName, targetTableName);

                // 获取表名的实际表名，DUCK并不支持部分字段的Appender操作。所以要追加列表中不存在的相关信息
                List<Integer> copyTableDbColumnMapPos = new ArrayList<>();
                String executeSql = "";
                if (targetSchemaName.isEmpty()) {
                    executeSql = "SELECT * FROM " + targetTableName + " LIMIT 0";
                }
                else
                {
                    executeSql = "SELECT * FROM " + targetSchemaName + "." + targetTableName + " LIMIT 0";
                }
                PreparedStatement ps = conn.prepareStatement(executeSql);
                ResultSet rs = ps.executeQuery();
                rs.next();
                for (int i=0; i<rs.getMetaData().getColumnCount(); i++)
                {
                    copyTableDbColumnMapPos.add(targetColumnMap.getOrDefault(
                            rs.getMetaData().getColumnName(i+1).toUpperCase(), -1));
                }
                rs.close();
                ps.close();
                DBInstance.getSession(getCurrentSessionId(ctx)).copyTableDbColumnMapPos = copyTableDbColumnMapPos;

                // 发送CopyInResponse
                CopyInResponse copyInResponse = new CopyInResponse();
                copyInResponse.copyColumnCount = (short)targetColumnMap.size();
                copyInResponse.process(ctx, request, out);

                // 发送并刷新返回消息
                PostgresMessage.writeAndFlush(ctx, CopyInResponse.class.getSimpleName(), out);

                out.close();
                return;
            }
            // 不认识的查询语句， 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorResponse("SLACKER-0099", "Not supported yet. " + sql );
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);
        }
        catch (SQLException se)
        {
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

//        以下是 COPY 操作的基本流程：
//
//        1. 客户端发送 COPY 命令
//        客户端首先发送一条包含 COPY 语句的简单查询（Simple Query）请求。例如：
//
//        sql
//                复制代码
//        COPY my_table (column1, column2) FROM stdin;
//        或
//
//                sql
//        复制代码
//        COPY my_table (column1, column2) TO stdout;
//        这里的 stdin 和 stdout 是 COPY 语句的目标，它们分别表示从客户端读取数据或将数据发送到客户端。
//
//        2. 服务器响应
//        根据 COPY 命令的类型，服务器会做出不同的回应：
//
//        对于 COPY FROM stdin;（从客户端读取数据）：服务器会回应一个 CopyInResponse 消息，表示准备接受数据。
//        对于 COPY TO stdout;（将数据发送到客户端）：服务器会回应一个 CopyOutResponse 消息，表示准备发送数据。
//        3. 数据传输阶段
//        3.1. COPY FROM（客户端向服务器发送数据）
//        客户端发送数据行给服务器，每行通过 CopyData 消息发送，直到数据结束。最后，客户端发送 CopyDone 消息表示数据发送完毕。
//
//        服务器在收到数据后进行处理，并在数据结束时发送 CommandComplete 和 ReadyForQuery 消息。
//
//        3.2. COPY TO（服务器向客户端发送数据）
//        服务器将数据逐行通过 CopyData 消息发送给客户端。当数据发送完毕后，服务器发送 CopyDone 和 CommandComplete 消息，表示操作完成。
//
//
//

    }
}
