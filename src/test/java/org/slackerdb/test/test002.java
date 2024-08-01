package org.slackerdb.test;

import org.slackerdb.utils.Sleeper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;

public class test002 {

    public static void main(String[] args) throws SQLException, ClassNotFoundException {
        BigDecimal decimal = new BigDecimal("1234.567");

        // Get integer part and decimal part
        BigInteger integerPart = decimal.toBigInteger();
        BigDecimal decimalPart = decimal.subtract(new BigDecimal(integerPart));
        int scale = decimal.scale();

        // Convert to byte arrays
        byte[] integerPartBytes = integerPart.toByteArray();
        byte[] decimalPartBytes = decimalPart.movePointRight(scale).toBigInteger().toByteArray();

        // Calculate the number of digits
        int numberOfDigits = (integerPartBytes.length + decimalPartBytes.length + 3) / 4; // each int16 holds 4 digits
        int numberWeight = integerPartBytes.length / 4; // weight in 4-digit blocks
        int numberSign = decimal.signum() >= 0 ? 0 : 16384;

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + integerPartBytes.length + decimalPartBytes.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Digits count
        buffer.putShort((short) numberOfDigits);
        // Weight
        buffer.putShort((short) numberWeight);
        // Sign
        buffer.putShort((short) numberSign);
        // Scale
        buffer.putShort((short) scale);

        // Add digits array (converted to 4-digit int16 values)
        buffer.put(integerPartBytes);
        buffer.put(decimalPartBytes);

        byte[] result = buffer.array();

        // Print the result in hex format for verification
        for (byte b : result) {
            System.out.printf("%02X ", b);
        }
    }
}

