package org.slackerdb.dbcli.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径参数的唯一入口（{@link PathArguments}）：非法字符/空值给可读错误，而不是
 * {@code InvalidPathException} 冒到 {@code Main} 变成 255 + Java 栈。
 */
class PathArgumentsTest {

    @Test
    @DisplayName("相对/绝对路径都按给定基准解析并规范化")
    void resolvesRelativeAndAbsolute(@TempDir Path dir) {
        assertEquals(dir.resolve("a.sql").normalize(), PathArguments.resolve(dir, "a.sql"));
        assertEquals(dir.resolve("sub/../a.sql").normalize(), PathArguments.resolve(dir, "sub/../a.sql"));
        Path absolute = dir.resolve("abs.sql").toAbsolutePath();
        assertEquals(absolute.normalize(), PathArguments.resolve(Path.of("elsewhere"), absolute.toString()));
    }

    @Test
    @DisplayName("非法字符：可读的 Invalid path [...]，不抛 JDK 的 InvalidPathException")
    void illegalCharactersAreReadable() {
        // 取值必须"在本平台上非法"：* 只在 Windows 上非法，POSIX 上要用 NUL（见 IllegalPaths）
        PathArguments.InvalidPathException invalid = assertThrows(PathArguments.InvalidPathException.class,
                () -> PathArguments.resolve(Path.of(""), IllegalPaths.VALUE));
        assertTrue(invalid.getMessage().startsWith(IllegalPaths.INVALID_PREFIX), invalid.getMessage());
        assertEquals(IllegalPaths.VALUE, invalid.value());
    }

    @Test
    @DisplayName("空值/只有空白也是可读错误")
    void emptyPathIsRejected() {
        assertEquals("Invalid path []: the path is empty.",
                assertThrows(PathArguments.InvalidPathException.class,
                        () -> PathArguments.resolve(Path.of(""), "  ")).getMessage());
    }
}
