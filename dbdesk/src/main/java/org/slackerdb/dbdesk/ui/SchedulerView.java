package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.scheduler.SchedulerClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度中心（ETL）：run 列表 + 任务表 + 任务编辑 + 日志尾巴。
 *
 * <p>页面**只在服务端声明了 etl 能力时**从菜单进入（见 {@link MainLayout}），
 * 但路由本身始终存在 —— 能力为假时页面会显示"该实例没有 ETL 插件"，而不是白屏。</p>
 *
 * <p>所有操作都是"调接口 → 重新拉列表"，不做本地乐观更新：调度状态是服务端说了算，
 * 猜错了比慢半拍更糟。</p>
 */
@Route(value = "scheduler", layout = MainLayout.class)
public class SchedulerView extends VerticalLayout {

    private final Button refresh = new Button("刷新");
    private final Span capabilityInfo = new Span("未连接");
    private final Span errorText = new Span();

    private final Grid<SchedulerClient.RunInfo> runGrid = new Grid<>(SchedulerClient.RunInfo.class, false);
    private final ComboBox<String> projectType = new ComboBox<>("项目类型");
    private final TextField runIdField = new TextField("runId（可空）");
    private final Button createRun = new Button("新建 run");
    private final Button templateButton = new Button("模板…");
    private final ComboBox<String> historyRange = new ComboBox<>("时间范围");
    private final Button historyRefresh = new Button("刷新历史");
    private final Button historyButton = new Button("历史流水");

    private final Span historySummary = new Span();
    private final Grid<SchedulerClient.HistoryRow> historyGrid =
            new Grid<>(SchedulerClient.HistoryRow.class, false);
    private final VerticalLayout templatePanel = new VerticalLayout();
    private final TextField templatePath = new TextField("相对路径");
    private final TextArea templateContent = new TextArea("内容（JSON）");
    private final Span templateResult = new Span();
    private String templateProject;
    private final Button startRun = new Button("启动");
    private final Button stopRun = new Button("停止");
    private final Button dropRun = new Button("丢弃");

    private final Grid<SchedulerClient.TaskInfo> taskGrid = new Grid<>(SchedulerClient.TaskInfo.class, false);
    private final TextField taskName = new TextField("任务名");
    private final TextField taskScript = new TextField("脚本");
    private final ComboBox<String> taskType = new ComboBox<>("类型");
    private final ComboBox<String> taskPolicy = new ComboBox<>("运行策略");
    private final TextField cron = new TextField("cron（6 段）");
    private final IntegerField taskTimeout = new IntegerField("超时(秒)");
    private final com.vaadin.flow.component.checkbox.Checkbox taskEnabled =
            new com.vaadin.flow.component.checkbox.Checkbox("启用");
    private final TextField taskGroup = new TextField("分组");
    private final IntegerField taskOrder = new IntegerField("顺序");
    private final TextField taskDesc = new TextField("描述");
    private final Span runDetail = new Span();
    private final ComboBox<String> logTask = new ComboBox<>("日志任务");
    private final IntegerField logTail = new IntegerField("行数");
    private final Button logRefresh = new Button("刷新日志");
    private final Button createTask = new Button("建任务");
    private final Button updateTask = new Button("更新");
    private final Button deleteTask = new Button("删除");
    private final Button scheduleTask = new Button("登记调度");

    private final Button loadLog = new Button("看日志");
    private final TextArea logArea = new TextArea("日志（最后 200 行）");

    private transient SchedulerClient client;
    private SchedulerClient.RunInfo selectedRun;
    private SchedulerClient.TaskInfo selectedTask;

    public SchedulerView() {
        setHeightFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("调度中心（ETL）");
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span("当前连接不可用，请先连接带实例管理口的服务端。");
            guard.setId("scheduler-guard");
            add(title, guard);
            return;
        }

        refresh.setId("sched-refresh");
        refresh.addClickListener(event -> reloadAll());
        historyButton.setId("history-button-open");
        historyButton.addClickListener(event -> {
            reloadHistory();
            historyGrid.getElement().executeJs("this.scrollIntoView({block: 'center'})");
        });

        capabilityInfo.setId("sched-cap");
        errorText.setId("sched-error");
        errorText.getStyle().set("color", "var(--lumo-error-text-color)");

        buildRunGrid();
        buildTaskGrid();

        projectType.setId("run-project");
        projectType.setAllowCustomValue(true); // 模板目录还没建的场景也要能输入
        // 注意：ComboBox 在 setItems 之前 setValue 会抛 "Cannot set a value … without items"，
        // 默认值放到 reloadAll() 拿到项目列表之后再选。
        runIdField.setId("run-id");
        createRun.setId("run-create");
        createRun.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createRun.addClickListener(event -> createRun());
        templateButton.setId("template-button");
        templateButton.addClickListener(event -> toggleTemplatePanel());
        startRun.setId("run-start");
        startRun.addClickListener(event -> runAction("start"));
        stopRun.setId("run-stop");
        stopRun.addClickListener(event -> runAction("stop"));
        dropRun.setId("run-drop");
        dropRun.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        dropRun.addClickListener(event -> runAction("drop"));

        taskName.setId("task-name");
        taskScript.setId("task-script");
        taskType.setId("task-type");
        taskType.setItems("HOP", "SHELL", "COMMAND");
        taskType.setValue("HOP");
        taskPolicy.setId("task-policy");
        taskPolicy.setItems("RUN_ONCE", "CRONTAB", "INTERVAL");
        taskPolicy.setValue("CRONTAB");
        cron.setId("task-cron");
        cron.setValue("0 0 3 * * ?");
        taskTimeout.setId("task-timeout");
        taskTimeout.setValue(0);
        taskEnabled.setId("task-enabled");
        taskEnabled.setValue(true);
        taskGroup.setId("task-group");
        taskOrder.setId("task-order");
        taskDesc.setId("task-desc");
        runDetail.setId("run-detail");
        runDetail.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");
        logTask.setId("log-task");
        logTask.setAllowCustomValue(true);
        logTail.setId("log-tail");
        logTail.setValue(200);
        logRefresh.setId("log-refresh");
        logRefresh.addClickListener(event -> showLog());

        createTask.setId("task-create");
        createTask.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createTask.addClickListener(event -> createTask());
        updateTask.setId("task-update");
        updateTask.addClickListener(event -> updateTask());
        deleteTask.setId("task-delete");
        deleteTask.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        deleteTask.addClickListener(event -> deleteTask());
        scheduleTask.setId("task-schedule");
        scheduleTask.addClickListener(event -> scheduleTask());

        loadLog.setId("task-log-load");
        loadLog.addClickListener(event -> showLog());
        logArea.setId("task-log");
        logArea.setReadOnly(true);
        logArea.setWidthFull();
        logArea.setHeight("220px");
        logArea.getStyle().set("font-family", "var(--lumo-font-family-monospace)");

        VerticalLayout tasksPane = new VerticalLayout(
                new HorizontalLayout(taskName, taskScript),
                new HorizontalLayout(taskType, taskPolicy, cron, taskTimeout),
                new HorizontalLayout(taskEnabled, taskGroup, taskOrder, taskDesc),
                new HorizontalLayout(createTask, updateTask, deleteTask, scheduleTask),
                taskGrid,
                new HorizontalLayout(logTask, logTail, logRefresh, loadLog),
                logArea);
        tasksPane.setPadding(false);
        tasksPane.setFlexGrow(1, taskGrid);

        historyRange.setId("history-range");
        historyRange.setItems("最近 1 小时", "最近 24 小时", "最近 7 天", "全部");
        historyRange.setValue("最近 24 小时");
        historyRefresh.setId("history-refresh");
        historyRefresh.addClickListener(event -> reloadHistory());
        historySummary.setId("history-summary");
        historyGrid.setId("history-grid");
        historyGrid.setWidthFull();
        historyGrid.addColumn(SchedulerClient.HistoryRow::runId).setHeader("runId").setAutoWidth(true);
        historyGrid.addColumn(SchedulerClient.HistoryRow::taskName).setHeader("任务").setAutoWidth(true);
        historyGrid.addColumn(SchedulerClient.HistoryRow::startTime).setHeader("开始").setAutoWidth(true);
        historyGrid.addColumn(SchedulerClient.HistoryRow::duration).setHeader("耗时").setAutoWidth(true);
        historyGrid.addColumn(SchedulerClient.HistoryRow::result).setHeader("结果").setAutoWidth(true);
        historyGrid.addColumn(SchedulerClient.HistoryRow::retMsg).setHeader("信息").setAutoWidth(true);

        VerticalLayout historyPane = new VerticalLayout(
                new H3("历史流水"),
                new HorizontalLayout(historyRange, historyRefresh, historySummary),
                historyGrid);
        historyPane.setPadding(false);
        historyGrid.setHeight("260px");

        // 不用 TabSheet：Vaadin 只把"选中 tab"的内容放进 DOM，切换瞬间元素可能不存在，
        // 按钮/测试都可能扑空。上下堆叠 + 右侧面板滚动更省心。
        VerticalLayout right = new VerticalLayout(tasksPane, historyPane);
        right.setPadding(false);
        right.getStyle().set("overflow", "auto");

        VerticalLayout left = new VerticalLayout(
                new H3("Run"),
                new HorizontalLayout(projectType, runIdField, createRun),
                new HorizontalLayout(startRun, stopRun, dropRun, templateButton),
                runGrid,
                runDetail);
        left.setPadding(false);
        left.setWidth("42%");
        left.setFlexGrow(1, runGrid);

        HorizontalLayout body = new HorizontalLayout(left, right);
        body.setWidthFull();
        body.setHeight("calc(100vh - 190px)");
        body.setFlexGrow(1, left);
        body.setFlexGrow(1, right);

        buildTemplatePanel();
        add(title, new HorizontalLayout(refresh, historyButton, capabilityInfo), errorText, body, templatePanel);

        reloadAll();
    }

    private void buildRunGrid() {
        runGrid.setId("run-grid");
        runGrid.setWidthFull();
        runGrid.addColumn(SchedulerClient.RunInfo::runId).setHeader("runId").setAutoWidth(true);
        runGrid.addColumn(SchedulerClient.RunInfo::projectType).setHeader("项目").setAutoWidth(true);
        runGrid.addColumn(SchedulerClient.RunInfo::status).setHeader("状态").setAutoWidth(true);
        runGrid.addColumn(SchedulerClient.RunInfo::createTime).setHeader("创建时间").setAutoWidth(true);
        runGrid.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(run -> {
            selectedRun = run;
            runDetail.setText(String.format("%s · %s · %s · %s",
                    run.runId(), run.projectType(), run.status(), run.workDir()));
            reloadTasks();
        }));
    }

    private void buildTaskGrid() {
        taskGrid.setId("task-grid");
        taskGrid.setWidthFull();
        taskGrid.addColumn(SchedulerClient.TaskInfo::name).setHeader("任务").setAutoWidth(true);
        taskGrid.addColumn(SchedulerClient.TaskInfo::scriptType).setHeader("类型").setAutoWidth(true);
        taskGrid.addColumn(SchedulerClient.TaskInfo::runPolicy).setHeader("策略").setAutoWidth(true);
        taskGrid.addColumn(SchedulerClient.TaskInfo::crontabExpr).setHeader("cron").setAutoWidth(true);
        taskGrid.addColumn(task -> task.enabled() ? "启用" : "停用").setHeader("开关").setAutoWidth(true);
        taskGrid.addColumn(task -> task.scheduled() ? "已登记" : "未登记").setHeader("调度").setAutoWidth(true);
        taskGrid.addColumn(task -> task.running() ? "运行中" : task.runtimeState()).setHeader("运行态").setAutoWidth(true);
        taskGrid.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(task -> {
            selectedTask = task;
            taskName.setValue(task.name());
            taskScript.setValue(task.script());
            taskType.setValue(task.scriptType());
            taskPolicy.setValue(task.runPolicy());
            cron.setValue(task.crontabExpr());
            taskTimeout.setValue(task.timeout());
            taskEnabled.setValue(task.enabled());
            logTask.setValue(task.name());
        }));
    }

    private SchedulerClient client() {
        if (client == null) {
            client = new SchedulerClient(ConnectionManager.current().managementUrl());
        }
        return client;
    }

    private void resetClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void reloadAll() {
        resetClient();
        errorText.setText("");
        try {
            SchedulerClient.Capabilities capabilities = client().capabilities();
            capabilityInfo.setText(String.format("contract %d · quartz %s(%d) · run:%s 任务:%s · HOP:%s",
                    capabilities.contractVersion(),
                    capabilities.quartzStarted() ? "启动" : "停止",
                    capabilities.quartzThreads(),
                    capabilities.runCrud() ? "可写" : "只读",
                    capabilities.taskCrud() ? "可写" : "只读",
                    capabilities.hopReady() ? "就绪" : "未就绪"));
            runGrid.setItems(client().runs());
        } catch (SchedulerClient.SchedulerException e) {
            capabilityInfo.setText("不可用");
            errorText.setText("调度器不可用：" + e.getMessage());
            runGrid.setItems(List.of());
            taskGrid.setItems(List.of());
            return;
        }
        // 项目列表单独 try：它挂了不该把整个页面标成"不可用"（下拉框退化成手填）
        try {
            List<String> projects = client().projects();
            projectType.setItems(projects);
            if (projectType.getValue() == null || projectType.getValue().isBlank()) {
                projectType.setValue(projects.isEmpty() ? "demo" : projects.get(0));
            }
        } catch (SchedulerClient.SchedulerException e) {
            projectType.setItems(List.of());
            if (projectType.getValue() == null || projectType.getValue().isBlank()) {
                projectType.setValue("demo");
            }
        }
    }

    private void reloadTasks() {
        if (selectedRun == null) {
            return;
        }
        try {
            List<SchedulerClient.TaskInfo> tasks = client().tasks(selectedRun.runId());
            taskGrid.setItems(tasks);
            logTask.setItems(tasks.stream().map(SchedulerClient.TaskInfo::name).toList());
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("读任务失败：" + e.getMessage());
        }
    }

    private void createRun() {
        try {
            client().createRun(projectType.getValue(), runIdField.getValue());
            errorText.setText("");
            runGrid.setItems(client().runs());
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("新建 run 失败：" + e.getMessage());
        }
    }

    /**
     * 模板编辑区（**内联面板**，不是 Dialog）：读/写/删 {@code projects/<type>/…} 下的文件。
     *
     * <p>为什么不用 Dialog：这里的 Dialog 在 Vaadin 24 + Aura 下没有生成 overlay，
     * 内容被内联渲染到页面最底部（实测 {@code hasOverlay=false}，出现在 1348px 高的 body 末尾），
     * 用户点了按钮看不到东西。内联面板反而更适合"编辑一段 JSON"。</p>
     */
    private void buildTemplatePanel() {
        templatePath.setId("tpl-path");
        templatePath.setValue("conf/defaultSchedulerTask.json");
        templatePath.setWidthFull();

        templateContent.setId("tpl-content");
        templateContent.setWidthFull();
        templateContent.setHeight("220px");
        templateContent.getStyle().set("font-family", "var(--lumo-font-family-monospace)");
        templateContent.setValue("""
                {
                  "tasks": [
                    {
                      "taskName": "load_fact",
                      "taskScript": "load_fact.hwf",
                      "taskScriptType": "HOP",
                      "taskRunPolicy": "CRONTAB",
                      "taskCrontabExpr": "0 0 3 * * ?",
                      "taskFailPolicy": "STOP",
                      "taskStartupOrder": 10
                    }
                  ]
                }
                """);

        templateResult.setId("tpl-result");

        Button load = new Button("读取", event -> {
            try {
                templateContent.setValue(client().templateFile(templateProject, templatePath.getValue()));
                templateResult.setText("已读取");
            } catch (SchedulerClient.SchedulerException e) {
                templateResult.setText("读取失败：" + e.getMessage());
            }
        });
        load.setId("tpl-load");

        Button upload = new Button("上传", event -> {
            try {
                List<String> warnings = client().uploadTemplate(templateProject,
                        templatePath.getValue(), templateContent.getValue());
                templateResult.setText(warnings.isEmpty() ? "已上传（无告警）" : "已上传，告警：" + String.join("；", warnings));
            } catch (SchedulerClient.SchedulerException e) {
                templateResult.setText("上传失败：" + e.getMessage());
            }
        });
        upload.setId("tpl-upload");
        upload.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button delete = new Button("删除", event -> {
            try {
                client().deleteTemplate(templateProject, templatePath.getValue());
                templateResult.setText("已删除");
            } catch (SchedulerClient.SchedulerException e) {
                templateResult.setText("删除失败：" + e.getMessage());
            }
        });
        delete.setId("tpl-delete");
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        // 整包导入：Vaadin 24 的 Upload 走 UploadHandler（事件里能直接拿 InputStream）
        com.vaadin.flow.component.upload.Upload zipUpload = new com.vaadin.flow.component.upload.Upload(event -> {
            byte[] bytes;
            try (var in = event.getInputStream()) {
                bytes = in.readAllBytes();
            } catch (Exception e) {
                getUI().ifPresent(ui -> ui.access(() -> templateResult.setText("读取上传失败：" + e.getMessage())));
                return;
            }
            String project = templateProject;
            getUI().ifPresent(ui -> ui.access(() -> {
                try {
                    SchedulerClient.ImportResult result = client().importZip(project,
                            java.util.Base64.getEncoder().encodeToString(bytes));
                    templateResult.setText(String.format("已导入 %d 个文件，模板任务 %d 个%s",
                            result.count(), result.taskCount(),
                            result.warnings().isEmpty() ? "" : "，告警：" + String.join("；", result.warnings())));
                } catch (SchedulerClient.SchedulerException e) {
                    templateResult.setText("导入失败：" + e.getMessage());
                }
            }));
        });
        zipUpload.setId("tpl-import-zip");
        zipUpload.setMaxFiles(1);
        zipUpload.setAcceptedFileTypes(".zip");
        zipUpload.setMaxFileSize(50 * 1024 * 1024);
        Button close = new Button("收起", event -> templatePanel.setVisible(false));
        close.setId("tpl-close");

        templatePanel.setId("tpl-panel");
        templatePanel.setWidthFull();
        templatePanel.setPadding(false);
        templatePanel.add(new H3("项目模板"), templatePath, templateContent,
                new HorizontalLayout(load, upload, delete, zipUpload, close), templateResult);
        templatePanel.setVisible(false);
    }

    /** 「模板…」按钮：切换模板面板的显示，并把当前项目类型记下来给按钮用。 */
    private void toggleTemplatePanel() {
        String project = projectType.getValue();
        if (project == null || project.isBlank()) {
            errorText.setText("先在左边选/填一个项目类型");
            return;
        }
        templateProject = project;
        templatePanel.setVisible(!templatePanel.isVisible());
        if (templatePanel.isVisible()) {
            templateResult.setText("项目：" + project);
            // 面板在页面下方（body 占满首屏高度），展开后滚过去，否则用户以为没反应
            templatePanel.getElement().executeJs("this.scrollIntoView({block: 'start'})");
        }
    }

    /** 历史：按时间范围拉跨 run 的流水。 */
    private void reloadHistory() {
        int hours = switch (historyRange.getValue() == null ? "" : historyRange.getValue()) {
            case "最近 1 小时" -> 1;
            case "最近 7 天" -> 24 * 7;
            case "全部" -> 0;
            default -> 24;
        };
        try {
            SchedulerClient.HistoryPage page = client().history(hours, 500);
            historyGrid.setItems(page.rows());
            historySummary.setText(String.format("成功 %d · 失败 %d · 进行中 %d",
                    page.success(), page.failed(), page.running()));
        } catch (SchedulerClient.SchedulerException e) {
            historyGrid.setItems(List.of());
            historySummary.setText("读历史失败：" + e.getMessage());
        }
    }

    /** 测试用。 */
    public String historySummaryText() {
        return historySummary.getText();
    }
    private void runAction(String action) {
        if (selectedRun == null) {
            errorText.setText("先选一个 run");
            return;
        }
        try {
            switch (action) {
                case "start" -> client().startRun(selectedRun.runId());
                case "stop" -> client().stopRun(selectedRun.runId());
                case "drop" -> client().dropRun(selectedRun.runId());
                default -> throw new IllegalStateException(action);
            }
            errorText.setText("");
            runGrid.setItems(client().runs());
            reloadTasks();
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("操作失败：" + e.getMessage());
        }
    }

    private Map<String, Object> taskPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskName", taskName.getValue());
        payload.put("taskScript", taskScript.getValue());
        payload.put("taskScriptType", taskType.getValue());
        payload.put("taskRunPolicy", taskPolicy.getValue());
        if ("CRONTAB".equals(taskPolicy.getValue())) {
            payload.put("taskCrontabExpr", cron.getValue());
        }
        if ("INTERVAL".equals(taskPolicy.getValue())) {
            payload.put("taskInterval", 60);
        }
        payload.put("taskTimeout", taskTimeout.getValue() == null ? 0 : taskTimeout.getValue());
        payload.put("taskEnabled", taskEnabled.getValue());
        payload.put("taskGroup", taskGroup.getValue());
        payload.put("taskStartupOrder", taskOrder.getValue() == null ? 0 : taskOrder.getValue());
        payload.put("taskDescription", taskDesc.getValue());
        return payload;
    }

    private void createTask() {
        if (selectedRun == null) {
            errorText.setText("先选一个 run");
            return;
        }
        try {
            client().createTask(selectedRun.runId(), taskPayload());
            errorText.setText("");
            reloadTasks();
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("建任务失败：" + e.getMessage());
        }
    }

    private void updateTask() {
        if (selectedRun == null || selectedTask == null) {
            errorText.setText("先选一个任务");
            return;
        }
        try {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("taskScript", taskScript.getValue());
            patch.put("taskScriptType", taskType.getValue());
            patch.put("taskRunPolicy", taskPolicy.getValue());
            if ("CRONTAB".equals(taskPolicy.getValue())) {
                patch.put("taskCrontabExpr", cron.getValue());
            }
            patch.put("taskTimeout", taskTimeout.getValue() == null ? 0 : taskTimeout.getValue());
            patch.put("taskEnabled", taskEnabled.getValue());
            patch.put("taskGroup", taskGroup.getValue());
            patch.put("taskStartupOrder", taskOrder.getValue() == null ? 0 : taskOrder.getValue());
            patch.put("taskDescription", taskDesc.getValue());
            client().updateTask(selectedRun.runId(), selectedTask.name(), patch);
            errorText.setText("");
            reloadTasks();
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("更新失败：" + e.getMessage());
        }
    }

    private void deleteTask() {
        if (selectedRun == null || selectedTask == null) {
            errorText.setText("先选一个任务");
            return;
        }
        try {
            client().deleteTask(selectedRun.runId(), selectedTask.name());
            errorText.setText("");
            reloadTasks();
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("删除失败：" + e.getMessage());
        }
    }

    private void scheduleTask() {
        if (selectedRun == null || selectedTask == null) {
            errorText.setText("先选一个任务");
            return;
        }
        try {
            client().scheduleTask(selectedRun.runId(), selectedTask.name());
            errorText.setText("");
            reloadTasks();
        } catch (SchedulerClient.SchedulerException e) {
            errorText.setText("登记调度失败：" + e.getMessage());
        }
    }

    private void showLog() {
        if (selectedRun == null) {
            errorText.setText("先选一个 run");
            return;
        }
        String task = logTask.getValue() != null ? logTask.getValue()
                : (selectedTask == null ? "" : selectedTask.name());
        if (task.isBlank()) {
            errorText.setText("先选一个任务");
            return;
        }
        try {
            int tail = logTail.getValue() == null ? 200 : logTail.getValue();
            logArea.setValue(client().taskLog(selectedRun.runId(), task, tail));
        } catch (SchedulerClient.SchedulerException e) {
            logArea.setValue("");
            errorText.setText("读日志失败（可能还没跑过）：" + e.getMessage());
        }
    }

    /** 测试用。 */
    public String errorText() {
        return errorText.getText();
    }
}
