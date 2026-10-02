package org.slackerdb.plsql.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 一致性用例加载器：扫描 {@link #ROOT} 目录下的 {@code *.plsql}，解析 {@code --!} 指令头。
 *
 * <p>指令格式（每条一行，<b>可以出现在用例块任意位置</b>；正文 = 所有非 {@code --!} 行。
 * 因此脚本里请用 {@code --} 写普通注释，{@code --!} 保留给指令）：</p>
 * <pre>
 * --! id:            REG-001            （必填，用例唯一标识）
 * --! layer:         L5                 （选填，报告分组）
 * --! req:           DECLARE            （选填，对应需求）
 * --! tags:          smoke, cursor      （选填）
 * --! mode:          exec               （选填，exec | parse）
 * --! setup:         create table t(i int)
 * --! expect-query:  select count(*) from t | 2
 * --! expect-error:  none               （none 或错误消息子串）
 * </pre>
 */
public final class CaseLoader {

    /** 相对模块根目录（surefire 的工作目录是模块根）。 */
    public static final String ROOT = "src/test/resources/plsql/conformance";

    /** {@code 消息 @行:列} 形态的位置断言。 */
    private static final java.util.regex.Pattern POSITION =
            java.util.regex.Pattern.compile("^(.*?)\\s*@\\s*(\\d+)(?::(\\d+))?$");

    private CaseLoader() {
    }

    public static List<ConformanceCase> loadAll() {
        Path root = Paths.get(ROOT);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".plsql"))
                    .sorted()
                    .flatMap(p -> loadFile(p).stream())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<ConformanceCase> loadFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<ConformanceCase> cases = new ArrayList<>();
        List<String> chunk = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().equals("--! ---")) {
                if (!chunk.isEmpty()) {
                    cases.add(parse(file, chunk));
                    chunk.clear();
                }
            } else {
                chunk.add(line);
            }
        }
        if (!chunk.isEmpty()) {
            cases.add(parse(file, chunk));
        }
        return cases;
    }

    private static ConformanceCase parse(Path file, List<String> lines) {
        ConformanceCase c = new ConformanceCase();
        c.file = file;
        c.rawText = String.join("\n", lines);

        StringBuilder script = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("--!")) {
                script.append(line).append('\n');
                continue;
            }
            String body = line.substring(3).trim();
            if (body.isEmpty()) {
                continue;
            }
            int colon = body.indexOf(':');
            if (colon < 0) {
                throw error(file, i, "指令缺少 ':' -> " + line);
            }
            String key = body.substring(0, colon).trim().toLowerCase();
            String value = body.substring(colon + 1).trim();
            switch (key) {
                case "id" -> c.id = value;
                case "layer" -> c.layer = value;
                case "req" -> c.req = value;
                case "mode" -> c.mode = value;
                case "expect-kind" -> c.expectKind = value;
                case "expect-statements" -> c.expectStatements = Integer.parseInt(value);
                case "expect-ast" -> c.expectAst = value;
                case "expect-error" -> {
                    // 支持 "消息 @行:列" / "消息 @行"，位置 1 起
                    java.util.regex.Matcher position = POSITION.matcher(value);
                    if (position.matches()) {
                        c.expectError = position.group(1).trim();
                        c.expectLine = Integer.parseInt(position.group(2));
                        c.expectColumn = position.group(3) == null ? -1 : Integer.parseInt(position.group(3));
                    } else {
                        c.expectError = value;
                    }
                }
                case "tags" -> {
                    for (String tag : value.split(",")) {
                        if (!tag.isBlank()) {
                            c.tags.add(tag.trim());
                        }
                    }
                }
                case "setup" -> c.setups.add(value);
                case "expect-query" -> c.queries.add(parseQuery(file, i, value));
                default -> throw error(file, i, "未知指令 '" + key + "'");
            }
        }
        c.script = script.toString().strip();

        if (c.id.isEmpty()) {
            throw error(file, 0, "用例缺少 --! id:");
        }
        if (!"exec".equalsIgnoreCase(c.mode)
                && !"parse".equalsIgnoreCase(c.mode)
                && !"detect".equalsIgnoreCase(c.mode)
                && !"compile".equalsIgnoreCase(c.mode)) {
            throw error(file, 0, "不支持的 mode：" + c.mode);
        }
        if (c.script.isEmpty() && !"detect".equalsIgnoreCase(c.mode)) {
            // detect 模式允许空正文：EMPTY 分类本身就是要断言的行为
            throw error(file, 0, "用例缺少脚本正文");
        }
        return c;
    }

    /** {@code <sql> | <row> ; <row>}，单元格用 ',' 分隔，含 ','/';' 的值用双引号包裹。 */
    private static ConformanceCase.QueryExpectation parseQuery(Path file, int lineNo, String value) {
        int bar = value.indexOf('|');
        if (bar < 0) {
            throw error(file, lineNo, "expect-query 缺少 '|' 分隔符 -> " + value);
        }
        ConformanceCase.QueryExpectation q = new ConformanceCase.QueryExpectation();
        q.sql = value.substring(0, bar).trim();
        String rowsPart = value.substring(bar + 1).trim();
        if (!rowsPart.isEmpty()) {
            for (String row : splitTopLevel(rowsPart, ';')) {
                if (row.isBlank()) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (String cell : splitTopLevel(row, ',')) {
                    cells.add(unquote(cell.trim()));
                }
                q.rows.add(cells);
            }
        }
        return q;
    }

    /** 按分隔符切分，但双引号内的分隔符不算。 */
    private static List<String> splitTopLevel(String text, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                current.append(ch);
            } else if (ch == sep && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static IllegalArgumentException error(Path file, int lineNo, String message) {
        return new IllegalArgumentException(file + ":" + (lineNo + 1) + " " + message);
    }
}
