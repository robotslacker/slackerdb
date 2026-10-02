package org.slackerdb.dbserver.message.request;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.ParsedStatement;
import org.slackerdb.dbserver.entity.PostgresTypeOids;
import org.slackerdb.dbserver.entity.SQLHistoryRecord;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.PostgresRequest;
import org.slackerdb.dbserver.message.response.BindComplete;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.Utils;
import org.slackerdb.dbserver.sql.PostgresSQLUtil;
import org.slackerdb.dbserver.sql.SqlStateMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;

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
    //        Next, the following pair of fields appear for each parameter:
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

    // PG 的时间纪元：date/timestamp 的二进制都是"相对 2000-01-01 的偏移"
    private static final LocalDate PG_EPOCH_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime PG_EPOCH_TIMESTAMP = LocalDateTime.of(2000, 1, 1, 0, 0);

    /**
     * 把 PG 二进制格式的参数交给 DuckDB 的 JDBC。
     *
     * <p><b>改造前</b>：只覆盖 INTEGER/BIGINT/BOOLEAN/DECIMAL/FLOAT/DOUBLE/VARCHAR/UNKNOWN 这几种，
     * 其余类型走 {@code default -> logger.error(...)} —— <b>不绑定参数、不回错误</b>。客户端要么拿到 NULL，
     * 要么拿到一个跟"参数没绑定"毫无关系的报错（实测 pgjdbc 会看到
     * {@code Invalid Input Error: Parameter count mismatch}）。</p>
     *
     * <p><b>取值策略</b>：数值类型用 DuckDB 的原生 setter；时间/间隔/UUID/BIT/JSON 这些
     * DuckDB JDBC 没有对应 setter（或会丢精度）的类型，统一还原成 PG 的文本表示再
     * {@code setString}，由 DuckDB 自己做隐式转换 —— 与文本模式（formatCode=0）的既有做法一致，
     * 也避免 {@code java.sql.Time/Timestamp} 的精度截断。</p>
     *
     * <p><b>失败必须明确</b>：类型无法支持 → SQLSTATE {@code 0A000}；
     * 载荷长度与声明类型不符 → {@code 22P03}。两者都会被 {@code process()} 的
     * {@code catch (SQLException)} 转成 ErrorResponse 回给客户端。</p>
     */
    private void bindBinaryParameter(PreparedStatement preparedStatement, int parameterIndex,
                                     String columnTypeName, int declaredTypeOid, byte[] payload)
            throws SQLException {
        switch (columnTypeName) {
            case "BOOLEAN" -> {
                requireBinaryLength(columnTypeName, payload, 1);
                preparedStatement.setBoolean(parameterIndex, payload[0] == (byte) 1);
            }
            case "SMALLINT" -> {
                requireBinaryLength(columnTypeName, payload, 2);
                preparedStatement.setShort(parameterIndex, Utils.bytesToInt16(payload));
            }
            case "INTEGER" -> {
                requireBinaryLength(columnTypeName, payload, 4);
                preparedStatement.setInt(parameterIndex, Utils.bytesToInt32(payload));
            }
            case "BIGINT" -> {
                requireBinaryLength(columnTypeName, payload, 8);
                preparedStatement.setLong(parameterIndex, Utils.bytesToInt64(payload));
            }
            case "FLOAT" -> {
                requireBinaryLength(columnTypeName, payload, 4);
                preparedStatement.setFloat(parameterIndex, ByteBuffer.wrap(payload).getFloat());
            }
            case "DOUBLE" -> {
                requireBinaryLength(columnTypeName, payload, 8);
                preparedStatement.setDouble(parameterIndex, ByteBuffer.wrap(payload).getDouble());
            }
            case "DECIMAL" ->
                    // PG numeric 的二进制布局见 PostgresSQLUtil.convertPGByteToBigDecimal
                    preparedStatement.setBigDecimal(parameterIndex,
                            PostgresSQLUtil.convertPGByteToBigDecimal(payload));
            case "VARCHAR", "TEXT", "UNKNOWN" ->
                    preparedStatement.setString(parameterIndex, new String(payload, StandardCharsets.UTF_8));
            case "DATE" -> {
                requireBinaryLength(columnTypeName, payload, 4);
                LocalDate date = PG_EPOCH_DATE.plusDays(Utils.bytesToInt32(payload));
                preparedStatement.setDate(parameterIndex, java.sql.Date.valueOf(date));
            }
            case "TIME" -> preparedStatement.setString(parameterIndex, pgTimeText(payload));
            case "TIMESTAMP" -> preparedStatement.setString(parameterIndex, pgTimestampText(payload, false));
            case "TIMESTAMP WITH TIME ZONE" ->
                    preparedStatement.setString(parameterIndex, pgTimestampText(payload, true));
            case "INTERVAL" -> preparedStatement.setString(parameterIndex, pgIntervalText(payload));
            case "UUID" -> {
                requireBinaryLength(columnTypeName, payload, 16);
                long mostSignificant = Utils.bytesToInt64(Arrays.copyOfRange(payload, 0, 8));
                long leastSignificant = Utils.bytesToInt64(Arrays.copyOfRange(payload, 8, 16));
                preparedStatement.setString(parameterIndex,
                        new UUID(mostSignificant, leastSignificant).toString());
            }
            case "BYTEA" -> preparedStatement.setBytes(parameterIndex, payload);
            case "BIT" -> preparedStatement.setString(parameterIndex, pgBitText(payload));
            case "JSON" -> preparedStatement.setString(parameterIndex,
                    new String(payload, StandardCharsets.UTF_8));
            case "JSONB" -> {
                // jsonb 的二进制比 json 多一个版本号字节（PG 官方格式：1 字节 version + 文本）
                if (payload.length < 1 || payload[0] != 1) {
                    throw new SQLException("Invalid binary representation for type [JSONB]:"
                            + " expected version byte 1, got " + (payload.length < 1 ? "nothing"
                            : String.valueOf(payload[0])), "22P03");
                }
                preparedStatement.setString(parameterIndex,
                        new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8));
            }
            default -> throw new SQLException("Binary parameter of type ["
                    + (columnTypeName == null || columnTypeName.isEmpty()
                    ? ("OID " + declaredTypeOid) : columnTypeName)
                    + "] is not supported yet by SlackerDB", "0A000");
        }
    }

    /** PG time：int8 微秒（当日）。 */
    private static String pgTimeText(byte[] payload) throws SQLException {
        requireBinaryLength("TIME", payload, 8);
        long micros = Utils.bytesToInt64(payload);
        long seconds = micros / 1_000_000;
        if (seconds < 0 || seconds >= 24 * 3600) {
            throw dateTimeFieldOverflow("TIME", micros);
        }
        return String.format("%02d:%02d:%02d%s", seconds / 3600, (seconds % 3600) / 60, seconds % 60,
                fractionText(micros % 1_000_000));
    }

    /** PG timestamp / timestamptz：int8 微秒（自 2000-01-01 起）。 */
    private static String pgTimestampText(byte[] payload, boolean withTimeZone) throws SQLException {
        String typeName = withTimeZone ? "TIMESTAMP WITH TIME ZONE" : "TIMESTAMP";
        requireBinaryLength(typeName, payload, 8);
        long micros = Utils.bytesToInt64(payload);
        LocalDateTime value;
        try {
            value = PG_EPOCH_TIMESTAMP.plus(micros, ChronoUnit.MICROS);
        }
        catch (RuntimeException runtimeException) {
            // 微秒数越界会抛 DateTimeException/ArithmeticException：转成标准码而不是逃逸到 Netty
            throw dateTimeFieldOverflow(typeName, micros);
        }
        String text = String.format("%04d-%02d-%02d %02d:%02d:%02d",
                value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond())
                + fractionText(value.getNano() / 1000);
        // timestamptz 是绝对时刻：显式带上 UTC 偏移，避免被会话时区二次解释
        return withTimeZone ? text + "+00" : text;
    }

    /** PG 的 datetime_field_overflow（22008）：时间/日期值超出可表示范围。 */
    private static SQLException dateTimeFieldOverflow(String typeName, long value) {
        return new SQLException("Datetime field overflow for type [" + typeName
                + "]: value " + value + " is out of the supported range", "22008");
    }

    /** PG interval：int8 微秒 + int4 天 + int4 月（顺序固定）。 */
    private static String pgIntervalText(byte[] payload) throws SQLException {
        requireBinaryLength("INTERVAL", payload, 16);
        long micros = Utils.bytesToInt64(Arrays.copyOfRange(payload, 0, 8));
        int days = Utils.bytesToInt32(Arrays.copyOfRange(payload, 8, 12));
        int months = Utils.bytesToInt32(Arrays.copyOfRange(payload, 12, 16));

        StringBuilder text = new StringBuilder();
        if (months != 0) {
            text.append(months).append(Math.abs(months) == 1 ? " month " : " months ");
        }
        if (days != 0) {
            text.append(days).append(Math.abs(days) == 1 ? " day " : " days ");
        }
        if (micros != 0 || text.length() == 0) {
            long absolute = Math.abs(micros);
            long seconds = absolute / 1_000_000;
            if (micros < 0) {
                text.append('-');
            }
            text.append(String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60))
                    .append(fractionText(absolute % 1_000_000));
        }
        return text.toString().trim();
    }

    /** PG varbit：int4 位长 + 字节（高位在前）。 */
    private static String pgBitText(byte[] payload) throws SQLException {
        if (payload == null || payload.length < 4) {
            throw invalidBinaryRepresentation("BIT", payload == null ? -1 : payload.length, "at least 4 bytes");
        }
        int bitCount = Utils.bytesToInt32(Arrays.copyOfRange(payload, 0, 4));
        if (bitCount < 0 || payload.length != 4 + (bitCount + 7) / 8) {
            throw invalidBinaryRepresentation("BIT", payload.length, "4 + " + ((bitCount + 7) / 8) + " bytes");
        }
        StringBuilder bits = new StringBuilder(bitCount);
        for (int i = 0; i < bitCount; i++) {
            bits.append((payload[4 + i / 8] & (1 << (7 - (i % 8)))) != 0 ? '1' : '0');
        }
        return bits.toString();
    }

    private static String fractionText(long micros) {
        return micros == 0 ? "" : String.format(".%06d", micros);
    }

    private static void requireBinaryLength(String typeName, byte[] payload, int expected) throws SQLException {
        if (payload == null || payload.length != expected) {
            throw invalidBinaryRepresentation(typeName, payload == null ? -1 : payload.length,
                    expected + " bytes");
        }
    }

    private static SQLException invalidBinaryRepresentation(String typeName, int actual, String expected) {
        return new SQLException("Invalid binary representation for type [" + typeName + "]: expected "
                + expected + ", got " + actual + " bytes", "22P03");
    }

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

                    // 空语句，也要送回去一个空的Port信息。
                    // 注意：这里必须存一个"空语句"对象而不是 null —— parsedStatements 是
                    // ConcurrentHashMap，不接受 null 值（存 null 会抛 NPE）。
                    // 下游对"空语句"的判定本来就是看 preparedStatement == null，
                    // 因此这里与原来的 null 语义一致。
                    ParsedStatement emptyPortal = new ParsedStatement();
                    emptyPortal.sql = "";
                    // 透传客户端原文：审计靠它，否则历史里只剩一条空语句
                    emptyPortal.originalSql = parsedStatement.originalSql;
                    emptyPortal.preparedStatement = null;
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "Portal" + "-" + portalName, emptyPortal);

                    break tryBlock;
                }

                //  如果是PLSQL，则不需要Bind
                if (parsedStatement.isPlSql)
                {
                    // 记录Bind后的PreparedStatement, 无需解析，无需绑定
                    ParsedStatement parsedBindPreparedStatement = new ParsedStatement();
                    parsedBindPreparedStatement.sql = parsedStatement.sql;
                    parsedBindPreparedStatement.originalSql = parsedStatement.originalSql;
                    parsedBindPreparedStatement.isPlSql = true;
                    parsedBindPreparedStatement.preparedStatement = null;
                    this.dbInstance.getSession(getCurrentSessionId(ctx)).saveParsedStatement(
                            "Portal" + "-" + portalName, parsedBindPreparedStatement);
                }
                else {
                    // 执行Bind请求
                    String executeSQL = parsedStatement.sql;
                    PreparedStatement preparedStatement = parsedStatement.preparedStatement;
                    if (preparedStatement != null &&
                            !preparedStatement.isClosed() &&
                            preparedStatement.getParameterMetaData().getParameterCount() != 0) {
                        // 获取参数的类型
                        int[] parameterDataTypeIds = parsedStatement.parameterDataTypeIds;
                        if (bindParameters != null) {
                            for (int i = 0; i < bindParameters.length; i++) {
                                // 客户端可以在 Parse 里少声明几个（甚至一个都不声明）参数类型，
                                // 缺的按"未指定"处理（0），不能再直接下标越界。
                                int declaredTypeOid = (parameterDataTypeIds != null
                                        && i < parameterDataTypeIds.length) ? parameterDataTypeIds[i] : 0;
                                String columnTypeName = (declaredTypeOid == 0)
                                        ? "VARCHAR"
                                        : PostgresTypeOids.getTypeNameFromTypeOid(dbInstance, declaredTypeOid);
                                if (bindParameters[i] == null) {
                                    preparedStatement.setNull(i + 1, Types.NULL);
                                    continue;
                                }
                                if (formatCodes[i] == 0) {
                                    // Text mode：所有类型都走字符串，由 DuckDB 自己做隐式转换
                                    preparedStatement.setString(i + 1, new String(bindParameters[i], StandardCharsets.UTF_8));
                                } else {
                                    // Binary mode：按 PG 的二进制格式解码。
                                    // 这里再兜一层 RuntimeException：畸形载荷可能触发下标越界/数值溢出/
                                    // 时间域越界（DateTimeException）等未检查异常，让它们逃逸到 Netty 会
                                    // **静默断开连接**（客户端连错误都收不到），必须转成协议错误。
                                    try {
                                        bindBinaryParameter(preparedStatement, i + 1, columnTypeName,
                                                declaredTypeOid, bindParameters[i]);
                                    } catch (RuntimeException runtimeException) {
                                        // 记一条 WARN：如果是服务端自身的编码缺陷，日志里要留下痕迹，
                                        // 不能让"转换成了协议错误"把问题掩盖掉
                                        this.dbInstance.logger.warn(
                                                "[SERVER][BIND       ] Decode binary parameter {} of type [{}] failed",
                                                i + 1, columnTypeName, runtimeException);
                                        throw new SQLException("Invalid binary representation for parameter "
                                                + (i + 1) + " of type [" + columnTypeName + "]: "
                                                + runtimeException.getMessage(), "22P03");
                                    }
                                }
                            }
                        }
                    }

                    // 记录Bind后的PreparedStatement
                    ParsedStatement parsedBindPreparedStatement = new ParsedStatement();
                    parsedBindPreparedStatement.sql = executeSQL;
                    parsedBindPreparedStatement.originalSql = parsedStatement.originalSql;
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
            // 事务块内的语句失败必须让事务块进入 aborted 状态（PG 语义），
            // 与 QueryRequest / ExecuteRequest / ParseRequest 保持一致：
            // Bind 阶段失败的语句（例如二进制参数长度不匹配）同样中止事务块。
            this.dbInstance.getSession(getCurrentSessionId(ctx)).markTransactionFailed();

            if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                    this.dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
                SQLHistoryRecord sqlHistoryRecord =
                        new SQLHistoryRecord(
                                "INSERT",
                                this.dbInstance.backendSqlHistoryId.incrementAndGet(),
                                ProcessHandle.current().pid(),
                                getCurrentSessionId(ctx),
                                this.dbInstance.getSession(getCurrentSessionId(ctx)).clientAddress,
                                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL,
                                this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get(),
                                LocalDateTime.now(),
                                LocalDateTime.now(),
                                e.getErrorCode(),
                                0,
                                e.getSQLState() + ":" + e.getMessage()
                        );
                this.dbInstance.sqlHistoryList.offer(sqlHistoryRecord);
            }

            // 生成一个错误消息
            // SQLSTATE 统一走映射器：异常自带合法五字符码时优先用它（本类抛的 0A000/22P03/22008），
            // 否则按 DuckDB 的消息前缀映射（DuckDB 的 getSQLState() 恒为 null、getErrorCode() 恒为 0，
            // 直接透传 errorCode 会让所有错误在客户端看来都是 "0"）。
            ErrorResponse errorResponse = new ErrorResponse(this.dbInstance);
            errorResponse.setErrorFile("BindRequest");
            errorResponse.setErrorResponse(SqlStateMapper.fromException(e), e.getMessage());
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
