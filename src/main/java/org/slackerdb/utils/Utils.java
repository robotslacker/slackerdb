package org.slackerdb.utils;

import org.slackerdb.logger.AppLogger;

import java.util.ArrayList;
import java.util.List;

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
}
