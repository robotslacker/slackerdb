package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.entity.PostgresTypeOids;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.BindComplete;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Arrays;

public class BindRequest extends PostgresRequest {
    //  Bind (F)
    //      Byte1('B')
    //        Identifies the message as a Bind command.
    //      Int32
    //        Length of message contents in bytes, including self.
    //      String
    //        The name of the destination portal (an empty string selects the unnamed portal).
    //      String
    //        The name of the source prepared statement (an empty string selects the unnamed prepared statement).
    //      Int16
    //        The number of parameter format codes that follow (denoted C below).
    //        This can be zero to indicate that there are no parameters or that the parameters all use the default format (text);
    //        or one, in which case the specified format code is applied to all parameters; or it can equal the actual number of parameters.
    //      Int16[C]
    //        The parameter format codes. Each must presently be zero (text) or one (binary).
    //      Int16
    //        The number of parameter values that follow (possibly zero). This must match the number of parameters needed by the query.
    //        Next, the following pair of fields appear for each parameter:    //
    //        Int32
    //          The length of the parameter value, in bytes (this count does not include itself). Can be zero.
    //          As a special case, -1 indicates a NULL parameter value. No value bytes follow in the NULL case.
    //        Byte(n)
    //          The value of the parameter, in the format indicated by the associated format code. n is the above length.
    //          After the last parameter, the following fields appear:
    //      Int16
    //        The number of result-column format codes that follow (denoted R below). This can be zero to indicate that there are no result columns or
    //        that the result columns should all use the default format (text);
    //        or one, in which case the specified format code is applied to all result columns (if any);
    //        or it can equal the actual number of result columns of the query.
    //        Int16[R]
    //           The result-column format codes. Each must presently be zero (text) or one (binary).

    private String      portalName = "";
    private String      preparedStmtName = "";
    private short[]     formatCodes;
    private byte[][]    bindParameters;

    public BindRequest(DBInstance pDbInstance) {
        super(pDbInstance);
    }

    @Override
    public void decode(byte[] data) {
        byte[][] result = Utils.splitByteArray(data, (byte)0);
        portalName = new String(result[0], StandardCharsets.UTF_8);
        preparedStmtName = new String(result[1], StandardCharsets.UTF_8);
        if (preparedStmtName.isEmpty())
        {
            preparedStmtName = "NONAME";
        }

        int currentPos = result[0].length + 1 + result[1].length + 1;
        byte[] part = Arrays.copyOfRange(
                data,
                currentPos, currentPos + 2);
        short numberOfFormatCodes = Utils.bytesToInt16(part);
        currentPos += 2;
        if (numberOfFormatCodes != 0) {
            formatCodes = new short[numberOfFormatCodes];
            for (int i = 0; i< numberOfFormatCodes; i++) {
                part = Arrays.copyOfRange(
                        data,
                        currentPos, currentPos + 2);
                formatCodes[i] = Utils.bytesToInt16(part);
                currentPos += 2;
            }
        }
        part = Arrays.copyOfRange(
                data,
                currentPos, currentPos + 2);
        short numberOfParameters = Utils.bytesToInt16(part);
        currentPos += 2;
        if (numberOfParameters != 0) {
            bindParameters = new byte[numberOfParameters][];
            for (int i = 0; i< numberOfParameters; i++) {
                part = Arrays.copyOfRange(
                        data,
                        currentPos, currentPos + 4);
                currentPos += 4;
                int parameterByteLength = Utils.bytesToInt32(part);
                if (parameterByteLength == -1)
                {
                    // As a special case, -1 indicates a NULL parameter value
                    bindParameters[i] = null;
                }
                else {
                    part = Arrays.copyOfRange(
                            data,
                            currentPos, currentPos + parameterByteLength);
                    bindParameters[i] = part;
                    currentPos += parameterByteLength;
                }
            }
            if (numberOfFormatCodes == 0)
            {
                formatCodes = new short[numberOfParameters];
                for (int i = 0; i< numberOfParameters; i++) {
                    // 所有的解析变量都使用默认格式，即Text
                    formatCodes[i] = 0;
                }
            }
            if (numberOfFormatCodes == 1)
            {
                short firstFormatCode = formatCodes[0];
                formatCodes = new short[numberOfParameters];
                for (int i = 0; i< numberOfParameters; i++) {
                    // 所有的解析变量都使用同一个格式
                    formatCodes[i] = firstFormatCode;
                }
            }
        }
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        // 记录会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = this.getClass().getSimpleName();
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = LocalDateTime.now();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        tryBlock:
        try {
            ParsedStatement parsedStatement =
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement("PreparedStatement" + "-" + preparedStmtName);
            if (parsedStatement != null)
            {
                // 取出上次解析的SQL，如果为空语句，则直接返回
                if (parsedStatement.sql.isEmpty()) {
                    BindComplete bindComplete = new BindComplete(this.dbInstance);
                    bindComplete.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, BindComplete.class.getSimpleName(), out, this.dbInstance.logger);
                    out.close();

                    // 空语句，也要送回去一个空的Port信息
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "Portal" + "-" + portalName, null);

                    break tryBlock;
                }

                //  如果是PLSQL，则不需要Bind
                if (parsedStatement.isPlSql)
                {
                    // 记录Bind后的PreparedStatement, 无需解析，无需绑定
                    ParsedStatement parsedBindPreparedStatement = new ParsedStatement();
                    parsedBindPreparedStatement.sql = parsedStatement.sql;
                    parsedBindPreparedStatement.isPlSql = true;
                    parsedBindPreparedStatement.preparedStatement = null;
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "Portal" + "-" + portalName, parsedBindPreparedStatement);
                }
                else {
                    // 执行Bind请求
                    String executeSQL = parsedStatement.sql;
                    PreparedStatement preparedStatement = parsedStatement.preparedStatement;
                    if (!preparedStatement.isClosed() && preparedStatement.getParameterMetaData().getParameterCount() != 0) {
                        // 获取参数的类型
                        int[] parameterDataTypeIds = parsedStatement.parameterDataTypeIds;
                        if (bindParameters != null) {
                            for (int i = 0; i < bindParameters.length; i++) {
                                String columnTypeName;
                                if (parameterDataTypeIds.length == 0) {
                                    // 没有指定字段类型，按照默认的VARCHAR处理
                                    columnTypeName = "VARCHAR";
                                } else {
                                    columnTypeName = PostgresTypeOids.getTypeNameFromTypeOid(dbInstance, parameterDataTypeIds[i]);
                                }
                                if (bindParameters[i] == null) {
                                    preparedStatement.setNull(i + 1, Types.NULL);
                                    continue;
                                }
                                if (formatCodes[i] == 0) {
                                    // Text mode
                                    preparedStatement.setString(i + 1, new String(bindParameters[i], StandardCharsets.UTF_8));
                                } else {
                                    // Binary mode
                                    switch (columnTypeName) {
                                        case "VARCHAR", "UNKNOWN" ->
                                                preparedStatement.setString(i + 1, new String(bindParameters[i], StandardCharsets.UTF_8));
                                        case "INTEGER" ->
                                                preparedStatement.setInt(i + 1, Utils.bytesToInt32(bindParameters[i]));
                                        case "BIGINT" ->
                                                preparedStatement.setLong(i + 1, Utils.bytesToInt64(bindParameters[i]));
                                        case "BOOLEAN" ->
                                                preparedStatement.setBoolean(i + 1, bindParameters[i][0] == (byte) 0);
                                        case "DECIMAL" -> {
                                            ByteBuffer buffer = ByteBuffer.wrap(bindParameters[i]);
                                            short nDigits = buffer.getShort();
                                            short weight = buffer.getShort();
                                            short sign = buffer.getShort();
                                            short dScale = buffer.getShort();
                                            int[] digits = new int[nDigits];
                                            for (int j = 0; j < nDigits; j++) {
                                                digits[j] = buffer.getShort();
                                            }
                                            BigDecimal result = BigDecimal.ZERO;
                                            BigDecimal base = BigDecimal.valueOf(10000); // 每个短整数表示4位十进制数字
                                            for (int j = 0; j < nDigits; j++) {
                                                BigDecimal digitValue = BigDecimal.valueOf(digits[j]);
                                                result = result.add(digitValue.multiply(base.pow(weight - j)));
                                            }
                                            if (sign == 1) {
                                                result = result.negate();
                                            }
                                            preparedStatement.setBigDecimal(i + 1, result.setScale(dScale, RoundingMode.UNNECESSARY));
                                        }
                                        case "FLOAT" ->
                                                preparedStatement.setFloat(i + 1, ByteBuffer.wrap(bindParameters[i]).getFloat());
                                        case "DOUBLE" ->
                                                preparedStatement.setDouble(i + 1, ByteBuffer.wrap(bindParameters[i]).getDouble());
                                        default ->
                                                this.dbInstance.logger.error("Not supported type in BindRequest: {}", columnTypeName);
                                    }
                                }
                            }
                        }
                    }

                    // 记录Bind后的PreparedStatement
                    ParsedStatement parsedBindPreparedStatement = new ParsedStatement();
                    parsedBindPreparedStatement.sql = executeSQL;
                    parsedBindPreparedStatement.preparedStatement = preparedStatement;
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "Portal" + "-" + portalName, parsedBindPreparedStatement);
                }
            }
            BindComplete bindComplete = new BindComplete(this.dbInstance);
            bindComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, BindComplete.class.getSimpleName(), out, this.dbInstance.logger);
        }
        catch (SQLException e) {
            // 插入SQL执行历史
            Connection backendSqlHistoryConnection = null;
            try {
                backendSqlHistoryConnection = this.dbInstance.getSqlHistoryConn();
            } catch (SQLException se)
            {
                this.dbInstance.logger.warn("[SERVER] Failed to get sql history conn.", se);
            }
            if (backendSqlHistoryConnection != null)
            {
                String historySQL = "Insert INTO sysaux.SQL_HISTORY(ID, ServerID, SessionId, ClientIP, SQL, SqlId, StartTime, EndTime," +
                        "SQLCode, AffectedRows, ErrorMsg) " +
                        "VALUES(?," + ProcessHandle.current().pid() + ", ?,?,?,?, current_timestamp, current_timestamp, ?, 0, ?)";
                try {
                    PreparedStatement preparedStatement =
                            backendSqlHistoryConnection.prepareStatement(historySQL);
                    preparedStatement.setLong(1, this.dbInstance.backendSqlHistoryId.incrementAndGet());
                    preparedStatement.setLong(2, getCurrentSessionId(ctx));
                    preparedStatement.setString(3, this.dbInstance.getSession(getCurrentSessionId(ctx)).clientAddress);
                    preparedStatement.setString(4, this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL);
                    preparedStatement.setLong(5, this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get());
                    if (e.getErrorCode() == 0) {
                        preparedStatement.setInt(6, -99);
                    }
                    else
                    {
                        preparedStatement.setInt(6, e.getErrorCode());
                    }
                    preparedStatement.setString(7, e.getSQLState() + ":" + e.getMessage());
                    preparedStatement.execute();
                    preparedStatement.close();
                    // 希望连接池能够复用数据库连接
                    this.dbInstance.releaseSqlHistoryConn(backendSqlHistoryConnection);
                }
                catch (SQLException se)
                {
                    this.dbInstance.logger.debug("[SERVER] Save to sql history failed.", se);
                }
            }

            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("BindRequest");
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, this.dbInstance.logger);
        }
        finally {
            out.close();
        }

        // 取消会话的开始时间，以及业务类型
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingFunction = "";
        this.dbInstance.getSession(getCurrentSessionId(ctx)).executingTime = null;
    }
}
