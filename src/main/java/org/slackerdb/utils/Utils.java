package org.slackerdb.utils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class Utils {
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

    public static String getZoneId()
    {
        ZoneId systemZoneId = ZoneId.systemDefault();

        // 获取当前时间
        ZonedDateTime now = ZonedDateTime.now(systemZoneId);

        // 获取时区缩写（例如CET、CST）
        return now.getZone().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    public static byte[] int32ToBytes(int value) {
        return new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    public static int bytesToInt32(byte[] byteArray) {
        return (byteArray[0] & 0xFF) << 24 |
                (byteArray[1] & 0xFF) << 16 |
                (byteArray[2] & 0xFF) << 8 |
                (byteArray[3] & 0xFF);
    }

    public static byte[] int16ToBytes(short value) {
        return new byte[] {
                (byte) (value >> 8),
                (byte) value
        };
    }

    public static short bytesToInt16(byte[] byteArray) {
        return (short) ((byteArray[0] << 8) | (byteArray[1] & 0xFF));
    }

    public static byte[] int64ToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >> (8 * (7 - i)));
        }
        return bytes;
    }

    public static long bytesToInt64(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

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
}
