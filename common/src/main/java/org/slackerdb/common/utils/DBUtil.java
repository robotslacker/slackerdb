package org.slackerdb.common.utils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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

}
