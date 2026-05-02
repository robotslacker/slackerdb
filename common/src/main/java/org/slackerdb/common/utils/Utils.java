package org.slackerdb.common.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用工具类。
 * 提供各种常用的工具方法，包括字节数组处理、数据类型转换、字符串格式化、本地化支持等。
 *
 * <p>该类包含以下功能组：</p>
 * <ul>
 *   <li>字节数组与十六进制字符串的相互转换</li>
 *   <li>基本数据类型与字节数组的相互转换（int16、int32、int64、float、double）</li>
 *   <li>字节数组分割操作</li>
 *   <li>本地化（Locale）处理</li>
 *   <li>资源包消息格式化</li>
 *   <li>字节大小和时长的人类可读格式化</li>
 *   <li>时区信息获取</li>
 * </ul>
 *
 * <p>注意：所有方法都是静态的，可以直接调用。</p>
 */
public class Utils {
    /**
     * 将字节数组转换为十六进制字符串表示。
     *
     * <p>该方法会忽略字节数组末尾的零值（0x00），只转换到最后一个非零字节为止。
     * 每个字节以两位十六进制数表示，字节之间用空格分隔。</p>
     *
     * <p>示例：字节数组 {0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x00, 0x00} 转换为 "48 65 6C 6C 6F"</p>
     *
     * @param bytes 要转换的字节数组
     * @return 十六进制字符串，每个字节以两位十六进制数表示，用空格分隔
     */
    public static String bytesToHex(byte[] bytes) {
        int length = bytes.length;
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] != 0) {
                length = i + 1;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    /**
     * 将字节数组转换为格式化的十六进制和ASCII表示列表。
     *
     * <p>该方法将字节数组按每行20字节的格式转换为可读的十六进制转储格式，每行包含：</p>
     * <ul>
     *   <li>地址范围（十六进制，如 "0000-0014"）</li>
     *   <li>十六进制表示（每个字节两位十六进制数，空格分隔）</li>
     *   <li>ASCII表示（可打印字符显示原字符，0x00显示为空格，其他显示为'?'）</li>
     * </ul>
     *
     * <p>与{@link #bytesToHex(byte[])}类似，该方法也会忽略末尾的零值字节。</p>
     *
     * <p>示例输出行： "0000-0014 48 65 6C 6C 6F 20 57 6F 72 6C 64 00 00 00 00 00 00 00 00 00 Hello World     "</p>
     *
     * @param byteArray 要转换的字节数组
     * @return 格式化后的十六进制转储行列表，每行包含地址、十六进制和ASCII表示
     */
    public static List<String> bytesToHexList(byte[] byteArray) {
        List<String> result = new ArrayList<>();

        int length = byteArray.length;
        for (int i = byteArray.length - 1; i >= 0; i--) {
            if (byteArray[i] != 0) {
                length = i + 1;
                break;
            }
        }
        int bytesPerLine = 20;
        StringBuilder hexBuilder = new StringBuilder();
        StringBuilder asciiBuilder = new StringBuilder();

        for (int i = 0; i < length; i++) {
            byte b = byteArray[i];

            // Append the hex representation
            hexBuilder.append(String.format("%02X ", b));

            // Append the ASCII representation
            if (b >= 32 && b <= 126) {
                asciiBuilder.append((char) b);
            }
            else if (b == 0)
            {
                asciiBuilder.append(" ");
            }
            else {
                asciiBuilder.append('?');
            }
            // Print line if we have reached the limit or at the end of the array
            if (((i + 1) % bytesPerLine) == 0 || i == (length - 1)) {
                // Calculate the starting position
                int startPosition = i / bytesPerLine * bytesPerLine;
                String address = String.format("%04X-%04X", startPosition, startPosition + bytesPerLine);

                // Pad hexBuilder if necessary
                while (hexBuilder.length() < bytesPerLine * 3) {
                    hexBuilder.append("   ");
                }
                result.add(address + " " + hexBuilder + " " + asciiBuilder);

                // Reset builders for next line
                hexBuilder.setLength(0);
                asciiBuilder.setLength(0);
            }
        }
        return result;
    }

    /**
     * 获取当前系统的时区缩写。
     *
     * <p>该方法返回当前系统时区的短名称（如CET、CST、EST等），使用英文Locale。</p>
     *
     * <p>实现原理：获取系统默认时区，获取当前时间在该时区下的表示，然后使用时区的短显示名称。</p>
     *
     * @return 当前时区的短名称（英文缩写）
     */
    public static String getZoneId()
    {
        ZoneId systemZoneId = ZoneId.systemDefault();

        // 获取当前时间
        ZonedDateTime now = ZonedDateTime.now(systemZoneId);

        // 获取时区缩写（例如CET、CST）
        return now.getZone().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /**
     * 将32位整数转换为4字节的大端序字节数组。
     *
     * <p>转换采用大端序（Big Endian）：最高有效字节在前（索引0）。</p>
     *
     * <p>示例：值 0x12345678 转换为字节数组 {0x12, 0x34, 0x56, 0x78}</p>
     *
     * @param value 要转换的32位整数
     * @return 4字节的大端序字节数组
     * @see #bytesToInt32(byte[])
     */
    public static byte[] int32ToBytes(int value) {
        return new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 将4字节的大端序字节数组转换为32位整数。
     *
     * <p>该方法假定字节数组采用大端序（Big Endian）：索引0为最高有效字节。
     * 如果字节数组长度超过4字节，只使用前4个字节。</p>
     *
     * <p>示例：字节数组 {0x12, 0x34, 0x56, 0x78} 转换为整数 0x12345678</p>
     *
     * @param byteArray 4字节的大端序字节数组
     * @return 转换后的32位整数
     * @see #int32ToBytes(int)
     */
    public static int bytesToInt32(byte[] byteArray) {
        return (byteArray[0] & 0xFF) << 24 |
                (byteArray[1] & 0xFF) << 16 |
                (byteArray[2] & 0xFF) << 8 |
                (byteArray[3] & 0xFF);
    }

    /**
     * 将16位短整数转换为2字节的大端序字节数组。
     *
     * <p>转换采用大端序（Big Endian）：最高有效字节在前（索引0）。</p>
     *
     * <p>示例：值 0x1234 转换为字节数组 {0x12, 0x34}</p>
     *
     * @param value 要转换的16位短整数
     * @return 2字节的大端序字节数组
     * @see #bytesToInt16(byte[])
     */
    public static byte[] int16ToBytes(short value) {
        return new byte[] {
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 将2字节的大端序字节数组转换为16位短整数。
     *
     * <p>该方法假定字节数组采用大端序（Big Endian）：索引0为最高有效字节。
     * 如果字节数组长度超过2字节，只使用前2个字节。</p>
     *
     * <p>示例：字节数组 {0x12, 0x34} 转换为短整数 0x1234</p>
     *
     * @param byteArray 2字节的大端序字节数组
     * @return 转换后的16位短整数
     * @see #int16ToBytes(short)
     */
    public static short bytesToInt16(byte[] byteArray) {
        return (short) ((byteArray[0] << 8) | (byteArray[1] & 0xFF));
    }

    /**
     * 将64位长整数转换为8字节的大端序字节数组。
     *
     * <p>转换采用大端序（Big Endian）：最高有效字节在前（索引0）。</p>
     *
     * <p>示例：值 0x1234567890ABCDEF 转换为字节数组 {0x12, 0x34, 0x56, 0x78, 0x90, 0xAB, 0xCD, 0xEF}</p>
     *
     * @param value 要转换的64位长整数
     * @return 8字节的大端序字节数组
     * @see #bytesToInt64(byte[])
     */
    public static byte[] int64ToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >> (8 * (7 - i)));
        }
        return bytes;
    }

    /**
     * 将字节数组转换为64位长整数。
     *
     * <p>该方法假定字节数组采用大端序（Big Endian）：索引0为最高有效字节。
     * 如果字节数组长度超过8字节，只使用前8个字节；如果不足8字节，从最高有效位开始填充。</p>
     *
     * <p>示例：字节数组 {0x12, 0x34, 0x56, 0x78, 0x90, 0xAB, 0xCD, 0xEF} 转换为长整数 0x1234567890ABCDEF</p>
     *
     * @param bytes 字节数组（最多8字节）
     * @return 转换后的64位长整数
     * @see #int64ToBytes(long)
     */
    public static long bytesToInt64(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

    /**
     * 将字节数组转换为双精度浮点数。
     *
     * <p>该方法使用{@link ByteBuffer}将字节数组转换为双精度浮点数，采用大端序（Big Endian）。
     * 只读取字节数组的前8个字节，避免数组越界异常。</p>
     *
     * @param bytes 字节数组（至少8字节）
     * @return 转换后的双精度浮点数
     * @see #doubleToBytes(double)
     */
    public static double byteToDouble(byte[] bytes)
    {
        // 确保只读取前8字节，避免越界
        return ByteBuffer.wrap(bytes, 0, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .getDouble();
    }

    /**
     * 将双精度浮点数转换为字节数组。
     *
     * <p>该方法使用{@link ByteBuffer}将双精度浮点数转换为8字节数组，采用大端序（Big Endian）。</p>
     *
     * @param value 要转换的双精度浮点数
     * @return 8字节的大端序字节数组
     * @see #byteToDouble(byte[])
     */
    public static byte[] doubleToBytes(double value)
    {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array();
    }

    /**
     * 将字节数组转换为单精度浮点数。
     *
     * <p>该方法使用{@link ByteBuffer}将字节数组转换为单精度浮点数，采用大端序（Big Endian）。
     * 只读取字节数组的前4个字节，避免数组越界异常。</p>
     *
     * <p>注意：方法注释提到"前8字节"，但实际实现读取4字节，可能存在注释错误。</p>
     *
     * @param bytes 字节数组（至少4字节）
     * @return 转换后的单精度浮点数
     * @see #floatToBytes(float)
     */
    public static float byteToFloat(byte[] bytes)
    {
        // 确保只读取前8字节，避免越界
        return ByteBuffer.wrap(bytes, 0, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .getFloat();
    }

    /**
     * 将单精度浮点数转换为字节数组。
     *
     * <p>该方法使用{@link ByteBuffer}将单精度浮点数转换为4字节数组，采用大端序（Big Endian）。</p>
     *
     * @param value 要转换的单精度浮点数
     * @return 4字节的大端序字节数组
     * @see #byteToFloat(byte[])
     */
    public static byte[] floatToBytes(float value)
    {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value).array();
    }

    /**
     * 使用指定分隔符将字节数组分割为多个子数组。
     *
     * <p>该方法遍历字节数组，每当遇到指定的分隔符字节时，就将之前的部分作为一个子数组。
     * 数组末尾如果没有分隔符，剩余部分也会作为一个子数组。</p>
     *
     * <p>示例：输入数组 {0x48, 0x2C, 0x65, 0x2C, 0x6C}，分隔符 0x2C (逗号)，
     * 返回 {{0x48}, {0x65}, {0x6C}}</p>
     *
     * @param input 要分割的字节数组
     * @param delimiter 分隔符字节
     * @return 分割后的字节数组的数组
     */
    public static byte[][] splitByteArray(byte[] input, byte delimiter) {
        List<byte[]> parts = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < input.length; i++) {
            if (input[i] == delimiter) {
                byte[] part = Arrays.copyOfRange(input, start, i);
                parts.add(part);
                start = i + 1;
            }
        }

        // Add the last part if there's no delimiter at the end of the input array
        if (start < input.length) {
            byte[] part = Arrays.copyOfRange(input, start, input.length);
            parts.add(part);
        }

        return parts.toArray(new byte[parts.size()][]);
    }

    /**
     * 将字符串转换为Locale对象。
     *
     * <p>支持标准的Locale字符串格式：</p>
     * <ul>
     *   <li>2字符：语言代码（如 "en"）</li>
     *   <li>5字符：语言代码 + 国家代码（如 "en_US"）</li>
     *   <li>7字符或更长：语言代码 + 国家代码 + 变体（如 "en_US_POSIX"）</li>
     * </ul>
     *
     * <p>格式要求：</p>
     * <ol>
     *   <li>语言代码：2个小写字母</li>
     *   <li>国家代码（可选）：2个大写字母</li>
     *   <li>变体（可选）：任意字符串</li>
     *   <li>各部分之间用下划线分隔</li>
     * </ol>
     *
     * @param str Locale字符串
     * @return 对应的Locale对象，如果输入为null则返回null
     * @throws IllegalArgumentException 如果字符串格式无效
     */
    public static Locale toLocale(String str) throws IllegalArgumentException {
        if (str == null) {
            return null;
        }
        int len = str.length();
        if (len != 2 && len != 5 && len < 7) {
            throw new IllegalArgumentException("Invalid locale format: " + str);
        }
        char ch0 = str.charAt(0);
        char ch1 = str.charAt(1);
        if (ch0 < 'a' || ch0 > 'z' || ch1 < 'a' || ch1 > 'z') {
            throw new IllegalArgumentException("Invalid locale format: " + str);
        }
        if (len == 2) {
            return new Locale(str, "");
        } else {
            if (str.charAt(2) != '_') {
                throw new IllegalArgumentException("Invalid locale format: " + str);
            }
            char ch3 = str.charAt(3);
            if (ch3 == '_') {
                return new Locale(str.substring(0, 2), "", str.substring(4));
            }
            char ch4 = str.charAt(4);
            if (ch3 < 'A' || ch3 > 'Z' || ch4 < 'A' || ch4 > 'Z') {
                throw new IllegalArgumentException("Invalid locale format: " + str);
            }
            if (len == 5) {
                return new Locale(str.substring(0, 2), str.substring(3, 5));
            } else {
                if (str.charAt(5) != '_') {
                    throw new IllegalArgumentException("Invalid locale format: " + str);
                }
                return new Locale(str.substring(0, 2), str.substring(3, 5), str.substring(6));
            }
        }
    }

    /**
     * 从默认资源包获取格式化消息。
     *
     * <p>该方法使用默认的"message"资源包和系统默认的Locale，调用重载方法获取格式化消息。</p>
     *
     * @param code 消息代码
     * @param contents 消息参数（可变参数）
     * @return 格式化后的消息字符串
     * @see #getMessage(ResourceBundle, String, Object...)
     */
    public static String getMessage(String code, Object... contents) {
        return getMessage(ResourceBundle.getBundle("message", Locale.getDefault()), code, contents);
    }

    /**
     * 从指定的资源包获取格式化消息。
     *
     * <p>该方法根据消息代码从资源包获取消息模式，然后使用{@link MessageFormat}进行格式化。
     * 如果消息代码在资源包中不存在，则返回降级格式："MSG-[code]:[param1]|[param2]|..."。</p>
     *
     * @param resourceBundle 资源包
     * @param code 消息代码
     * @param contents 消息参数（可变参数）
     * @return 格式化后的消息字符串
     */
    public static String getMessage(ResourceBundle resourceBundle, String code, Object... contents) {
        StringBuilder content;
        String pattern;
        try {
            pattern = resourceBundle.getString(code);
            content = new StringBuilder(MessageFormat.format(pattern, contents));
        } catch (MissingResourceException me)
        {
            content = new StringBuilder("MSG-" + code + ":");
            for (Object object : contents) {
                if (object != null) {
                    content.append(object).append("|");
                }
                else {
                    content.append("null|");
                }
            }
        }
        return content.toString();
    }

    /**
     * 将字节数格式化为人类可读的字符串。
     *
     * <p>根据字节大小自动选择合适的单位（B、KB、MB、GB、TB、PB、EB），
     * 使用1024进制（二进制前缀）。</p>
     *
     * <p>示例：</p>
     * <ul>
     *   <li>500 → "500B"</li>
     *   <li>2048 → "2.0KB"</li>
     *   <li>1572864 → "1.5MB"</li>
     * </ul>
     *
     * @param bytes 字节数
     * @return 格式化后的字符串，包含单位和一位小数
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        int unit = 1024;
        String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        return String.format("%.1f%s", bytes / Math.pow(unit, exp), units[exp - 1]);
    }

    /**
     * 时间单位到秒数的映射表。
     * 支持的时间单位：second(s)、minute(s)、hour(s)。
     */
    private static final Map<String, Integer> TIME_UNITS = new HashMap<>();
    static {
        TIME_UNITS.put("second", 1);
        TIME_UNITS.put("seconds", 1);
        TIME_UNITS.put("minute", 60);
        TIME_UNITS.put("minutes", 60);
        TIME_UNITS.put("hour", 3600);
        TIME_UNITS.put("hours", 3600);
    }

    /**
     * 将人类可读的时长字符串转换为秒数。
     *
     * <p>解析包含数字和时间单位的字符串，支持单位：second(s)、minute(s)、hour(s)。
     * 字符串可以包含多个部分，如"3 hours 30 minutes"。</p>
     *
     * <p>示例：</p>
     * <ul>
     *   <li>"2 hours" → 7200</li>
     *   <li>"1 hour 30 minutes" → 5400</li>
     *   <li>"45minutes" → 2700</li>
     * </ul>
     *
     * <p>注意：不支持的单位会被忽略。</p>
     *
     * @param durationStr 时长字符串
     * @return 总秒数
     * @see #TIME_UNITS
     */
    public static long convertDurationStrToSeconds(String durationStr)
    {
        long    totalSeconds = 0;
        // 匹配数字和时间单位，如"3 hours"或"5minutes"
        Pattern pattern = Pattern.compile("(\\d+)\\s*(\\w+)");
        Matcher matcher = pattern.matcher(durationStr.toLowerCase());

        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            if (TIME_UNITS.containsKey(unit)) {
                totalSeconds += (long) value * TIME_UNITS.get(unit);
            }
        }
        return totalSeconds;
    }

    /**
     * 将秒数转换为人类可读的时长字符串。
     *
     * <p>将秒数分解为小时、分钟和秒，只显示非零部分。
     * 如果总秒数为0，显示"0 seconds"。</p>
     *
     * <p>示例：</p>
     * <ul>
     *   <li>7200 → "2 hours"</li>
     *   <li>3665 → "1 hour 1 minute 5 seconds"</li>
     *   <li>30 → "30 seconds"</li>
     *   <li>0 → "0 seconds"</li>
     * </ul>
     *
     * @param totalSeconds 总秒数
     * @return 人类可读的时长字符串，如果输入负数则返回"Invalid input"
     * @see #convertDurationStrToSeconds(String)
     */
    public static String convertSecondsToHumanTime(long totalSeconds) {
        if (totalSeconds < 0) {
            return "Invalid input";
        }

        long  hours = totalSeconds / 3600;
        long  remaining = totalSeconds % 3600;
        long  minutes = remaining / 60;
        long  seconds = remaining % 60;

        StringBuilder result = new StringBuilder();
        if (hours > 0) {
            result.append(hours).append(hours == 1 ? " hour " : " hours ");
        }
        if (minutes > 0) {
            result.append(minutes).append(minutes == 1 ? " minute " : " minutes ");
        }
        if (seconds > 0 || (hours == 0 && minutes == 0)) {
            result.append(seconds).append(seconds == 1 ? " second" : " seconds");
        }

        return result.toString().trim();
    }
}
