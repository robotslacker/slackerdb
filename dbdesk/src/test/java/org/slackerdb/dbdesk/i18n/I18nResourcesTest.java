package org.slackerdb.dbdesk.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源文件纪律：
 *
 * <ol>
 *   <li>英文（默认包 {@code messages.properties}）与中文（{@code messages_zh.properties}）
 *       的 key 集合必须完全一致——少一条就是运行时显示 key；</li>
 *   <li>同一条文案在两种语言里的 {@code {n}} 占位符集合必须一致，否则切换语言就会丢参数；</li>
 *   <li>不许空值、不许重复 key（properties 后者会静默覆盖前者）。</li>
 * </ol>
 */
class I18nResourcesTest {

    private static final Path I18N_DIR = Path.of("src", "main", "resources", "i18n");
    private static final Path ENGLISH = I18N_DIR.resolve("messages.properties");
    private static final Path CHINESE = I18N_DIR.resolve("messages_zh.properties");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    @Test
    void englishAndChineseBundlesHaveTheSameKeys() throws IOException {
        Set<String> english = keys(ENGLISH);
        Set<String> chinese = keys(CHINESE);

        assertFalse(english.isEmpty(), "英文资源文件不应为空");

        Set<String> missingInChinese = new LinkedHashSet<>(english);
        missingInChinese.removeAll(chinese);
        Set<String> missingInEnglish = new LinkedHashSet<>(chinese);
        missingInEnglish.removeAll(english);

        assertTrue(missingInChinese.isEmpty(), "中文资源缺少这些 key：" + missingInChinese);
        assertTrue(missingInEnglish.isEmpty(), "英文资源缺少这些 key：" + missingInEnglish);
    }

    @Test
    void placeholdersMatchBetweenLanguages() throws IOException {
        var english = entries(ENGLISH);
        var chinese = entries(CHINESE);
        List<String> problems = new ArrayList<>();
        for (var entry : english.entrySet()) {
            String zh = chinese.get(entry.getKey());
            if (zh == null) {
                continue;
            }
            Set<String> enArgs = placeholders(entry.getValue());
            Set<String> zhArgs = placeholders(zh);
            if (!enArgs.equals(zhArgs)) {
                problems.add(entry.getKey() + "：en=" + enArgs + " zh=" + zhArgs);
            }
        }
        assertTrue(problems.isEmpty(), "占位符不一致：\n" + String.join("\n", problems));
    }

    @Test
    void valuesAreNotEmptyAndKeysAreUnique() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : List.of(ENGLISH, CHINESE)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int separator = line.indexOf('=');
                assertTrue(separator > 0, file.getFileName() + " 第 " + (i + 1) + " 行没有 =：" + line);
                String key = line.substring(0, separator).strip();
                String value = line.substring(separator + 1);
                if (!seen.add(key)) {
                    problems.add(file.getFileName() + " 重复 key：" + key);
                }
                if (value.isBlank()) {
                    problems.add(file.getFileName() + " 空值：" + key);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void defaultBundleIsEnglishAndChineseIsASeparateLocale() {
        assertEquals("DbDesk", bundle(java.util.Locale.ENGLISH).getString("app.title"));
        assertEquals("中文", bundle(java.util.Locale.SIMPLIFIED_CHINESE).getString("language.zh"));
        assertEquals("SQL 控制台", bundle(java.util.Locale.SIMPLIFIED_CHINESE).getString("nav.sql"),
                "中文包必须真的被加载，而不是回落到英文");
        assertEquals("SQL Console", bundle(java.util.Locale.ENGLISH).getString("nav.sql"),
                "英文必须来自默认包，不能受 JVM 默认语言影响");
    }

    /**
     * properties 会吃掉分隔符后的前导空格；真正需要空格的文案必须写成 {@code \u0020}，
     * 否则状态行会变成 "3 statement(s)· last: query" 这种粘在一起的文本。
     */
    @Test
    void significantSpacesSurvivePropertiesParsing() {
        java.util.ResourceBundle english = bundle(java.util.Locale.ENGLISH);
        java.util.ResourceBundle chinese = bundle(java.util.Locale.SIMPLIFIED_CHINESE);

        assertEquals(" · ", english.getString("common.segmentJoin"), "状态行分隔符必须两侧各有一个空格");
        assertEquals(" · ", chinese.getString("common.segmentJoin"), "状态行分隔符必须两侧各有一个空格");
        assertTrue(english.getString("management.label").endsWith(" "), "管理口标签后要留一个空格再跟 URL");
        assertTrue(english.getString("cluster.field.label").endsWith(" "), "详情字段标签后要留一个空格再跟值");
    }

    /** 与 {@code I18n} 一样关掉 "回落到 JVM 默认语言"，否则中文开发机上的英文断言会读到中文包。 */
    private static java.util.ResourceBundle bundle(java.util.Locale locale) {
        return java.util.ResourceBundle.getBundle("i18n.messages", locale,
                java.util.ResourceBundle.Control.getNoFallbackControl(
                        java.util.ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /**
     * 代码里用到的静态 key 必须真的存在于资源文件里。
     *
     * <p>只检查"字面量出现在取词调用参数位"的情况；动态拼出来的 key（例如按状态返回
     * {@code ai.role.*}）不在检查范围，由评审兜底。</p>
     */
    @Test
    void everyKeyUsedInCodeExistsInTheBundle() throws IOException {
        Set<String> known = entries(ENGLISH).keySet();
        List<String> usages = List.of(
                "I18n\\.t\\(\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "I18n\\.setTitle\\(\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "MessageResolver\\.render\\(\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "LocalizedMessage\\.of\\(\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "LocalizedException\\(\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "new (?:org\\.slackerdb\\.dbdesk\\.i18n\\.)?LocalizedException\\([^,()]+,\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "ProbeException\\(\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"",
                "ProbeException\\([^,()]+,\\s*\"([A-Za-z][A-Za-z0-9_.]*)\"");
        List<Pattern> patterns = usages.stream().map(Pattern::compile).toList();

        List<String> missing = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(Path.of("src", "main", "java"))) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!known.contains(key)) {
                        missing.add(Path.of("src", "main", "java").relativize(file) + " → " + key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "代码引用了资源文件里不存在的 key：\n" + String.join("\n", missing));
    }

    private static Set<String> keys(Path file) throws IOException {
        return entries(file).keySet();
    }

    /** 保持文件顺序读 key/value；重复 key 由 {@link #valuesAreNotEmptyAndKeysAreUnique()} 负责发现。 */
    private static java.util.LinkedHashMap<String, String> entries(Path file) throws IOException {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
                    return;
                }
                int separator = stripped.indexOf('=');
                if (separator <= 0) {
                    return;
                }
                result.put(stripped.substring(0, separator).strip(), stripped.substring(separator + 1));
            });
        }
        return result;
    }

    private static Set<String> placeholders(String pattern) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }
}
