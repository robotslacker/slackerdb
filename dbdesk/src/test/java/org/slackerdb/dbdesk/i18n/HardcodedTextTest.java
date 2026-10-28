package org.slackerdb.dbdesk.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 硬编码文案的守卫：{@code src/main/java} 里的字符串字面量（含文本块）不许出现中文。
 *
 * <p>注释里出现中文是允许的——本测试用一个手写小词法器跳过注释、字符字面量和转义，
 * 只检查真正的字符串内容。这样"代码里写死文案"会在测试期被抓到，而不是等用户切到英文时
 * 看到一半中文。</p>
 *
 * <p>确实需要豁免的行，在行尾写 {@code // i18n-ignore}。</p>
 */
class HardcodedTextTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    void noChineseInJavaStringLiterals() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (Literal literal : literals(source)) {
                if (!containsChinese(literal.value())) {
                    continue;
                }
                String line = source.lines().skip(literal.line() - 1L).findFirst().orElse("");
                if (line.contains("i18n-ignore")) {
                    continue;
                }
                violations.add(SOURCE_ROOT.relativize(file) + ":" + literal.line()
                        + " → " + oneLine(literal.value()));
            }
        }
        assertTrue(violations.isEmpty(),
                "以下字符串字面量仍是硬编码中文，请移到 i18n 资源文件并用 I18n.t(...)：\n"
                        + String.join("\n", violations));
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fff')
                    || (c >= '\u3000' && c <= '\u303f')
                    || (c >= '\uff00' && c <= '\uffef')) {
                return true;
            }
        }
        return false;
    }

    private static String oneLine(String value) {
        String flat = value.replace("\n", "\\n").replace("\r", "");
        return flat.length() > 80 ? flat.substring(0, 77) + "..." : flat;
    }

    /** 一个字符串字面量：内容 + 起始行号（1-based）。 */
    private record Literal(String value, int line) {
    }

    /**
     * 手写词法器：跳过注释/字符字面量，返回所有字符串字面量。
     *
     * <p>只处理 Java 里会出现的形态；不会解析 unicode 转义（{@code \\uXXXX} 在字面量里极少见，
     * 真出现也不是"写死中文"的常见写法，宁可漏报也不误报）。</p>
     */
    static List<Literal> literals(String source) {
        List<Literal> result = new ArrayList<>();
        int line = 1;
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < length && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                    if (i < length && source.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i++;
                continue;
            }
            if (c == '"') {
                boolean textBlock = i + 2 < length && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"';
                int startLine = line;
                StringBuilder value = new StringBuilder();
                if (textBlock) {
                    i += 3;
                    while (i < length && !(source.charAt(i) == '"' && i + 2 < length
                            && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"')) {
                        char ch = source.charAt(i);
                        if (ch == '\n') {
                            line++;
                        }
                        value.append(ch);
                        i++;
                    }
                    i += 3;
                } else {
                    i++;
                    while (i < length && source.charAt(i) != '"') {
                        char ch = source.charAt(i);
                        if (ch == '\\' && i + 1 < length) {
                            value.append(ch);
                            ch = source.charAt(i + 1);
                            if (ch == '\n') {
                                line++;
                            }
                            value.append(ch);
                            i += 2;
                            continue;
                        }
                        if (ch == '\n') {
                            // 未闭合的字符串（语法错误的源码）：宁可在这里停掉
                            line++;
                        }
                        value.append(ch);
                        i++;
                    }
                    i++;
                }
                result.add(new Literal(value.toString(), startLine));
                continue;
            }
            i++;
        }
        return result;
    }
}
