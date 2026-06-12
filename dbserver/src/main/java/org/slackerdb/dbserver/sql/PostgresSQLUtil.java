package org.slackerdb.dbserver.sql;

import org.slackerdb.common.utils.Utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class PostgresSQLUtil {
    public static BigDecimal convertPGByteToBigDecimal(byte[] buf)
    {
        if (buf == null) {
            throw new IllegalArgumentException("convertPGByteToBigDecimal: buf must not be null");
        }
        if (buf.length < 8) {
            throw new IllegalArgumentException("convertPGByteToBigDecimal: buf length must be at least 8, got " + buf.length);
        }
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
            int exp = weight - j;
            if (exp >= 0) {
                result = result.add(digitValue.multiply(base.pow(exp)));
            }
            else
            {
                // 负指数：使用 BigDecimal 除法计算 10000^exp 的倒数，保证精度
                // dScale 可能为负数（当 scale < exp*4 时），确保精度参数至少为 0
                int precisionScale = Math.max(dScale, (short)0) + 16;
                result = result.add(digitValue.divide(base.pow(-exp), precisionScale, RoundingMode.HALF_UP));
            }
        }
        if (sign == 1) {
            result = result.negate();
        }
        // dScale 可能为负数（PG numeric 允许 scale 为负），此时 setScale 不接受负数参数
        // 使用 max(0, dScale) 确保 scale 非负
        int finalScale = Math.max(dScale, 0);
        result = result.setScale(finalScale, RoundingMode.HALF_UP);
        return result;
    }

    public static byte[] convertPGBigDecimalToByte(BigDecimal value)
    {
        if (value == null) {
            throw new IllegalArgumentException("convertPGBigDecimalToByte: value must not be null");
        }
        // 处理零值：PG 数值类型零值的表示为 nDigits=0, weight=0, sign=0, scale=0
        if (value.signum() == 0) {
            ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            buffer.putShort((short)0); // nDigits
            buffer.putShort((short)0); // weight
            buffer.putShort((short)0); // sign
            buffer.putShort((short)0); // scale
            return buffer.array();
        }

        // 解析符号
        short sign = (value.signum() < 0) ? (short)1 : (short)0x0000;
        // 计算过程中不考虑正负数
        value = value.abs();

        // 获取小数位数（scale）
        short scale = (short)value.scale();

        // 计算小数部分占用的 digit 数量
        int scaleDigits = (scale + 3) / 4;

        // 提取未缩放值（unscaledValue = value * 10^scale）
        BigDecimal unscaledValue = value.setScale(scale, RoundingMode.HALF_UP);

        // 分解未缩放值为基数10000的数字列表
        BigDecimal  base = BigDecimal.valueOf(10000);
        List<Short> digitsList = new ArrayList<>();
        // 处理整数部分
        while (unscaledValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal[] divRem = unscaledValue.divideAndRemainder(base);
            digitsList.add(0, divRem[1].shortValue());  // 存储低位
            unscaledValue = divRem[0];  // 更新剩余部分
        }
        // 处理小数部分：使用 remainder 避免双重舍入
        BigDecimal fractionalPart = value.remainder(BigDecimal.ONE).abs().setScale(scale, RoundingMode.HALF_UP);
        if (fractionalPart.signum() != 0) {
            for (int i = 0; i < scaleDigits; i++) {
                fractionalPart = fractionalPart.multiply(base);
                short digit = fractionalPart.setScale(0, RoundingMode.FLOOR).shortValue();
                digitsList.add(digit);
                fractionalPart = fractionalPart.subtract(BigDecimal.valueOf(digit));
            }
        }

        short nDigits = (short)digitsList.size();
        // 使用 BigDecimal 的 precision() 和 scale() 计算 weight，避免 doubleValue() 精度损失
        // weight = 整数部分位数 / 4（向上取整）- 1
        // 整数部分位数 = precision - scale
        short weight;
        int intDigits = value.precision() - value.scale();
        if (intDigits <= 0) {
            // 纯小数（如 0.00123），整数部分为0
            // 使用 BigDecimal.stripTrailingZeros 避免 double 精度问题
            BigDecimal absVal = value.abs().stripTrailingZeros();
            int prec = absVal.precision();
            int sc = absVal.scale();
            int intPartDigits = prec - sc;
            if (intPartDigits <= 0) {
                // 纯小数：weight = -ceil(sc / 4.0)
                weight = (short)(-(sc + 3) / 4);
            } else {
                weight = (short)((intPartDigits + 3) / 4 - 1);
            }
        } else {
            weight = (short)((intDigits + 3) / 4 - 1);
        }

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
    public static byte[] convertPGRowToByte(List<Object[]> data) throws IOException {
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
                    output.write(Utils.int32ToBytes(2)); // 长度
                    output.write(Utils.int16ToBytes((Short) value));
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
                    output.write(Utils.int64ToBytes(((Timestamp) value).getTime() * 1000));
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
