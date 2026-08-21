package org.slackerdb.plugins.scheduler.runner;

import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.Result;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.encryption.Encr;
import org.apache.hop.core.encryption.ITwoWayPasswordEncoder;
import org.apache.hop.core.encryption.TwoWayPasswordEncoderPluginType;
import org.apache.hop.core.extension.ExtensionPointPluginType;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.logging.ILoggingObject;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.core.logging.LoggingPluginType;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.core.variables.resolver.VariableResolverPluginType;
import org.apache.hop.core.vfs.plugin.VfsPluginType;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.metadata.serializer.multi.MultiMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.engine.PipelineEngineFactory;
import org.apache.hop.pipeline.engine.PipelineEnginePluginType;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.engine.IWorkflowEngine;
import org.apache.hop.workflow.engine.WorkflowEngineFactory;
import org.apache.hop.workflow.engine.WorkflowEnginePluginType;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * HopService - 封装 Apache Hop 运行环境的服务类。
 * <p>
 * 提供对 Hop Pipeline (.hpl) 和 Workflow (.hwf) 文件的运行能力，
 * 支持通过 Map 传递环境变量。
 * <p>
 * 使用方式：
 * <pre>
 * HopService service = new HopService(logger);
 * service.setEnv(workDirectory, pluginDirectory);
 * service.setAuditDir(auditDirectory); // 可选，默认为 workspace/audit
 * service.setLogDir(logDirectory);     // 可选，默认为 workspace/logs
 *
 * // 运行 HOP 流程
 * service.runHop("path/to/workflow.hwf", environmentMap);
 * </pre>
 */
public class HopService {

    /** 日志记录器 */
    private final Logger log;

    /** 工作目录 */
    private String workDirectory;

    /** 插件目录 */
    private String pluginDirectory;

    /** 审计目录，默认为 workspace/audit */
    private String auditDirectory;

    /** 日志目录，默认为 workspace/logs */
    private String logDirectory;

    /** 运行配置名称，固定为 local */
    private static final String RUN_CONFIG_NAME = "local";

    /** metadata 目录名称，固定为 metadata */
    private static final String METADATA_FOLDER_NAME = "metadata";

    /** Hop 环境是否已初始化 */
    private boolean environmentInitialized = false;

    // ========== 构造函数 ==========

    /**
     * 构造 HopService 实例。
     *
     * @param logger SLF4J 日志记录器，用于输出运行日志
     */
    public HopService(Logger logger) {
        this.log = logger;
    }

    // ========== 配置方法 ==========

    /**
     * 设置工作目录和插件目录。
     * <p>
     * 此方法必须在调用 runHop 之前调用。
     *
     * @param workDirectory   工作路径，用于存放 metadata、audit、logs 等目录
     * @param pluginDirectory 插件所在的目录
     */
    public void setEnv(String workDirectory, String pluginDirectory) {
        this.workDirectory = workDirectory;
        this.pluginDirectory = pluginDirectory;
    }

    /**
     * 设置审计目录。
     * <p>
     * 如果不设置，默认为 workspace 下的 audit 目录。
     *
     * @param auditDirectory 审计目录路径
     */
    public void setAuditDir(String auditDirectory) {
        this.auditDirectory = auditDirectory;
    }

    /**
     * 设置日志目录。
     * <p>
     * 如果不设置，默认为 workspace 下的 logs 目录。
     *
     * @param logDirectory 日志目录路径
     */
    public void setLogDir(String logDirectory) {
        this.logDirectory = logDirectory;
    }

    // ========== 运行 HOP 流程 ==========

    /**
     * 运行 HOP 流程（通过 Map 传递变量）。
     * <p>
     * 支持运行 .hwf (Workflow) 和 .hpl (Pipeline) 文件。
     * hopFileName 可以是相对于 workDirectory 的相对路径，也可以是绝对路径。
     *
     * @param hopFileName    HOP 文件名，可以是 .hwf 或 .hpl 文件
     * @param mapEnvironment 环境变量 Map
     * @throws Exception 如果运行过程中发生错误
     */
    public void runHop(String hopFileName, Map<String, String> mapEnvironment) throws Exception {
        // 校验配置
        validateConfiguration();

        // 解析文件路径
        String resolvedFilePath = resolveFilePath(hopFileName);
        log.info("解析后的文件路径: {}", resolvedFilePath);

        // 检查文件是否存在
        if (!Files.exists(Paths.get(resolvedFilePath))) {
            throw new IllegalArgumentException("HOP 文件不存在: " + resolvedFilePath);
        }

        // 初始化 Hop 环境（仅首次调用时初始化）
        initHopEnvironment();

        // 创建变量环境
        IVariables variables = createVariables(mapEnvironment);

        // 创建元数据提供者
        IHopMetadataProvider metadataProvider = createMetadataProvider(variables);

        // 根据文件扩展名判断类型并执行
        String lowerPath = resolvedFilePath.toLowerCase();

        if (lowerPath.endsWith(".hwf")) {
            runWorkflow(resolvedFilePath, variables, metadataProvider);
        } else if (lowerPath.endsWith(".hpl")) {
            runPipeline(resolvedFilePath, variables, metadataProvider);
        } else {
            throw new IllegalArgumentException("不支持的文件类型，请使用 .hwf (workflow) 或 .hpl (pipeline) 文件: " + hopFileName);
        }
    }

    // ========== 内部初始化方法 ==========

    /**
     * 校验配置是否完整。
     */
    private void validateConfiguration() {
        if (workDirectory == null || workDirectory.isEmpty()) {
            throw new IllegalStateException("workDirectory 未设置，请先调用 setEnv() 方法");
        }
        if (pluginDirectory == null || pluginDirectory.isEmpty()) {
            throw new IllegalStateException("pluginDirectory 未设置，请先调用 setEnv() 方法");
        }
    }

    /**
     * 解析文件路径。
     * <p>
     * 如果 hopFileName 是绝对路径，则直接返回；
     * 否则将其视为相对于 workDirectory 的相对路径。
     *
     * @param hopFileName HOP 文件名
     * @return 解析后的绝对路径
     */
    private String resolveFilePath(String hopFileName) {
        Path path = Paths.get(hopFileName);
        if (path.isAbsolute()) {
            return hopFileName;
        }
        return Paths.get(workDirectory, hopFileName).normalize().toString();
    }

    /**
     * 初始化 Hop 客户端环境。
     * <p>
     * 仅在首次调用时执行初始化，后续调用复用已初始化的环境。
     */
    @SuppressWarnings({"rawtypes"})
    private synchronized void initHopEnvironment() throws Exception {
        if (environmentInitialized) {
            log.info("Hop 环境已初始化，跳过");
            return;
        }

        log.info("正在初始化 Hop 环境...");

        // 先初始化 HopLogStore
        HopLogStore.init();

        // 注册所有需要的插件类型
        // HopClientEnvironment.init() 接受 raw type List<IPluginType>
        List<IPluginType> pluginTypes = createPluginTypes();
        HopClientEnvironment.init(pluginTypes);

        // 设置系统属性
        setSystemProperties();

        environmentInitialized = true;
        log.info("Hop 环境初始化完成");
    }

    /**
     * 创建插件类型列表。
     */
    @SuppressWarnings({"rawtypes"})
    private List<IPluginType> createPluginTypes() {
        return Arrays.asList(
                LoggingPluginType.getInstance(),
                ValueMetaPluginType.getInstance(),
                DatabasePluginType.getInstance(),
                ExtensionPointPluginType.getInstance(),
                TwoWayPasswordEncoderPluginType.getInstance(),
                VariableResolverPluginType.getInstance(),
                VfsPluginType.getInstance(),
                ActionPluginType.getInstance(),
                PipelineEnginePluginType.getInstance(),
                WorkflowEnginePluginType.getInstance()
        );
    }

    /**
     * 设置 Hop 相关的系统属性。
     */
    private void setSystemProperties() {
        // 设置审计目录
        String auditDir = (auditDirectory != null) ? auditDirectory : Paths.get(workDirectory, "audit").toString();
        System.setProperty("HOP_AUDIT_FOLDER", auditDir);
        log.info("审计目录: {}", auditDir);

        // 设置日志目录
        String logDir = (logDirectory != null) ? logDirectory : Paths.get(workDirectory, "logs").toString();
        System.setProperty("HOP_LOG_FOLDER", logDir);
        log.info("日志目录: {}", logDir);

        // 设置插件目录
        System.setProperty("HOP_PLUGIN_FOLDER", pluginDirectory);
        log.info("插件目录: {}", pluginDirectory);

        // 设置工作目录
        System.setProperty("HOP_WORK_FOLDER", workDirectory);
        log.info("工作目录: {}", workDirectory);
    }

    /**
     * 创建变量环境，并将 mapEnvironment 中的变量注入。
     *
     * @param mapEnvironment 环境变量 Map
     * @return 变量环境
     */
    private IVariables createVariables(Map<String, String> mapEnvironment) {
        IVariables variables = new Variables();
        variables.initializeFrom(null);

        // 注入自定义环境变量
        if (mapEnvironment != null && !mapEnvironment.isEmpty()) {
            for (Map.Entry<String, String> entry : mapEnvironment.entrySet()) {
                variables.setVariable(entry.getKey(), entry.getValue());
                log.info("设置变量: {} = {}", entry.getKey(), entry.getValue());
            }
        }

        return variables;
    }

    /**
     * 创建元数据提供者。
     * <p>
     * metadata 目录固定为 workspace 下的 metadata 目录。
     */
    private IHopMetadataProvider createMetadataProvider(IVariables variables) throws Exception {
        String metadataFolder = Paths.get(workDirectory, METADATA_FOLDER_NAME).toString();
        log.info("Metadata 目录: {}", metadataFolder);

        Path path = Paths.get(metadataFolder);
        if (!Files.exists(path)) {
            log.info("Metadata 目录不存在，将创建: {}", metadataFolder);
            Files.createDirectories(path);
        }

        ITwoWayPasswordEncoder encoder = Encr.getEncoder();
        JsonMetadataProvider jsonProvider = new JsonMetadataProvider(encoder, metadataFolder, variables);
        return new MultiMetadataProvider(variables, jsonProvider);
    }

    // ========== 运行 Workflow ==========

    /**
     * 运行指定的 Workflow 文件。
     *
     * @param workflowFile     Workflow 文件路径 (.hwf)
     * @param variables        变量环境
     * @param metadataProvider 元数据提供者
     */
    private void runWorkflow(String workflowFile, IVariables variables,
                             IHopMetadataProvider metadataProvider) throws Exception {
        log.info("=== 加载 Workflow ===");

        // 加载 Workflow 元数据
        WorkflowMeta workflowMeta = new WorkflowMeta(variables, workflowFile, metadataProvider);

        // 创建 Workflow 引擎
        ILoggingObject loggingObject = new LoggingObject("HopService");
        IWorkflowEngine<WorkflowMeta> workflowEngine = WorkflowEngineFactory.createWorkflowEngine(
                variables,
                RUN_CONFIG_NAME,
                metadataProvider,
                workflowMeta,
                loggingObject
        );

        log.info("=== 开始执行 Workflow: {} ===", workflowMeta.getName());

        // 执行 Workflow (同步等待完成)
        Result result = workflowEngine.startExecution();

        // 检查执行结果
        String resultStr = result.getExitStatus() != 0 ? "失败" : "成功";
        log.info("=== Workflow 执行完成 ===");
        log.info("退出状态: {}", result.getExitStatus());
        log.info("错误数: {}", result.getNrErrors());
        log.info("结果: {}", resultStr);

        if (result.getExitStatus() != 0) {
            throw new RuntimeException("Workflow 执行失败，退出状态: " + result.getExitStatus()
                    + "，错误数: " + result.getNrErrors());
        }
    }

    // ========== 运行 Pipeline ==========

    /**
     * 运行指定的 Pipeline 文件。
     *
     * @param pipelineFile     Pipeline 文件路径 (.hpl)
     * @param variables        变量环境
     * @param metadataProvider 元数据提供者
     */
    private void runPipeline(String pipelineFile, IVariables variables,
                             IHopMetadataProvider metadataProvider) throws Exception {
        log.info("=== 加载 Pipeline ===");

        // 加载 Pipeline 元数据
        PipelineMeta pipelineMeta = new PipelineMeta(pipelineFile, metadataProvider, variables);

        // 创建 Pipeline 引擎
        IPipelineEngine<PipelineMeta> pipelineEngine = PipelineEngineFactory.createPipelineEngine(
                variables,
                RUN_CONFIG_NAME,
                metadataProvider,
                pipelineMeta
        );

        log.info("=== 开始执行 Pipeline: {} ===", pipelineMeta.getName());

        // 准备执行
        pipelineEngine.prepareExecution();

        // 开始执行 (同步等待完成)
        pipelineEngine.startThreads();

        // 等待执行完成
        pipelineEngine.waitUntilFinished();

        // 检查执行结果
        String result = pipelineEngine.getErrors() > 0 ? "失败" : "成功";
        log.info("=== Pipeline 执行完成 ===");
        log.info("错误数: {}", pipelineEngine.getErrors());
        log.info("结果: {}", result);

        if (pipelineEngine.getErrors() > 0) {
            throw new RuntimeException("Pipeline 执行失败，错误数: " + pipelineEngine.getErrors());
        }
    }
}
