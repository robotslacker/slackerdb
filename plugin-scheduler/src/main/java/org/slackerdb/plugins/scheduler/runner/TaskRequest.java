package org.slackerdb.plugins.scheduler.runner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次任务执行的输入。
 *
 * <p>执行后端（当前只有"外部命令"一种：SHELL/COMMAND 直接执行命令行，HOP 由
 * {@link HopCommandBuilder} 拼出 hop-run 命令行）只依赖本对象，因此后续替换执行方式
 * 不需要改动调度内核。</p>
 */
public final class TaskRequest {

    /** 实例 id（框架变量 {@code RUN_ID}）。 */
    public String runId;
    /** 项目模板名（框架变量 {@code PROJECT_TYPE}）。 */
    public String projectType;
    /** 任务名（框架变量 {@code TASK_NAME}）。 */
    public String taskName;
    /** 任务类型：HOP | SHELL | COMMAND。 */
    public String scriptType;
    /** HOP：相对 {@code PROJECT_HOME} 的 .hwf/.hpl 文件名；SHELL/COMMAND：命令行原文。 */
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
        /** Hop 使用的 JDK 目录（{@code HOP_JAVA_HOME}），可为空。 */
        public String javaHome;
        /** Hop 项目名；为空时用 {@code projectType} 作为项目名。 */
        public String projectName;
        /** Hop 生命周期环境名模板（支持 {@code ${VAR}}）；为空时用 {@code RUN_ID}。 */
        public String environmentName;
        /** {@code --runconfig} 取值，默认 {@code local}。 */
        public String runConfig = "local";

        public boolean isUsable() {
            return runScript != null && !runScript.isBlank();
        }
    }
}
