package org.slackerdb.plugins.scheduler.runner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 变量集合：框架变量 + 三层业务变量，并做有限的 {@code ${...}} 展开。
 *
 * <p><b>框架不解释任何业务语义</b>：这里只处理字符串透传与展开。业务需要什么连接变量
 * （例如 {@code ODS_HOST}、{@code JTLS_PORT}），由业务在配置/模板/API 中声明。</p>
 *
 * <p>覆盖顺序（后者覆盖前者）：</p>
 * <ol>
 *   <li>全局：{@code scheduler.home/variables.properties}</li>
 *   <li>模板：{@code projects/<projectType>/conf/variables.properties} 与任务清单里的 {@code variables}</li>
 *   <li>实例：{@code POST /scheduler/run/create} 的 {@code params}</li>
 *   <li>框架变量（最高优先级，业务不可覆盖）：见 {@link #FRAMEWORK_KEYS}</li>
 * </ol>
 *
 * <p>变量值中的 {@code ${OTHER_KEY}} 会按上述合并后的视图展开（最多 {@link #MAX_EXPAND_DEPTH} 层），
 * 因此业务可以写 {@code EXERCISE_CODE=${RUN_ID}} 这类映射，而框架本身不认识 {@code EXERCISE_CODE}。
 * 无法解析的引用保持原样（便于排障，也与 shell/Hop 的行为一致）。</p>
 */
public final class VariableSet {

    /** 框架变量名（由插件注入，业务不可覆盖）。 */
    public static final String PROJECT_HOME = "PROJECT_HOME";
    public static final String RUN_HOME = "RUN_HOME";
    public static final String RUN_ID = "RUN_ID";
    public static final String PROJECT_TYPE = "PROJECT_TYPE";
    public static final String TASK_NAME = "TASK_NAME";
    public static final String LOG_DIR = "LOG_DIR";
    public static final String AUDIT_DIR = "AUDIT_DIR";
    public static final String TASK_BEGIN_TIME = "TASK_BEGIN_TIME";

    public static final java.util.List<String> FRAMEWORK_KEYS = java.util.List.of(
            PROJECT_HOME, RUN_HOME, RUN_ID, PROJECT_TYPE, TASK_NAME, LOG_DIR, AUDIT_DIR, TASK_BEGIN_TIME);

    /** {@code ${}} 展开的最大层数，防止自引用死循环。 */
    private static final int MAX_EXPAND_DEPTH = 5;

    private final Map<String, String> values;

    private VariableSet(Map<String, String> values) {
        this.values = values;
    }

    /**
     * 合并各层并展开。
     *
     * @param layers 按优先级从低到高的变量层，允许 null / 空
     */
    @SafeVarargs
    public static VariableSet resolve(Map<String, String>... layers) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (layers != null) {
            for (Map<String, String> layer : layers) {
                if (layer == null) {
                    continue;
                }
                for (Map.Entry<String, String> entry : layer.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        continue;
                    }
                    merged.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }
        Map<String, String> expanded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            expanded.put(entry.getKey(), expand(entry.getValue(), merged, 0));
        }
        return new VariableSet(expanded);
    }

    /** 变量值 → 进程环境变量（SHELL/COMMAND 任务：注入为子进程环境变量）。 */
    public Map<String, String> asProcessEnv() {
        return new LinkedHashMap<>(values);
    }

    /** 变量值 → Hop 变量（HOP 任务：作为 {@code -D} 无法传递，改为 {@code HOP_VARIABLES} 文件/命令行参数）。 */
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }

    /** 供 API 展示：对可能含口令的键做脱敏。 */
    public Map<String, String> masked() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result.put(entry.getKey(), isSecret(entry.getKey()) ? "******" : entry.getValue());
        }
        return result;
    }

    public String get(String name) {
        return values.get(name);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 变量名是否像口令（仅用于脱敏展示，不参与任何业务判断）。 */
    private static boolean isSecret(String key) {
        String upper = key.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("PASSWORD") || upper.contains("PASSWD") || upper.contains("SECRET")
                || upper.contains("TOKEN") || upper.endsWith("_PWD");
    }

    private static String expand(String value, Map<String, String> context, int depth) {
        if (value == null || value.indexOf('$') < 0 || depth >= MAX_EXPAND_DEPTH) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            if (start < 0) {
                out.append(value, index, value.length());
                break;
            }
            out.append(value, index, start);
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                out.append(value, start, value.length());
                break;
            }
            String key = value.substring(start + 2, end).trim();
            String resolved = context.get(key);
            if (resolved == null) {
                out.append(value, start, end + 1); // 未定义：保持原样，便于排障
            } else {
                out.append(expand(resolved, context, depth + 1));
            }
            index = end + 1;
        }
        return out.toString();
    }

    /**
     * 由 {@code key=value} 行构造一层变量（{@code #} 起始的行为注释）。
     */
    public static Map<String, String> parseProperties(java.util.List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        if (lines == null) {
            return map;
        }
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            map.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
        }
        return map;
    }

    /** 从 properties 文件读取一层变量；文件不存在返回空。 */
    public static Map<String, String> loadProperties(java.nio.file.Path file) {
        Objects.requireNonNull(file, "file");
        try {
            if (!java.nio.file.Files.isRegularFile(file)) {
                return new LinkedHashMap<>();
            }
            return parseProperties(java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取变量文件失败: " + file + " : " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "VariableSet" + masked();
    }
}
