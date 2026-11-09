package org.slackerdb.dbserver.scheduler.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><b>Hop 2.20 起</b>：项目与环境通过 {@code --project-locations} /
 * {@code --environments} / {@code --in-memory} 在子进程内注册，不再要求先写入
 * {@code hop-config.json}。这解决了旧实现的两个问题：</p>
 * <ul>
 *   <li>同一实例的多个任务并行时，各自读-改-写 {@code hop-config.json} 会丢条目，
 *       后启动的 hop-run 可能找不到自己的环境；</li>
 *   <li>{@code hop-config.json} 只读（或由部署方集中管控）时，写它会让任务直接失败。</li>
 * </ul>
 * <p>为了兼容模板里的全局变量/项目配置，{@code hop-config.json} 仍会被尽力维护；但在
 * {@link TaskRequest.HopConfig#inMemoryProject} 为 true（默认）时，维护失败只记告警，
 * 真正的项目/环境来源是命令行。需要回退到 Hop 2.19 行为时把它设为 false。</p>
 *
 * <p>本类只做三件事：</p>
 * <ol>
 *   <li>校验 Hop 运行环境与工作流文件；</li>
 *   <li>把变量写成 Hop 生命周期环境可读的 JSON 文件，并准备 {@code HOP_*} 环境变量；</li>
 *   <li>拼出 {@code hop-run} 命令行（<b>参数数组</b>，不做字符串拼接，路径含空格/中文不会出错）。</li>
 * </ol>
 *
 * <p><b>框架不内置任何业务命名</b>：Hop 项目名与环境名都来自配置模板并支持 {@code ${VAR}} 展开，
 * 例如 {@code hop.projectName=某平台-[${PROJECT_TYPE}]}、{@code hop.environmentName=${RUN_ID}}。</p>
 *
 * <p><b>变量如何进入工作流</b>：Hop 的 {@code --environment <name>} 指向生命周期环境，
 * 该环境的 {@code configurationFiles} 指向一个 JSON 变量文件。2.20 起这个环境由
 * {@code --environments <name>=<project>:<variablesFile>} 在进程内定义，
 * 不再依赖运行时目录的 {@code hop-config.json} 中是否存在同名条目。</p>
 */
public final class HopCommandBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(HopCommandBuilder.class);

    /** hop-config.json 中生命周期环境条目的形状（供创建实例时写入，兼容旧版 Hop）。 */
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
        String environmentName = safeEnvironmentName(expand(hop.environmentName, request,
                (request.runId == null ? "run" : request.runId) + "-"
                        + (request.taskName == null ? "task" : request.taskName)));
        String runConfig = hop.runConfig == null || hop.runConfig.isBlank() ? "local" : hop.runConfig;
        boolean inMemory = hop.inMemoryProject;

        // 旧版 Hop 依赖 hop-config.json 中的项目/环境条目；2.20 起命令行注册才是权威来源。
        // 因此这里始终"尽力"维护，但在进程内注册可用时不把失败当作致命错误。
        ensureHopConfig(configFolder, projectName, projectFlowDir, environmentName, variablesFile, inMemory);

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

        if (inMemory) {
            // Hop 2.20：在子进程内注册项目与环境，并用 --in-memory 禁止写回 hop-config.json。
            // 这样每个任务的环境只存在于自己的进程里，并行任务不会互相覆盖。
            command.add("--project-locations");
            StringBuilder projectLocations = new StringBuilder()
                    .append(projectName).append('=').append(projectFlowDir.toAbsolutePath());
            // 父项目 default 也要在进程内注册：hop-config.json 只读/写失败时，项目配置里的
            // parentProjectName=default 才不会解析不到。
            Path defaultProjectDir = configFolder.resolve("default");
            if (!"default".equalsIgnoreCase(projectName) && Files.isDirectory(defaultProjectDir)) {
                projectLocations.append(",default=").append(defaultProjectDir.toAbsolutePath());
            }
            command.add(projectLocations.toString());
            command.add("--environments");
            command.add(environmentName + "=" + projectName + ":" + variablesFile.toAbsolutePath());
            command.add("--in-memory");
        }
        return command;
    }

    /** 维护 hop-config.json；进程内注册可用时允许失败。 */
    private static void ensureHopConfig(Path configFolder, String projectName, Path projectHome,
                                        String environmentName, Path variablesFile, boolean inMemory) {
        try {
            HopConfigWriter.ensure(configFolder, projectName, projectHome, environmentName, variablesFile);
        } catch (RuntimeException e) {
            if (!inMemory) {
                throw e;
            }
            LOG.warn("[RUNNER] 维护 hop-config.json 失败，改由 Hop 2.20 的进程内项目/环境注册执行"
                    + "（configFolder={}, project={}, environment={}）: {}",
                    configFolder, projectName, environmentName, e.getMessage());
        }
    }

    /**
     * HOP 进程所需的环境变量。
     *
     * <p>{@code HOP_CONFIG_FOLDER} 指向运行目录里的 {@code flow}（模板根的拷贝，内含
     * {@code hop-config.json}、{@code default/} 与 {@code <projectType>/}）。该值是"每进程一份"
     * —— 每个任务一个进程，因此天然隔离，这正是命令行方案相对内嵌的优势。</p>
     *
     * <p><b>编码</b>：hop-run 的 stdout/stderr 会被合并后写入 UTF-8 任务日志。为了避免
     * Windows 控制台代码页（GBK）与 UTF-8 混写，这里显式要求 Hop 以 UTF-8 输出：</p>
     * <ul>
     *   <li>{@code HOP_WINDOWS_SHELL_ENCODING=65001}：Hop 2.20 的 {@code hop-run.bat}
     *       会据此执行 {@code chcp 65001}；</li>
     *   <li>{@code HOP_OPTIONS} 追加 {@code -Dfile.encoding/-Dstdout.encoding/-Dstderr.encoding=UTF-8}，
     *       保证 JVM 通过管道写出的字节是 UTF-8（JDK 18+ 的 {@code stdout.encoding} 不再跟随
     *       {@code file.encoding}，两者都要设置）。</li>
     * </ul>
     */
    public static Map<String, String> environment(TaskRequest request) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOP_CONFIG_FOLDER", configFolder(request).toAbsolutePath().toString());
        if (request.runHome != null) {
            Path auditFolder = request.runHome.resolve("audit").toAbsolutePath();
            Path logFolder = request.runHome.resolve("logs").toAbsolutePath();
            createDirectoriesQuietly(auditFolder);
            createDirectoriesQuietly(logFolder);
            env.put("HOP_AUDIT_FOLDER", auditFolder.toString());
            env.put("HOP_LOG_FOLDER", logFolder.toString());
        }
        if (isWindows()) {
            // Hop 2.20 的 hop-run.bat 读取该变量并 chcp 到 UTF-8
            env.put("HOP_WINDOWS_SHELL_ENCODING", "65001");
        }
        // javaHome 为空时用当前 JVM 的 JDK；HOP 2.20 要求 JDK 21，而服务端本身就跑在 21 上。
        String javaHome = request.hop == null ? null : request.hop.javaHome;
        if (javaHome == null || javaHome.isBlank()) {
            javaHome = System.getProperty("java.home");
        }
        if (javaHome != null && !javaHome.isBlank()) {
            env.put("HOP_JAVA_HOME", javaHome);
        }
        env.put("HOP_OPTIONS", hopOptions());
        return env;
    }

    /**
     * 保留部署方已有的 {@code HOP_OPTIONS}，并追加 UTF-8 设置。
     *
     * <p>hop-run 脚本只在 {@code HOP_OPTIONS} 为空时才补默认的 {@code -Xmx2048m}，
     * 所以这里在为空时显式补上，避免调度器设置编码后反而丢掉默认堆大小。</p>
     */
    private static String hopOptions() {
        String existing = System.getenv("HOP_OPTIONS");
        StringBuilder options = new StringBuilder();
        options.append(existing == null || existing.isBlank() ? "-Xmx2048m" : existing.trim());
        options.append(" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        return options.toString();
    }

    private static void createDirectoriesQuietly(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("[RUNNER] 创建 Hop 目录失败（Hop 启动时可能自行创建）: {} : {}", dir, e.getMessage());
        }
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
     * <pre>{ "description": "...", "variables": [ { "name": "KEY", "value": "VALUE", "description": "..." } ] }</pre>
     * <p>不是 {@code KEY=VALUE} 文本 —— 写成文本 Hop 不会报错，但变量会静默不生效。
     * 顶层的 {@code description} 是 Hop 2.20 新增的（旧版会忽略）。</p>
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
        root.put("description", "slackerdb scheduler variables");
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

    /**
     * 环境名会进入 {@code --environments <name>=<project>:<file>} 与 hop-config.json。
     * {@code = , ;} 是 Hop 2.20 这两个选项的分隔符，出现在名字里会截断解析，这里统一替换。
     */
    private static String safeEnvironmentName(String name) {
        return name.replace('=', '_').replace(',', '_').replace(';', '_');
    }

    /**
     * 变量文件与 Hop 项目名里用到的文件名安全化。
     *
     * <p>除路径分隔符外，还要避开 Hop 2.20 {@code --environments} 的 {@code , ; =} 分隔符，
     * 否则任务名里带这些字符时会截断环境定义。</p>
     */
    private static String safeFileName(String name) {
        return name.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('=', '_').replace(',', '_').replace(';', '_');
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
