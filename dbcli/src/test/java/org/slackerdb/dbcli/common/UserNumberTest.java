package org.slackerdb.dbcli.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数值参数的唯一解析入口（{@link UserNumber}）：**一种句式 + 一组规则**。
 *
 * <p>以前同一个"写错了"有三种说法（{@code expected an integer} / {@code must be a number of seconds} /
 * {@code Invalid sleep time [...]}），而且校验（正数/有限/范围）散落各处。这里的断言同时锁住
 * 文案与校验，任何一处退化都会显形。</p>
 */
class UserNumberTest {

    @Test
    @DisplayName("整数：统一句式 Invalid <name> [<value>]: expected <rule>.")
    void integerMessage() {
        UserNumber.InvalidNumberException invalid = assertThrows(UserNumber.InvalidNumberException.class,
                () -> UserNumber.integer("exit code", "99999999999"));
        assertEquals("Invalid exit code [99999999999]: expected an integer.", invalid.getMessage());
        assertEquals("exit code", invalid.label());
        assertEquals("99999999999", invalid.value());
        assertEquals(UserNumber.Rule.INTEGER, invalid.rule());
    }

    @Test
    @DisplayName("整数：int 范围、进制都按规则判定；合法值原样返回")
    void integerAcceptsRadixAndRange() {
        assertEquals(488, UserNumber.integer("SFTP file mode", "750", UserNumber.Rule.FILE_MODE, 8),
                "八进制 750 = 488");
        assertEquals(7, UserNumber.integer("x", "007"));
        assertEquals(Integer.MAX_VALUE, UserNumber.integer("x", String.valueOf(Integer.MAX_VALUE)));

        UserNumber.InvalidNumberException octal = assertThrows(UserNumber.InvalidNumberException.class,
                () -> UserNumber.integer("SFTP file mode", "8", UserNumber.Rule.FILE_MODE, 8));
        assertEquals("Invalid SFTP file mode [8]: expected an octal permission mode in 0..07777.",
                octal.getMessage());
        // 负数/超范围不是权限位
        for (String bad : new String[] {"-1", "10000"}) {
            assertEquals("Invalid SFTP file mode [" + bad
                            + "]: expected an octal permission mode in 0..07777.",
                    assertThrows(UserNumber.InvalidNumberException.class,
                            () -> UserNumber.integer("SFTP file mode", bad,
                                    UserNumber.Rule.FILE_MODE, 8)).getMessage(), bad);
        }
    }

    @Test
    @DisplayName("非负整数 / 端口：范围是规则的一部分")
    void nonNegativeAndPort() {
        assertEquals(0, UserNumber.nonNegativeInteger("linewidth", "0"));
        assertEquals(40, UserNumber.nonNegativeInteger("colwidth", "40"));
        assertEquals("Invalid linewidth [-1]: expected a non-negative integer.",
                assertThrows(UserNumber.InvalidNumberException.class,
                        () -> UserNumber.nonNegativeInteger("linewidth", "-1")).getMessage());

        assertEquals(22, UserNumber.port("SSH port", "22"));
        assertEquals(65535, UserNumber.port("SSH port", "65535"));
        for (String bad : new String[] {"0", "65536", "abc", "-1"}) {
            assertEquals("Invalid SSH port [" + bad + "]: expected an integer in 1..65535.",
                    assertThrows(UserNumber.InvalidNumberException.class,
                            () -> UserNumber.port("SSH port", bad)).getMessage(), bad);
        }
    }

    @Test
    @DisplayName("秒数：NaN / Infinity / 负数 / 0 都按规则挡住，合法小数保留")
    void secondsRules() {
        assertEquals(0.05, UserNumber.positiveSeconds("job wait timeout", "0.05"), 0.000001);
        assertEquals(0, UserNumber.nonNegativeSeconds("sleep time", "0"), 0.000001);
        assertEquals(1.5, UserNumber.nonNegativeSeconds("sleep time", "1.5"), 0.000001);

        for (String bad : new String[] {"abc", "NaN", "Infinity", "1e400", "0", "-1"}) {
            assertEquals("Invalid job wait timeout [" + bad + "]: expected a positive number of seconds.",
                    assertThrows(UserNumber.InvalidNumberException.class,
                            () -> UserNumber.positiveSeconds("job wait timeout", bad)).getMessage(), bad);
        }
        for (String bad : new String[] {"abc", "NaN", "1e400", "-1"}) {
            assertEquals("Invalid sleep time [" + bad + "]: expected a non-negative number of seconds.",
                    assertThrows(UserNumber.InvalidNumberException.class,
                            () -> UserNumber.nonNegativeSeconds("sleep time", bad)).getMessage(), bad);
        }
    }

    @Test
    @DisplayName("规则描述就是错误文案里的那一句（_SET 用它拼自己的句式）")
    void ruleDescriptions() {
        assertEquals("an integer", UserNumber.Rule.INTEGER.description());
        assertEquals("a non-negative integer", UserNumber.Rule.NON_NEGATIVE_INTEGER.description());
        assertEquals("an integer in 1..65535", UserNumber.Rule.PORT.description());
        assertEquals("an octal permission mode in 0..07777", UserNumber.Rule.FILE_MODE.description());
        assertEquals("a positive number of seconds", UserNumber.Rule.POSITIVE_SECONDS.description());
        assertEquals("a non-negative number of seconds", UserNumber.Rule.NON_NEGATIVE_SECONDS.description());
    }

    @Test
    @DisplayName("拿错规则组合是编程错误：开发期就炸，不做静默容忍")
    void wrongRuleKindIsRejected() {
        IllegalArgumentException wrongInteger = assertThrows(IllegalArgumentException.class,
                () -> UserNumber.integer("x", "1", UserNumber.Rule.POSITIVE_SECONDS, 10));
        assertTrue(wrongInteger.getMessage().contains("not an integer rule"), wrongInteger.getMessage());
    }
}
