/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;

/**
 *
 * @author Brett Okken
 */
public class BigDecimalByteConverterTest {
  public static Iterable<Object[]> data() {
    final Collection<Object[]> numbers = new ArrayList<>();
    numbers.add(new Object[]{new BigDecimal("0.1")});
    numbers.add(new Object[]{new BigDecimal("0.10")});
    numbers.add(new Object[]{new BigDecimal("0.01")});
    numbers.add(new Object[]{new BigDecimal("0.001")});
    numbers.add(new Object[]{new BigDecimal("0.0001")});
    numbers.add(new Object[]{new BigDecimal("0.00001")});
    numbers.add(new Object[]{new BigDecimal("1.0")});
    numbers.add(new Object[]{new BigDecimal("0.000000000000000000000000000000000000000000000000000")});
    numbers.add(new Object[]{new BigDecimal("0.100000000000000000000000000000000000000000000009900")});
    numbers.add(new Object[]{new BigDecimal("-1.0")});
    numbers.add(new Object[]{new BigDecimal("-1")});
    numbers.add(new Object[]{new BigDecimal("1.2")});
    numbers.add(new Object[]{new BigDecimal("-2.05")});
    numbers.add(new Object[]{new BigDecimal("0.000000000000000000000000000990")});
    numbers.add(new Object[]{new BigDecimal("-0.000000000000000000000000000990")});
    numbers.add(new Object[]{new BigDecimal("10.0000000000099")});
    numbers.add(new Object[]{new BigDecimal(".10000000000000")});
    numbers.add(new Object[]{new BigDecimal("1.10000000000000")});
    numbers.add(new Object[]{new BigDecimal("99999.2")});
    numbers.add(new Object[]{new BigDecimal("99999")});
    numbers.add(new Object[]{new BigDecimal("-99999.2")});
    numbers.add(new Object[]{new BigDecimal("-99999")});
    numbers.add(new Object[]{new BigDecimal("2147483647")});
    numbers.add(new Object[]{new BigDecimal("-2147483648")});
    numbers.add(new Object[]{new BigDecimal("2147483648")});
    numbers.add(new Object[]{new BigDecimal("-2147483649")});
    numbers.add(new Object[]{new BigDecimal("9223372036854775807")});
    numbers.add(new Object[]{new BigDecimal("-9223372036854775808")});
    numbers.add(new Object[]{new BigDecimal("9223372036854775808")});
    numbers.add(new Object[]{new BigDecimal("-9223372036854775809")});
    numbers.add(new Object[]{new BigDecimal("10223372036850000000")});
    numbers.add(new Object[]{new BigDecimal("19223372036854775807")});
    numbers.add(new Object[]{new BigDecimal("19223372036854775807.300")});
    numbers.add(new Object[]{new BigDecimal("-19223372036854775807.300")});
    numbers.add(new Object[]{new BigDecimal(BigInteger.valueOf(1234567890987654321L), -1)});
    numbers.add(new Object[]{new BigDecimal(BigInteger.valueOf(1234567890987654321L), -5)});
    numbers.add(new Object[]{new BigDecimal(BigInteger.valueOf(-1234567890987654321L), -3)});
    numbers.add(new Object[]{new BigDecimal(BigInteger.valueOf(6), -8)});
    numbers.add(new Object[]{new BigDecimal("30000")});
    numbers.add(new Object[]{new BigDecimal("40000").setScale(15)});
    numbers.add(new Object[]{new BigDecimal("20000.000000000000000000")});
    numbers.add(new Object[]{new BigDecimal("9990000").setScale(8)});
    numbers.add(new Object[]{new BigDecimal("1000000").setScale(31)});
    numbers.add(new Object[]{new BigDecimal("10000000000000000000000000000000000000").setScale(14)});
    numbers.add(new Object[]{new BigDecimal("90000000000000000000000000000000000000")});
    return numbers;
  }

  @MethodSource("data")
  @ParameterizedTest(name = "number = {0,number,#,###.##################################################}")
  void binary(BigDecimal number) {
    testBinaryConversion(number);
  }

  @Test
  void bigDecimal10_pow_131072_minus_1() {
    testBinaryConversion(
        new BigDecimal(BigInteger.TEN.pow(131072).subtract(BigInteger.ONE))
    );
  }

  static void testBinaryConversion(BigDecimal number) {
    final byte[] bytes = ByteConverter.numeric(number);
    final BigDecimal actual = (BigDecimal) ByteConverter.numeric(bytes);
    if (number.scale() >= 0) {
      assertEquals(number, actual);
    } else {
      assertEquals(number.toPlainString(), actual.toPlainString());
    }
  }

  @Test
  void testSpecialNumericValues() {
    Number nan = ByteConverter.numeric(new byte[]{0, 0, 0, 0, (byte) 0xC0, 0, 0, 0});
    assertEquals(Double.NaN, nan);
    Number pinf = ByteConverter.numeric(new byte[]{0, 0, 0, 0, (byte) 0xD0, 0, 0, 0});
    assertEquals(Double.POSITIVE_INFINITY, pinf);
    Number ninf = ByteConverter.numeric(new byte[]{0, 0, 0, 0, (byte) 0xF0, 0, 0, 0});
    assertEquals(Double.NEGATIVE_INFINITY, ninf);
  }
}
