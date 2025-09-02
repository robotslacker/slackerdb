package org.slackerdb.common.utils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.ByteOrder;
import java.math.RoundingMode;

public class DBUtil {
    public static Map<String, String> parseJdbcUrl(String jdbcUrl) {
        Map<String, String> result = new HashMap<>();

        // 正则表达式匹配 JDBC URL
        String regex = "((?<user>[^?]+(?=/|$))?/?(?<pass>[^?]+)?@)?jdbc:(?<protocol>[^:]+)://(?<host>[^:/]+)(:(?<port>\\d+))?/?(?<database>[^?]+)?(\\?(?<params>.*))?";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(jdbcUrl);
        if (matcher.matches()) {
            // 提取子协议、主机、端口、数据库名称
            result.put("user", matcher.group("user"));
            result.put("pass", matcher.group("pass"));
            result.put("protocol", matcher.group("protocol"));
            result.put("host", matcher.group("host"));
            result.put("port", matcher.group("port"));  // 可能为 null
            result.put("database", matcher.group("database"));

            // 解析 URL 参数（如用户名和密码）
            String params = matcher.group("params");
            if (params != null) {
                for (String param : params.split("&")) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length == 2) {
                        result.put("param:" + keyValue[0], keyValue[1]);
                    }
                }
            }

        } else {
            throw new IllegalArgumentException("Invalid JDBC URL: " + jdbcUrl);
        }

        return result;
    }

    public static Connection getJdbcConnection(String jdbcUrl) throws SQLException
    {
        Map<String, String> result = parseJdbcUrl(jdbcUrl);

        String jdbcConnectUserName = result.getOrDefault("user", "");
        String jdbcConnectPassword = result.getOrDefault("pass", "");
        Properties jdbcConnectProperties = new Properties();
        StringBuilder jdbcConnectUrl = new StringBuilder();
        jdbcConnectUrl.append("jdbc:")
                .append(result.getOrDefault("protocol", ""))
                .append("://")
                .append(result.getOrDefault("host", ""));
        if (result.get("port") != null)
        {
            jdbcConnectUrl.append(":").append(result.get("port"));
        }
        if (result.get("database") != null)
        {
            jdbcConnectUrl.append("/").append(result.get("database"));
        }
        for (String keyName : result.keySet())
        {
            if (keyName.startsWith("param:"))
            {
                jdbcConnectProperties.put(keyName.replace("param:", ""), result.get(keyName));
            }
        }
        if (!jdbcConnectUserName.isEmpty()) {
            jdbcConnectProperties.put("user", jdbcConnectUserName);
        }
        if (!jdbcConnectPassword.isEmpty()) {
            jdbcConnectProperties.put("password", jdbcConnectPassword);
        }
        return DriverManager.getConnection(jdbcConnectUrl.toString(), jdbcConnectProperties);
    }

    public static BigDecimal convertPGByteToBigDecimal(byte[] buf)
    {
        ByteBuffer buffer = ByteBuffer.wrap(buf);

        short nDigits = buffer.getShort();
        short weight = buffer.getShort();
        short sign = buffer.getShort();
        short dScale = buffer.getShort();
        int[] digits = new int[nDigits];
        for (int j = 0; j < nDigits; j++) {
            digits[j] = buffer.getShort();
        }
        BigDecimal result = BigDecimal.ZERO;
        BigDecimal base = BigDecimal.valueOf(10000);

        // 每个短整数表示4位十进制数字
        for (int j = 0; j < nDigits; j++) {
            BigDecimal digitValue = BigDecimal.valueOf(digits[j]);
            if (weight -j >= 0) {
                result = result.add(digitValue.multiply(base.pow(weight - j)));
            }
            else
            {
                result = result.add(digitValue.multiply(base.pow(weight - j, new MathContext(j+1))));
            }
        }
        if (sign == 1) {
            result = result.negate();
        }
        result = result.setScale(dScale, RoundingMode.UNNECESSARY);
        return result;
    }

    public static byte[] convertPGBigDecimalToByte(BigDecimal value)
    {
        // 解析符号
        short sign = (value.signum() < 0) ? (short)1 : (short)0x0000;
        // 计算过程中不考虑正负数
        value = value.abs();

        // 获取小数位数（scale）
        short scale = (short)value.scale();

        // 计算小数部分占用的 digit 数量
        int scaleDigits = (scale + 3) / 4;

        // 提取未缩放值和标度（dScale）
        BigDecimal scaledValue = value.setScale(scale, RoundingMode.HALF_UP);

        // 分解未缩放值为基数10000的数字列表
        BigDecimal  base = BigDecimal.valueOf(10000);
        List<Short> digitsList = new ArrayList<>();
        // 处理整数部分
        while (scaledValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal[] divRem = scaledValue.divideAndRemainder(base);
            digitsList.add(0, divRem[1].shortValue());  // 存储低位
            scaledValue = divRem[0];  // 更新剩余部分
        }
        // 处理小数部分
        BigDecimal fractionalPart = value.subtract(value.setScale(0, RoundingMode.FLOOR)).setScale(scale, RoundingMode.HALF_UP);
        for (int i = 0; i < scaleDigits; i++) {
            fractionalPart = fractionalPart.multiply(base);
            short digit = fractionalPart.setScale(0, RoundingMode.FLOOR).shortValue();
            digitsList.add(digit);
            fractionalPart = fractionalPart.subtract(BigDecimal.valueOf(digit));
        }

        short nDigits = (short)digitsList.size();
        short weight = (short)(nDigits - scaleDigits - 1);

        // 整理输出结果
        ByteBuffer buffer = ByteBuffer.allocate(2 + 2 + 2 + 2 + (nDigits * 2)).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort(nDigits);
        buffer.putShort(weight);
        buffer.putShort(sign);
        buffer.putShort(scale);
        for (short digit : digitsList) {
            buffer.putShort(digit);
        }

        return buffer.array();
    }

    // 生成 BINARY COPY 格式的列数据
    public static byte[] convertPGRowToByte(List<Object[]> data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // 写入 PostgresSQL BINARY COPY 头
        output.write(new byte[]{0x50, 0x47, 0x43, 0x4F, 0x50, 0x59, 0x0A, (byte) 0xFF, 0x0D, 0x0A, 0x00});
        output.write(Utils.int32ToBytes(0)); // Flags
        output.write(Utils.int32ToBytes(0)); // Header extension area

        // **写入数据**
        for (Object[] row : data) {
            output.write(Utils.int16ToBytes((short) row.length)); // 列数

            for (Object value : row) {
                if (value == null) {
                    output.write(Utils.int32ToBytes(-1)); // NULL
                } else if (value instanceof Short) {
                    output.write(Utils.int32ToBytes(4)); // 长度
                    output.write(Utils.int32ToBytes((Short) value));
                } else if (value instanceof Integer) {
                    output.write(Utils.int32ToBytes(4)); // 长度
                    output.write(Utils.int32ToBytes((Integer) value));
                } else if (value instanceof String) {
                    byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    output.write(Utils.int32ToBytes(strBytes.length));
                    output.write(strBytes);
                } else if (value instanceof Double) {
                    output.write(Utils.int32ToBytes(8));
                    output.write(Utils.doubleToBytes((Double) value));
                } else if (value instanceof BigDecimal) {
                    byte[] decimalBytes = convertPGBigDecimalToByte((BigDecimal) value);
                    output.write(Utils.int32ToBytes(decimalBytes.length));
                    output.write(decimalBytes);
                } else if (value instanceof Timestamp) {
                    output.write(Utils.int32ToBytes(8));
                    output.write(Utils.int64ToBytes(((Timestamp) value).toInstant().toEpochMilli() * 1000));
                } else if (value instanceof Boolean) {
                    output.write(Utils.int32ToBytes(1));
                    output.write((Boolean) value ? new byte[]{0x01} : new byte[]{0x00});
                } else if (value instanceof byte[]) {
                    output.write(Utils.int32ToBytes(((byte[]) value).length));
                    output.write((byte[])value);
                } else if (value instanceof Long) {
                    output.write(Utils.int32ToBytes(8));
                    output.write(Utils.int64ToBytes((Long) value));
                } else if (value instanceof java.sql.Date) {
                    output.write(Utils.int32ToBytes(8));
                    output.write(Utils.int64ToBytes(((java.sql.Date) value).getTime() * 1000));
                } else if (value instanceof java.util.Date) {
                    output.write(Utils.int32ToBytes(8));
                    output.write(Utils.int64ToBytes(((java.util.Date) value).getTime() * 1000));
                } else {
                    throw new IllegalArgumentException("Unsupported type: " + value.getClass().getSimpleName());
                }
            }
        }

        // 写入 COPY 结束标志
        output.write(Utils.int16ToBytes((short) -1));

        return output.toByteArray();
    }

    public static List<Object[]> convertPGByteToRow(byte[] buf) throws BufferUnderflowException
    {
        // 返回的结果中并不可能知道数据类型，需要根据目标表的数据结构进行隐式插入
        List<Object[]> ret = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(buf);

        // 记录最后一次成功读取的记录位置
        if (buffer.remaining() >= 19) {
            // 前19个字节为PG的固定格式信息，包括文件头和标志位
            buffer.position(buffer.position() + 19);
        } else {
            // 处理字节不足的情况
            throw new BufferUnderflowException();
        }
        while (true) {
            short colCount = buffer.getShort();
            if (colCount == -1) {
                // 读取到COPY的末尾，退出
                break;
            }
            Object[] rows = new Object[colCount];
            for (int i = 0; i < colCount; i++) {
                // 每个列都是一个长度和内容构建
                int colLength = buffer.getInt();
                if (colLength == -1) {
                    rows[i] = null;
                } else {
                    byte[] cellValue = new byte[colLength];
                    buffer.get(cellValue);
                    rows[i] = cellValue;
                }
            }
            ret.add(rows);
        }
        return ret;
    }
}
