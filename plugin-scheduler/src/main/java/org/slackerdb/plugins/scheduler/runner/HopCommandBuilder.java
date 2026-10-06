package org.slackerdb.plugins.scheduler.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HOP 命令行构造器（方案 A：Hop 独立目录 + 命令行执行）。
 *
 * <p><b>为什么用命令行而不是内嵌</b>：内嵌需要改 dbserver 启动参数（{@code --add-opens}）、
 * 让 Hop 与数据库共用进程堆与故障域、并自行处理类加载隔离；命令行把 Hop 放在独立进程里，
 * 对 slackerdb 本体零侵入，且 Hop 升级只需换目录。</p>
 *
 * <p>本类只做三件事：</p>
 * <ol>
 *   <li>校验 Hop 运行环境与工作流文件；</li>
 *   <li>把变量写成 Hop 生命周期环境可读的 {@code .env} 文件，并准备 {@code HOP_*} 环境变量；</li>
 *   <li>拼出 {@code hop-run} 命令行（<b>参数数组</b>，不做字符串拼接，路径含空格/中文不会出错）。</li>
 * </ol>
 *
 * <p><b>框架不内置任何业务命名</b>：Hop 项目名与环境名都来自配置模板并支持 {@code ${VAR}} 展开，
 * 例如 {@code hop.projectName=某平台-[${PROJECT_TYPE}]}、{@code hop.environmentName=${RUN_ID}}。</p>
 *
 * <p><b>变量如何进入工作流</b>：Hop 的 {@code --environment <name>} 指向 hop-config.json 中声明的
 * 生命周期环境，而该环境的 {@code configurationFiles} 指向一个 {@code KEY=VALUE} 文件。因此
 * 框架把变量写进 {@link #variablesFile(TaskRequest)}，并要求运行时目录的 {@code hop-config.json}
 * 中存在同名环境的对应条目（形状见 {@link #ENVIRONMENT_ENTRY_HINT}）。</p>
 */
public final class HopCommandBuilder {

    /** hop-config.json 中生命周期环境条目的形状（供创建实例时写入）。 */
    public static final String ENVIRONMENT_ENTRY_HINT =
            "{ \"name\": \"<environmentName>\", \"purpose\": \"<purpose>\", "
            + "\"projectName\": \"<projectName>\", \"configurationFiles\": [ \"<absolute .env path>\" ] }";

    private HopCommandBuilder() {
    }

    /**
     * 构造 hop-run 命令行；同时落盘变量文件。
     *
     * @throws IllegalStateException 运行环境或工作流文件不可用时
     */
    public static List<String> buildCommand(TaskRequest request) {
        TaskRequest.HopConfig hop = request.hop;
        if (hop == null || !hop.isUsable()) {
            throw new IllegalStateException("Hop 运行环境未配置（scheduler.hop.runScript 缺失），无法执行 HOP 任务");
        }
        Path runScript = Path.of(hop.runScript);
        if (!Files.isRegularFile(runScript)) {
            throw new IllegalStateException("hop-run 脚本不存在: " + runScript);
        }

        Path configFolder = configFolder(request);
        Path projectFlowDir = projectFlowDir(request);
        Path flowFile = projectFlowDir.resolve(request.script);
        if (!Files.isRegularFile(flowFile)) {
            throw new IllegalStateException("HOP 工作流文件不存在: " + flowFile);
        }

        Path variablesFile = variablesFile(request);
        writeVariablesFile(variablesFile, request);

        String projectName = expand(hop.projectName, request, request.projectType);
        String environmentName = expand(hop.environmentName, request,
                (request.runId == null ? "run" : request.runId) + "-"
                        + (request.taskName == null ? "task" : request.taskName));
        String runConfig = hop.runConfig == null || hop.runConfig.isBlank() ? "local" : hop.runConfig;

        // 变量要进工作流，必须让 hop-config.json 里存在同名项目与环境（环境的 configurationFiles 指向变量文件）
        HopConfigWriter.ensure(configFolder, projectName, projectFlowDir, environmentName, variablesFile);

        List<String> command = new ArrayList<>();
        if (isWindows()) {
            // .bat/.cmd 不能被 CreateProcess 直接执行，显式经 cmd /c 包装
            command.add("cmd");
            command.add("/c");
            command.add(runScript.toAbsolutePath().toString());
        } else {
            command.add("sh");
            command.add(runScript.toAbsolutePath().toString());
        }
        command.add("--project");
        command.add(projectName);
        command.add("--file");
        command.add(flowFile.toAbsolutePath().toString());
        command.add("--environment");
        command.add(environmentName);
        command.add("--runconfig");
        command.add(runConfig);
        return command;
    }

    /**
     * HOP 进程所需的环境变量。
     *
     * <p>{@code HOP_CONFIG_FOLDER} 指向运行目录里的 {@code flow}（模板根的拷贝，内含
     * {@code hop-config.json}、{@code default/} 与 {@code <projectType>/}）。该值是"每进程一份"
     * —— 每个任务一个进程，因此天然隔离，这正是命令行方案相对内嵌的优势。</p>
     */
    public static Map<String, String> environment(TaskRequest request) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOP_CONFIG_FOLDER", configFolder(request).toAbsolutePath().toString());
        if (request.runHome != null) {
            env.put("HOP_AUDIT_FOLDER", request.runHome.resolve("audit").toAbsolutePath().toString());
            env.put("HOP_LOG_FOLDER", request.runHome.resolve("logs").toAbsolutePath().toString());
        }
        if (request.hop != null && request.hop.javaHome != null && !request.hop.javaHome.isBlank()) {
            env.put("HOP_JAVA_HOME", request.hop.javaHome);
        }
        return env;
    }

    /** 本次任务的变量文件路径：{@code <runHome>/conf/<taskName>.env}（每个任务一份，避免并行覆盖）。 */
    public static Path variablesFile(TaskRequest request) {
        Path base = request.runHome == null ? Path.of(".") : request.runHome;
        String taskName = request.taskName == null || request.taskName.isBlank() ? "task" : request.taskName;
        return base.resolve("conf").resolve(safeFileName(taskName) + ".env");
    }

    /** Hop 配置根目录（{@code HOP_CONFIG_FOLDER}）：{@code <runHome>/flow}。 */
    public static Path configFolder(TaskRequest request) {
        Path base = request.runHome == null ? Path.of(".") : request.runHome;
        return base.resolve("flow");
    }

    /** Hop 项目主目录（工作流与 metadata 所在）：{@code <runHome>/flow/<projectType>}。 */
    public static Path projectFlowDir(TaskRequest request) {
        String projectType = request.projectType == null ? "" : request.projectType;
        return configFolder(request).resolve(projectType);
    }

    /**
     * 写 Hop 生命周期环境的配置文件。
     *
     * <p><b>格式必须是 JSON</b>（Hop 的 {@code DescribedVariablesConfigFile}）：</p>
     * <pre>{ "variables": [ { "name": "KEY", "value": "VALUE", "description": "..." } ] }</pre>
     * <p>不是 {@code KEY=VALUE} 文本 —— 写成文本 Hop 不会报错，但变量会静默不生效。</p>
     */
    private static void writeVariablesFile(Path file, TaskRequest request) {
        com.alibaba.fastjson2.JSONArray variables = new com.alibaba.fastjson2.JSONArray();
        if (request.variables != null) {
            for (Map.Entry<String, String> entry : request.variables.asMap().entrySet()) {
                com.alibaba.fastjson2.JSONObject item = new com.alibaba.fastjson2.JSONObject();
                item.put("name", entry.getKey());
                item.put("value", entry.getValue() == null ? "" : entry.getValue());
                item.put("description", "slackerdb scheduler");
                variables.add(item);
            }
        }
        com.alibaba.fastjson2.JSONObject root = new com.alibaba.fastjson2.JSONObject();
        root.put("variables", variables);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, com.alibaba.fastjson2.JSON.toJSONString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 Hop 变量文件失败: " + file + " : " + e.getMessage(), e);
        }
    }

    private static String expand(String template, TaskRequest request, String fallback) {
        String value = template;
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        if (request.variables != null) {
            // 通过 VariableSet 的统一展开规则解析 ${VAR}
            value = VariableSet.resolve(request.variables.asMap(), Map.of("__T__", value)).get("__T__");
        }
        return value;
    }

    /** 变量文件与 Hop 项目名里用到的文件名安全化（仅替换路径分隔符，不做业务改名）。 */
    private static String safeFileName(String name) {
        return name.replace('\\', '_').replace('/', '_').replace(':', '_');
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
