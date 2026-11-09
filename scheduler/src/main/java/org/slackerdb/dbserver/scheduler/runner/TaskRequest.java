package org.slackerdb.dbserver.scheduler.runner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次任务执行的输入。
 *
 * <p>执行后端（SHELL 直接执行命令行，HOP 由 {@link HopCommandBuilder} 拼出 hop-run 命令行，
 * DBCLI 由 {@link DbcliTaskExecutor} 在进程内跑 dbcli 脚本）只依赖本对象，因此后续替换执行方式
 * 不需要改动调度内核。</p>
 */
public final class TaskRequest {

    /** 实例 id（框架变量 {@code RUN_ID}）。 */
    public String runId;
    /** 项目模板名（框架变量 {@code PROJECT_TYPE}）。 */
    public String projectType;
    /** 任务名（框架变量 {@code TASK_NAME}）。 */
    public String taskName;
    /** 任务类型：HOP | SHELL | DBCLI。 */
    public String scriptType;
    /**
     * HOP：相对 {@code PROJECT_HOME} 的 .hwf/.hpl 文件名；
     * SHELL：命令行原文；
     * DBCLI：dbcli 脚本文件名（相对 run 的 {@code flow/<projectType>}，或绝对路径）。
     */
    public String script;

    /** 实例根目录（{@code RUN_HOME}）。 */
    public Path runHome;
    /** 本次执行的日志文件（绝对路径）。 */
    public Path logFile;
    /** 进程工作目录；为空时用 {@code runHome}。 */
    public Path workDir;

    /** 超时秒数；0 或负数表示不限。 */
    public int timeoutSeconds;

    /** 已解析好的变量（框架变量 + 三层业务变量）。 */
    public VariableSet variables;

    /** HOP 运行环境配置（{@code scheduler.hop.*}）。 */
    public HopConfig hop;

    /**
     * DBCLI 任务连回本实例的连接串（{@code jdbc:slackerdb://host:port/database}）。
     *
     * <p>由宿主按数据服务端口生成（见 {@code DBInstanceX}），也可用
     * {@code plugin-scheduler.dbcli.connect} 覆盖；为空表示本实例没有可供 DBCLI 连接的数据服务端口。</p>
     */
    public String dbcliConnect;

    public TaskRequest() {
    }

    public Map<String, String> processEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        if (variables != null) {
            env.putAll(variables.asProcessEnv());
        }
        if (scriptType != null && "HOP".equalsIgnoreCase(scriptType.trim())) {
            env.putAll(HopCommandBuilder.environment(this));
        }
        return env;
    }

    /** Hop 运行环境配置；由插件属性（{@code scheduler.hop.*}）填充。 */
    public static final class HopConfig {
        /** {@code hop-run.bat|sh} 的绝对路径。 */
        public String runScript;
        /** Hop 使用的 JDK 目录（{@code HOP_JAVA_HOME}）；为空时使用当前 JVM 的 {@code java.home}。 */
        public String javaHome;
        /** Hop 项目名；为空时用 {@code projectType} 作为项目名。 */
        public String projectName;
        /** Hop 生命周期环境名模板（支持 {@code ${VAR}}）；为空时用 {@code RUN_ID}。 */
        public String environmentName;
        /** {@code --runconfig} 取值，默认 {@code local}。 */
        public String runConfig = "local";
        /**
         * 是否使用 Hop 2.20+ 的进程内项目/环境注册
         * （{@code --project-locations} / {@code --environments} / {@code --in-memory}）。
         *
         * <p>默认 {@code true}。开启后 Hop 不再依赖 {@code hop-config.json} 中的条目，
         * 因此多个任务并发执行时不会互相覆盖环境；该文件仍会被维护（兼容全局配置），
         * 但写入失败只记告警、不阻断任务。仅在 Hop 2.19 及更早版本上需要设为
         * {@code false} 回退到旧的 hop-config 模式。</p>
         */
        public boolean inMemoryProject = true;

        public boolean isUsable() {
            return runScript != null && !runScript.isBlank();
        }
    }
}
