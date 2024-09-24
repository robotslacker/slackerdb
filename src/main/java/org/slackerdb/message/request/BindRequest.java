package org.slackerdb.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.entity.ParsedStatement;
import org.slackerdb.entity.PostgresTypeOids;
import org.slackerdb.logger.AppLogger;
import org.slackerdb.message.PostgresMessage;
import org.slackerdb.message.PostgresRequest;
import org.slackerdb.message.response.BindComplete;
import org.slackerdb.message.response.ErrorResponse;
import org.slackerdb.server.DBInstance;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            ParsedStatement parsedStatement =
                    DBInstance.getSession(getCurrentSessionId(ctx)).getParsedStatement("PreparedStatement" + "-" + preparedStmtName);
            if (parsedStatement != null)
            {
                // 取出上次解析的SQL，如果为空语句，则直接返回
                if (parsedStatement.sql.isEmpty()) {
                    BindComplete bindComplete = new BindComplete();
                    bindComplete.process(ctx, request, out);

                    // 发送并刷新返回消息
                    PostgresMessage.writeAndFlush(ctx, BindComplete.class.getSimpleName(), out);
                    out.close();
                    return;
                }

                //  如果是PLSQL，则不需要Bind
                if (parsedStatement.isPlSql)
                {
                    // 记录Bind后的PreparedStatement, 无需解析，无需绑定
                    ParsedStatement parsedBindPreparedStatement = new ParsedStatement();
                    parsedBindPreparedStatement.sql = parsedStatement.sql;
                    parsedBindPreparedStatement.isPlSql = true;
                    parsedBindPreparedStatement.preparedStatement = null;
                    DBInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
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
                                    columnTypeName = PostgresTypeOids.getTypeNameFromTypeOid(parameterDataTypeIds[i]);
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
                                        case "VARCHAR":
                                        case "UNKNOWN":
                                            preparedStatement.setString(i + 1, new String(bindParameters[i], StandardCharsets.UTF_8));
                                            break;
                                        case "INTEGER":
                                            preparedStatement.setInt(i + 1, Utils.bytesToInt32(bindParameters[i]));
                                            break;
                                        case "BIGINT":
                                            preparedStatement.setLong(i + 1, Utils.bytesToInt64(bindParameters[i]));
                                            break;
                                        case "BOOLEAN":
                                            preparedStatement.setBoolean(i + 1, bindParameters[i][0] == (byte) 0);
                                            break;
                                        case "DECIMAL":
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
                                            break;
                                        case "FLOAT":
                                            preparedStatement.setFloat(i + 1, ByteBuffer.wrap(bindParameters[i]).getFloat());
                                            break;
                                        case "DOUBLE":
                                            preparedStatement.setDouble(i + 1, ByteBuffer.wrap(bindParameters[i]).getDouble());
                                            break;
                                        default:
                                            AppLogger.logger.error("Not supported type in BindRequest: {}", columnTypeName);
                                            break;
                                    }
                                }
                            }
                        }
                    }

                    // 记录Bind后的PreparedStatement
                    ParsedStatement parsedBindPreparedStatement = new ParsedStatement();
                    parsedBindPreparedStatement.sql = executeSQL;
                    parsedBindPreparedStatement.preparedStatement = preparedStatement;
                    DBInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "Portal" + "-" + portalName, parsedBindPreparedStatement);
                }
            }
            BindComplete bindComplete = new BindComplete();
            bindComplete.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, BindComplete.class.getSimpleName(), out);
        }
        catch (SQLException e) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorFile("BindRequest");
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);
        }
        finally {
            out.close();
        }

    }
}
