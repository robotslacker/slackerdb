package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项表的单一定义（{@link SetOption}）：默认值/类型/取值校验/打印顺序都从这一处来。
 *
 * <p>这些断言锁的是"加一个选项只改一个地方"：任何默认值、错误文案或顺序的变化都会在这里显形。</p>
 */
class SetOptionTest {

    @Test
    @DisplayName("默认值都合法，且按声明顺序打印（_SET 表的顺序）")
    void defaultsAreValidAndOrdered() {
        assertEquals(List.of("echo", "feedback", "termout", "timing", "time", "outformat", "header",
                        "linewidth", "colwidth", "diff_report_html", "autocommit", "timeout"),
                SetOption.names(), "打印顺序是契约（_SET 无参的输出）");

        for (SetOption option : SetOption.values()) {
            assertEquals(option.defaultValue(), option.validate(option.defaultValue()),
                    "默认值必须能通过自己的校验: " + option.optionName());
        }
    }

    @Test
    @DisplayName("取值校验的错误文案（与命令行口径一致）")
    void validationMessages() {
        assertEquals("Set option [echo] requires ON or OFF, got [maybe].",
                assertThrows(SetOption.InvalidValueException.class,
                        () -> SetOption.ECHO.validate("maybe")).getMessage());
        assertEquals("Set option [linewidth] requires a non-negative integer, got [-1].",
                assertThrows(SetOption.InvalidValueException.class,
                        () -> SetOption.LINEWIDTH.validate("-1")).getMessage());
        assertEquals("Set option [linewidth] requires a non-negative integer, got [abc].",
                assertThrows(SetOption.InvalidValueException.class,
                        () -> SetOption.LINEWIDTH.validate("abc")).getMessage());
        assertEquals("Set option [outformat] requires table or vertical or csv, got [json].",
                assertThrows(SetOption.InvalidValueException.class,
                        () -> SetOption.OUTFORMAT.validate("json")).getMessage());
    }

    @Test
    @DisplayName("规范化：布尔/枚举取值大小写无关，整数去掉前导零；查名字不区分大小写")
    void normalizationAndLookup() {
        assertEquals("on", SetOption.AUTOCOMMIT.validate(" ON "));
        assertEquals("csv", SetOption.OUTFORMAT.validate("Csv"));
        assertEquals("30", SetOption.TIMEOUT.validate("0030"));
        assertTrue(SetOption.of("OUTFORMAT") == SetOption.OUTFORMAT);
        assertNull(SetOption.of("nope"));
        assertNull(SetOption.of(null));
    }
}
