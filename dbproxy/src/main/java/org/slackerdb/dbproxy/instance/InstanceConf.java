package org.slackerdb.dbproxy.instance;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个实例的 dbserver 配置（{@code instance.conf}）。
 *
 * <p>这是实例目录里<b>唯一的事实源</b>：库名、两个端口、pid 文件、日志路径都在这里。
 * 代理每次探测都重新读它，所以用户手工改端口后，路由会自动跟着变。</p>
 *
 * <p>解析使用 {@link Properties}，与 dbserver 的配置加载方式保持一致（注释、转义、行续写
 * 的行为都一样），避免"代理读出来一套、dbserver 读出来另一套"。</p>
 */
public final class InstanceConf {

    /** 匹配 {@code port=...} 行（行首只允许空白，不匹配 {@code port_x} 或注释行）。 */
    private static final Pattern PORT_LINE =
            Pattern.compile("(?im)^(\\s*)port(\\s*[=:]\\s*|\\s+)[^\\r\\n]*$");
    /** 匹配 {@code port_x=...} 行。 */
    private static final Pattern PORT_X_LINE =
            Pattern.compile("(?im)^(\\s*)port_x(\\s*[=:]\\s*|\\s+)[^\\r\\n]*$");

    private final Map<String, String> values;   // key 统一大写

    private InstanceConf(Map<String, String> values) {
        this.values = values;
    }

    public static InstanceConf load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Configuration file not found: " + file);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    public static InstanceConf parse(String text) throws IOException {
        if (text == null) {
            return new InstanceConf(Map.of());
        }
        try (Reader reader = new StringReader(text)) {
            return parse(reader);
        }
    }

    private static InstanceConf parse(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            parsed.put(name.toUpperCase(Locale.ROOT), properties.getProperty(name).trim());
        }
        return new InstanceConf(parsed);
    }

    public String get(String key) {
        return values.get(key.toUpperCase(Locale.ROOT));
    }

    public String get(String key, String fallback) {
        String value = get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        String value = get(key);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    /** {@code --data}：这个实例承载的数据库名（可以不同于别名）。 */
    public String dataName() {
        return get("DATA", "");
    }

    /** PG 服务端口；非法或未配置时返回 -1。 */
    public int port() {
        return getInt("PORT", -1);
    }

    /** 管理口端口；{@code -1} 表示未启用（此时无法探活，只能靠 pid 判活）。 */
    public int portX() {
        return getInt("PORT_X", -1);
    }

    public String pidFile() {
        return get("PID", "instance.pid");
    }

    public String bind() {
        return get("BIND", "127.0.0.1");
    }

    public String accessMode() {
        return get("ACCESS_MODE", "READ_WRITE");
    }

    /** 给 JSON 用的只读视图（键小写，便于阅读）。 */
    public Map<String, String> asLowercaseMap() {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    /**
     * 配置层面的问题清单（为空说明可用）。
     *
     * <p>代理只做"能不能路由/能不能探活"这一层的校验，不替 dbserver 校验业务参数。</p>
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (dataName().isEmpty()) {
            problems.add("data= is missing");
        }
        if (port() < 1 || port() > 65535) {
            problems.add("port= is not a routable port (" + get("PORT") + ")");
        }
        int portX = portX();
        if (portX != -1 && (portX < 1 || portX > 65535)) {
            problems.add("port_x= is invalid (" + get("PORT_X") + ")");
        }
        return problems;
    }

    public boolean isUsable() {
        return problems().isEmpty();
    }

    /**
     * 把 {@code port=} / {@code port_x=} 改写成实际分配的端口，文件其它内容（注释、顺序、
     * 插件参数大小写）原样保留。
     *
     * <p>用于"用户传 0 表示自动分配"：create 时直接写实际端口；start 时如果旧配置里仍是 0，
     * 就在这里补上，之后通过管理 API 查到的就是真实端口。</p>
     *
     * @param port  新的 PG 端口；{@code null} 表示不改
     * @param portX 新的管理口端口；{@code null} 表示不改
     * @return 是否写入了文件
     */
    public static boolean rewritePorts(Path file, Integer port, Integer portX) throws IOException {
        if (port == null && portX == null) {
            return false;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (port != null) {
            String replaced = replaceLine(text, PORT_LINE, "port", port);
            text = replaced != null ? replaced : appendLine(text, "port=" + port);
        }
        if (portX != null) {
            String replaced = replaceLine(text, PORT_X_LINE, "port_x", portX);
            text = replaced != null ? replaced : appendLine(text, "port_x=" + portX);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return true;
    }

    private static String replaceLine(String text, Pattern pattern, String key, int value) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String replacement = matcher.group(1) + key + "=" + value;
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String appendLine(String text, String line) {
        String separator = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r") ? "" : "\n";
        return text + separator + line + "\n";
    }

    /**
     * 生成配置文件正文。
     *
     * @param ordered 需要写入的键值（按迭代顺序输出；键原样保留，插件参数的大小写有意义）
     */
    public static String render(Map<String, String> ordered) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Slackerdb instance configuration (generated by dbproxy).\n");
        sb.append("# This file is the source of truth for this instance: database name, ports,\n");
        sb.append("# pid file and log path live here. The proxy re-reads it on every probe.\n");
        sb.append("#\n");
        sb.append("# Manual maintenance is supported: edit and restart the instance, the proxy\n");
        sb.append("# will follow the new values without being restarted.\n");
        sb.append('\n');
        ordered.forEach((key, value) -> sb.append(key)
                .append('=')
                .append(escapeValue(value))
                .append('\n'));
        return sb.toString();
    }

    /**
     * 按 {@link Properties} 的规则转义取值。
     *
     * <p>只处理会破坏"一行一个键值"的字符：反斜杠（Windows 路径里到处都是）、换行、制表符。
     * 这样用户用 {@code --data_dir D:\data\sales} 传进来的路径，dbserver 读到的仍是原样路径。</p>
     */
    private static String escapeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }
}
