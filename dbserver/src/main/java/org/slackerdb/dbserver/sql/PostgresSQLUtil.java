package org.slackerdb.dbserver.sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PostgresSQLUtil {

    /**
     * PG BINARY 里 TIMESTAMP / TIMESTAMPTZ 的基准点：<b>2000-01-01 00:00:00 UTC</b>。
     *
     * <p>线上值是"自该基准点的微秒数"（int64）。这一点由 PostgreSQL 的 {@code timestamp_send}
     * 定义，PG 文档在 Binary Format 一节也把具体编码指向各类型的 {@code *send}/{@code *recv} 函数。</p>
     *
     * <p>注意这里<b>不是</b> Unix 纪元（1970-01-01）：两者相差本常量所示的微秒数。
     * 曾经本类的编码与 {@code CopyDoneRequest} 的解码都按"自 1970 的微秒"处理，
     * 两处一起偏离标准，因此用 PG 标准客户端发的 BINARY COPY 时间戳会整体偏移约 30 年。</p>
     */
    public static final long PG_EPOCH_MICROS = 946_684_800_000_000L;

    /** 纳秒 → 微秒。PG 的 TIMESTAMP 精度就是微秒，多余的纳秒按向下取整丢弃。 */
    private static final long NANOS_PER_MICRO = 1_000L;

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
        if (sign == 0x4000) {
            // PG 的 NUMERIC 用 0x4000 表示负数（NUMERIC_NEG），0x0000 表示正数/零。
            // 这里曾经写成 sign == 1，与编码端和 PG 标准都不符：结果是负数被静默读成正数。
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

        // 解析符号：PG 的 NUMERIC 用 0x4000 表示负数（NUMERIC_NEG），0x0000 表示正数/零。
        // 这里曾经写成 1，与 PG 标准不符：负数会被任何标准客户端（包括本项目自己的解码器）读成正数。
        short sign = (value.signum() < 0) ? (short)0x4000 : (short)0x0000;
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

    /** PG BINARY COPY 文件头：11字节签名 + 4字节标志位 + 4字节头部扩展区长度（共19字节）。 */
    private static final byte[] COPY_BINARY_HEADER = new byte[]{
            0x50, 0x47, 0x43, 0x4F, 0x50, 0x59, 0x0A, (byte) 0xFF, 0x0D, 0x0A, 0x00,
            0, 0, 0, 0, // Flags
            0, 0, 0, 0  // Header extension area
    };

    /** 单行 BINARY COPY 的固定开销估算值：列数(2B) + 每列长度(4B) 按 4 字节计。 */
    private static final int COPY_ROW_FIXED_BYTES = 4;

    /**
     * 预估 BINARY COPY 字节流的大小，用于一次性预分配输出缓冲区。
     * <p>
     * 仅作容量提示：字符串按 {@code length()} 近似（CJK 等多字节字符会被低估），
     * 估算偏小只会触发缓冲区的一次扩容，不影响输出内容。
     * </p>
     */
    private static int estimateCopySize(List<Object[]> data) {
        long size = COPY_BINARY_HEADER.length;
        for (Object[] row : data) {
            size += 2L + (long) row.length * COPY_ROW_FIXED_BYTES;
            for (Object value : row) {
                if (value instanceof String) {
                    size += ((String) value).length();
                } else if (value instanceof byte[]) {
                    size += ((byte[]) value).length;
                } else if (value instanceof BigDecimal) {
                    // 长度不定，取一个经验值
                    size += 32L;
                } else if (value != null) {
                    size += 8L;
                }
            }
        }
        return (int) Math.min(size, (long) Integer.MAX_VALUE - 8);
    }

    // 生成 BINARY COPY 格式的列数据
    public static byte[] convertPGRowToByte(List<Object[]> data)
    {
        // 预分配输出缓冲区
        CopyBuffer output = new CopyBuffer(estimateCopySize(data));

        // 写入 PostgresSQL BINARY COPY 头
        output.writeBytes(COPY_BINARY_HEADER, 0, COPY_BINARY_HEADER.length);

        // **写入数据**
        for (Object[] row : data) {
            output.writeShort((short) row.length); // 列数

            for (Object value : row) {
                if (value == null) {
                    output.writeInt(-1); // NULL
                } else if (value instanceof Short) {
                    output.writeInt(2); // 长度
                    output.writeShort((Short) value);
                } else if (value instanceof Integer) {
                    output.writeInt(4); // 长度
                    output.writeInt((Integer) value);
                } else if (value instanceof String) {
                    byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    output.writeInt(strBytes.length);
                    output.writeBytes(strBytes, 0, strBytes.length);
                } else if (value instanceof Double) {
                    output.writeInt(8);
                    output.writeDouble((Double) value);
                } else if (value instanceof BigDecimal) {
                    byte[] decimalBytes = convertPGBigDecimalToByte((BigDecimal) value);
                    output.writeInt(decimalBytes.length);
                    output.writeBytes(decimalBytes, 0, decimalBytes.length);
                } else if (value instanceof Timestamp) {
                    output.writeInt(8);
                    output.writeLong(toPgTimestampMicros(((Timestamp) value).toInstant()));
                } else if (value instanceof Boolean) {
                    output.writeInt(1);
                    output.writeByte((Boolean) value ? 0x01 : 0x00);
                } else if (value instanceof byte[] bytes) {
                    output.writeInt(bytes.length);
                    output.writeBytes(bytes, 0, bytes.length);
                } else if (value instanceof Long) {
                    output.writeInt(8);
                    output.writeLong((Long) value);
                } else if (value instanceof java.util.Date) {
                    // java.sql.Date / java.util.Date：同样按 PG 的 TIMESTAMP 二进制编码
                    output.writeInt(8);
                    output.writeLong(toPgTimestampMicros(((java.util.Date) value).toInstant()));
                } else {
                    throw new IllegalArgumentException("Unsupported type: " + value.getClass().getSimpleName());
                }
            }
        }

        // 写入 COPY 结束标志
        output.writeShort((short) -1);

        return output.toByteArray();
    }

    /**
     * 把某一瞬间编码成 PG BINARY 的 TIMESTAMP：<b>自 2000-01-01 00:00:00 UTC 的微秒数</b>。
     *
     * <p>用 {@link java.time.Instant} 而不是 {@code Timestamp.getTime()}：后者的毫秒数依赖 JVM 默认时区，
     * 会让同一时刻在不同时区的机器上编出不同字节；{@code Instant} 是与时区无关的绝对时刻。</p>
     */
    public static long toPgTimestampMicros(java.time.Instant instant) {
        return instant.getEpochSecond() * 1_000_000L
                + instant.getNano() / NANOS_PER_MICRO
                - PG_EPOCH_MICROS;
    }

    /**
     * {@link #toPgTimestampMicros} 的逆运算，得到的永远是 <b>UTC</b> 墙钟时间。
     *
     * <p>PG 的 TIMESTAMP 不带时区，其二进制值就是 UTC 下的微秒数，因此这里固定按 UTC 解读。</p>
     *
     * <p>注意：BINARY COPY 的解码路径（{@code CopyDoneRequest}）出于性能考虑没有用这个方法，
     * 而是直接 {@code appendEpochMicros(micros + PG_EPOCH_MICROS)} 省掉 {@code LocalDateTime}
     * 的构造；两者语义一致，这个方法保留给需要墙钟时间的调用方。</p>
     */
    public static LocalDateTime pgTimestampMicrosToLocalDateTime(long micros) {
        return LocalDateTime.of(2000, 1, 1, 0, 0, 0)
                .plus(Duration.ofNanos(micros * NANOS_PER_MICRO));
    }

    /**
     * 把 PG BINARY COPY 字节流解析成行数据。
     * <p>
     * 数据流不完整时(缺少固定头、行数据被截断、缺少行尾的 -1 结束标志等)抛出带明确说明的
     * {@link IllegalArgumentException}，便于上层回给客户端可读的错误信息，
     * 而不是让裸的 {@link BufferUnderflowException} 逃逸出去。
     * </p>
     */
    public static List<Object[]> convertPGByteToRow(byte[] buf)
    {
        // 返回的结果中并不可能知道数据类型，需要根据目标表的数据结构进行隐式插入
        List<Object[]> ret = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(buf);

        // 前19个字节为PG的固定格式信息，包括文件头和标志位
        if (buffer.remaining() < 19) {
            throw new IllegalArgumentException("invalid binary COPY data: stream truncated in header ("
                    + buffer.remaining() + " bytes available, 19 bytes expected)");
        }
        buffer.position(buffer.position() + 19);

        long rowIndex = 0;
        while (true) {
            // 行头：2字节列数
            if (buffer.remaining() < 2) {
                throw new IllegalArgumentException("invalid binary COPY data: stream truncated at row " + rowIndex
                        + " (missing column count, " + buffer.remaining() + " bytes left)");
            }
            short colCount = buffer.getShort();
            if (colCount == -1) {
                // 读取到COPY的末尾，退出
                break;
            }
            Object[] rows = new Object[colCount];
            for (int i = 0; i < colCount; i++) {
                // 每个列都是一个长度和内容构建
                if (buffer.remaining() < 4) {
                    throw new IllegalArgumentException("invalid binary COPY data: stream truncated at row " + rowIndex
                            + " column " + i + " (missing column length, " + buffer.remaining() + " bytes left)");
                }
                int colLength = buffer.getInt();
                if (colLength == -1) {
                    rows[i] = null;
                } else {
                    if (colLength < 0 || buffer.remaining() < colLength) {
                        throw new IllegalArgumentException("invalid binary COPY data: stream truncated at row " + rowIndex
                                + " column " + i + " (need " + colLength + " bytes, but only "
                                + buffer.remaining() + " bytes left)");
                    }
                    byte[] cellValue = new byte[colLength];
                    buffer.get(cellValue);
                    rows[i] = cellValue;
                }
            }
            ret.add(rows);
            rowIndex++;
        }
        return ret;
    }

    /**
     * BINARY COPY 专用的轻量字节缓冲。
     * <p>
     * 与 {@code ByteArrayOutputStream} 的区别：可一次性预分配容量，并按大端序直接写入基本类型，
     * 从而避免逐列创建临时 {@code byte[]}（原实现每列至少 {@code new} 一个小数组）
     * 以及反复整块扩容拷贝。输出的字节序列与原先的编码方式完全一致。
     * </p>
     */
    private static final class CopyBuffer {
        private byte[] buf;
        private int pos;

        CopyBuffer(int initialCapacity) {
            this.buf = new byte[Math.max(64, initialCapacity)];
        }

        private void ensure(int extra) {
            int need = pos + extra;
            if (need > buf.length) {
                int newCapacity = buf.length;
                while (newCapacity < need) {
                    newCapacity = newCapacity << 1;
                    if (newCapacity <= 0) {
                        // 溢出保护：直接取所需容量
                        newCapacity = need;
                        break;
                    }
                }
                buf = Arrays.copyOf(buf, newCapacity);
            }
        }

        void writeByte(int value) {
            ensure(1);
            buf[pos++] = (byte) value;
        }

        void writeShort(short value) {
            ensure(2);
            buf[pos++] = (byte) (value >>> 8);
            buf[pos++] = (byte) value;
        }

        void writeInt(int value) {
            ensure(4);
            buf[pos++] = (byte) (value >>> 24);
            buf[pos++] = (byte) (value >>> 16);
            buf[pos++] = (byte) (value >>> 8);
            buf[pos++] = (byte) value;
        }

        void writeLong(long value) {
            ensure(8);
            buf[pos++] = (byte) (value >>> 56);
            buf[pos++] = (byte) (value >>> 48);
            buf[pos++] = (byte) (value >>> 40);
            buf[pos++] = (byte) (value >>> 32);
            buf[pos++] = (byte) (value >>> 24);
            buf[pos++] = (byte) (value >>> 16);
            buf[pos++] = (byte) (value >>> 8);
            buf[pos++] = (byte) value;
        }

        void writeDouble(double value) {
            writeLong(Double.doubleToLongBits(value));
        }

        void writeBytes(byte[] src, int off, int len) {
            ensure(len);
            System.arraycopy(src, off, buf, pos, len);
            pos += len;
        }

        byte[] toByteArray() {
            return pos == buf.length ? buf : Arrays.copyOf(buf, pos);
        }
    }
}
