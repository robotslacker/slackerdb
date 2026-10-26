package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.provider.DataProvider;
import org.slackerdb.dbdesk.access.JdbcPageSource;
import org.slackerdb.dbdesk.access.sql.JdbcSqlExecutor;
import org.slackerdb.dbdesk.access.sql.ResultExporter;
import org.slackerdb.dbdesk.access.sql.SqlError;
import org.slackerdb.dbdesk.access.sql.SqlHistoryStore;
import org.slackerdb.dbdesk.access.sql.StatementResult;
import org.slackerdb.dbdesk.jsbridge.SqlEditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一个查询面板 = 编辑器 + 执行/取消 + 结果网格。工作台的"标签页"就是若干这个面板。
 *
 * <p><b>线程模型</b>（写错就会死锁或界面不刷新，所以写清楚）：</p>
 * <ul>
 *   <li>执行放到 {@link CompletableFuture} 的线程池里，**不占 UI 锁**；</li>
 *   <li>结果回填统一走 {@code ui.access(...)} —— Vaadin 只允许持锁线程改组件树；</li>
 *   <li>取消从 UI 线程直接调 {@code executor.cancel()}，它只碰 volatile 的 Statement。</li>
 * </ul>
 *
 * <p><b>显示策略</b>：多语句只渲染**最后一条有结果集的语句**（前面的结果在状态行里报条数）。
 * 这是 M1 的取舍 —— 结果集分页签属于 P1 后续，不在这里堆。</p>
 */
public class QueryPanel extends VerticalLayout {

    private static final int MAX_ROWS = 1_000;

    private final SqlEditor editor;
    private final Button run = new Button("执行");
    private final Button cancel = new Button("取消");
    private final Button exportCsv = new Button("导出 CSV");
    private final Button exportCancel = new Button("取消导出");
    private final Button historyButton = new Button("历史");
    private final Button saveScript = new Button("保存脚本");
    private final Upload loadScript;
    private final Span connectionBanner = new Span();
    private final Button reconnect = new Button("重连");
    private final Span status = new Span("就绪");
    private final Span exportStatus = new Span("导出：未执行");
    private final Grid<List<Object>> grid = new Grid<>();

    private final String jdbcUrl;
    private final String idSuffix;
    private transient JdbcSqlExecutor executor;
    /** 懒加载翻页用的分页数据源（与执行层分开：执行层一条连接，翻页另一条，互不阻塞）。 */
    private transient JdbcPageSource pageSource;
    /** 每次执行自增，用来丢弃"上一次查询"迟到的异步结果（例如总行数统计）。 */
    private long querySequence;
    /** 导出取消标志（导出线程每 1000 行检查一次）。 */
    private final java.util.concurrent.atomic.AtomicBoolean exportCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 最近一次渲染到网格的查询语句；导出拿它重跑并流式写盘（界面上的行是截断/限量的）。 */
    private String lastQuerySql;
    /** 最近一次执行的脚本原文（把错误里的"语句内行号"换算成编辑器绝对行号要用）。 */
    private String currentScript = "";
    private final SqlHistoryStore history = sessionHistory();

    private static SqlHistoryStore sessionHistory() {
        var session = com.vaadin.flow.server.VaadinSession.getCurrent();
        if (session == null) {
            return new SqlHistoryStore();
        }
        SqlHistoryStore history = session.getAttribute(SqlHistoryStore.class);
        if (history == null) {
            history = new SqlHistoryStore();
            session.setAttribute(SqlHistoryStore.class, history);
        }
        return history;
    }

    public QueryPanel(String jdbcUrl, String initialSql) {
        this(jdbcUrl, initialSql, "");
    }

    /**
     * @param idSuffix 多标签时给 DOM id 加后缀（第一个标签用空串，保证既有选择器稳定）
     */
    public QueryPanel(String jdbcUrl, String initialSql, String idSuffix) {
        this.jdbcUrl = jdbcUrl;
        this.idSuffix = idSuffix;
        setSizeFull();
        setPadding(false);
        setSpacing(true);

        editor = new SqlEditor(initialSql);
        editor.setId("sql-editor" + idSuffix);
        editor.setWidthFull();
        editor.setHeight("200px");
        editor.addRunListener(event -> startExecution(event.getSql()));
        run.setId("run-button" + idSuffix);
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        run.addClickListener(event -> execute());

        cancel.setId("cancel-button" + idSuffix);
        cancel.setEnabled(false);
        cancel.addClickListener(event -> cancelExecution());

        status.setId("query-status" + idSuffix);

        exportCsv.setId("export-csv-button" + idSuffix);
        exportCsv.setEnabled(false);
        exportCsv.addClickListener(event -> openCsvExportDialog(lastQuerySql));

        exportCancel.setId("export-cancel-button" + idSuffix);
        exportCancel.setEnabled(false);
        exportCancel.addClickListener(event -> {
            exportCancelled.set(true);
            exportStatus.setText("导出：正在取消…");
        });

        exportStatus.setId("export-status" + idSuffix);

        historyButton.setId("history-button" + idSuffix);
        historyButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        historyButton.addClickListener(event -> openHistory());

        saveScript.setId("save-script" + idSuffix);
        saveScript.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        saveScript.addClickListener(event -> editor.readValue(this::openSaveScriptDialog));

        loadScript = new Upload(event -> {
            try (var input = event.getInputStream()) {
                editor.setValue(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                status.setText("已加载脚本：" + event.getFileName());
            } catch (IOException e) {
                status.setText("加载脚本失败：" + e.getMessage());
            }
        });
        loadScript.setId("load-script" + idSuffix);
        loadScript.setMaxFiles(1);
        loadScript.setAcceptedFileTypes(".sql", ".txt");
        Button loadScriptButton = new Button("加载脚本");
        loadScriptButton.setId("load-script-button" + idSuffix);
        loadScriptButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        loadScript.setUploadButton(loadScriptButton);

        grid.setId("result-grid" + idSuffix);
        grid.setSizeFull();
        grid.addThemeVariants(GridVariant.LUMO_COMPACT);
        grid.setColumnReorderingAllowed(false);

        connectionBanner.setId("connection-banner" + idSuffix);
        connectionBanner.setVisible(false);
        connectionBanner.getStyle()
                .set("color", "var(--lumo-error-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        reconnect.setId("reconnect-button" + idSuffix);
        reconnect.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        reconnect.addClickListener(event -> reconnect());
        HorizontalLayout connectionRow = new HorizontalLayout(connectionBanner, reconnect);
        connectionRow.setId("connection-row" + idSuffix);
        connectionRow.setVisible(false);
        connectionRow.setSpacing(true);

        add(editor, new HorizontalLayout(run, cancel, exportCsv, exportCancel,
                        historyButton, saveScript, loadScript, status),
                connectionRow, grid,
                new HorizontalLayout(exportStatus));
        setFlexGrow(1, grid);
    }

    /** SQLState 08xxx：连接断了/被杀了。此时提示"重连"，而不是让用户怀疑 SQL。 */
    private void showConnectionFailure(StatementResult result) {
        connectionBanner.setText(String.format("连接已断开（SQLState %s）：请点「重连」后重新执行",
                result.errorState()));
        connectionBanner.setVisible(true);
        connectionBanner.getParent().ifPresent(parent -> parent.setVisible(true));
    }

    private void hideConnectionFailure() {
        connectionBanner.setVisible(false);
        connectionBanner.getParent().ifPresent(parent -> parent.setVisible(false));
    }

    /** 丢掉旧连接（执行器 + 翻页源），下一次执行会重新建。 */
    private void reconnect() {
        if (executor != null) {
            executor.close();
            executor = null;
        }
        if (pageSource != null) {
            pageSource.close();
            pageSource = null;
        }
        hideConnectionFailure();
        status.setText("已重建连接，请重新执行");
    }

    /**
     * 执行入口：**先向浏览器要一次当前内容**再执行。
     *
     * <p>不能直接用 {@code editor.getValue()}：那是"客户端改动 → change 事件（300 ms 防抖）"回传的副本，
     * 点执行的一瞬间可能还没同步过来，会跑上一版 SQL（实测踩到）。</p>
     */
    private void execute() {
        editor.readValue(this::startExecution);
    }

    private void startExecution(String script) {
        if (script == null || script.isBlank()) {
            status.setText("请先选中要执行的语句");
            return;
        }
        currentScript = script;
        run.setEnabled(false);
        cancel.setEnabled(true);
        status.setText("执行中…");

        UI ui = getUI().orElse(null);
        CompletableFuture
                .supplyAsync(() -> executor().execute(script, MAX_ROWS))
                .whenComplete((results, error) -> {
                    if (ui == null) {
                        return;
                    }
                    ui.access(() -> render(results, error));
                });
    }

    private void render(List<StatementResult> results, Throwable error) {
        run.setEnabled(true);
        cancel.setEnabled(false);

        if (error != null) {
            status.setText("执行失败：" + error.getMessage());
            return;
        }
        if (results == null || results.isEmpty()) {
            status.setText("没有可执行的语句");
            return;
        }

        StatementResult last = results.get(results.size() - 1);
        StatementResult rendered = results.stream()
                .filter(StatementResult::query)
                .reduce((first, second) -> second)
                .orElse(last);

        if (last.failed()) {
            if (last.connectionFailure()) {
                showConnectionFailure(last);
            } else {
                hideConnectionFailure();
            }
            SqlError parsed = SqlError.parse(last.error());
            String where = parsed.hasPosition()
                    ? String.format("（第 %d 行第 %d 列）", parsed.line(), parsed.column())
                    : "";
            status.setText(String.format("%d 条语句，第 %d 条失败%s：%s",
                    results.size(), results.size(), where, parsed.summary()));
            if (parsed.hasPosition()) {
                // 错误文案里的 LINE 是"这条语句内部"的行号，换算成编辑器里的绝对行
                editor.setCursor(lineOffset(currentScript, last.sql()) + parsed.line(), parsed.column());
            }
        } else {
            status.setText(String.format("%d 条语句 · 最后一条 %s · %d 行 · %d ms%s",
                    results.size(),
                    last.query() ? "查询" : "更新 " + last.updateCount() + " 行",
                    last.rowCount(),
                    last.elapsedMillis(),
                    last.truncated() ? " · 有单元格被截断(>1MB)" : ""));
        }

        renderGrid(rendered);
        if (!last.failed()) {
            hideConnectionFailure();
            history.append(currentScript, Instant.now());
        }
    }

    private void renderGrid(StatementResult result) {
        grid.removeAllColumns();
        if (!result.query() || result.columns().isEmpty()) {
            lastQuerySql = null;
            exportCsv.setEnabled(false);
            exportStatus.setText("导出：当前没有查询结果");
            grid.setItems(List.of());
            return;
        }
        lastQuerySql = result.sql();
        exportCsv.setEnabled(true);
        exportStatus.setText("导出：可导出全部行（不含界面截断）");
        for (int i = 0; i < result.columns().size(); i++) {
            int index = i;
            String type = i < result.columnTypes().size() ? result.columnTypes().get(i) : null;
            Grid.Column<List<Object>> column = grid.addColumn(row -> row.get(index));
            column.setHeader(result.columns().get(i));
            column.setAutoWidth(true);
            if (type != null) {
                column.setTooltipGenerator(row -> type);
            }
        }
        grid.setItems(result.rows());

        if (result.rowsTruncated()) {
            attachLazyLoading(result.sql());
        }
    }

    /**
     * 结果超过首屏上限时，把网格换成**懒加载**：滚动到底部会按 {@code LIMIT/OFFSET} 继续取页
     * （复用 {@link JdbcPageSource}，与 M0-V2 验证过的取数策略一致）。
     *
     * <p>总行数要单独查（{@code count(*)}），所以放在后台线程里做，回来再挂 DataProvider ——
     * 不让 UI 线程等一次全表统计。</p>
     */
    private void attachLazyLoading(String sql) {
        long sequence = ++querySequence;
        status.setText(status.getText() + " · 统计总行数…");

        UI ui = getUI().orElse(null);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return pageSource().count(sql);
                    } catch (SQLException e) {
                        return -1L;
                    }
                })
                .whenComplete((total, error) -> {
                    if (ui == null) {
                        return;
                    }
                    ui.access(() -> {
                        if (sequence != querySequence) {
                            return; // 用户已经跑了新查询，这次统计作废
                        }
                        if (error != null || total == null || total <= 0) {
                            status.setText(status.getText().replace(" · 统计总行数…", " · 总行数未知（仅显示前 "
                                    + MAX_ROWS + " 行，可导出全部）"));
                            return;
                        }
                        grid.setItems(DataProvider.fromCallbacks(
                                query -> {
                                    try {
                                        return pageSource().fetch(sql, query.getOffset(), query.getLimit())
                                                .rows().stream();
                                    } catch (SQLException e) {
                                        throw new IllegalStateException("翻页取数失败：" + e.getMessage(), e);
                                    }
                                },
                                query -> (int) Math.min(total, Integer.MAX_VALUE)));
                        status.setText(status.getText()
                                .replace(" · 统计总行数…", String.format(" · 共 %,d 行（可滚动加载）", total)));
                    });
                });
    }

    /** 供别的面板（元数据树"导出该表"）复用 CSV 导出流程。 */
    public void exportSql(String sql, ResultExporter.Format format) {
        openCsvExportDialog(sql);
    }

    private void openCsvExportDialog(String sql) {
        if (sql == null || sql.isBlank()) {
            exportStatus.setText("导出：先执行一条查询");
            return;
        }
        openPathDialog("导出 CSV", defaultPath("exports", "query", "csv"),
                path -> exportCsvTo(path, sql));
    }

    private void openSaveScriptDialog(String sql) {
        if (sql == null || sql.isBlank()) {
            status.setText("当前编辑器没有可保存的脚本");
            return;
        }
        openPathDialog("保存脚本", defaultPath("scripts", "query", "sql"), path -> {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, sql, StandardCharsets.UTF_8);
                status.setText("已保存脚本：" + path);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    /** 保存脚本 / 导出 CSV 共用的"选择保存位置"对话框。 */
    private void openPathDialog(String title, String defaultPath, java.util.function.Consumer<Path> action) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        TextField location = new TextField("保存位置");
        location.setId("save-path" + idSuffix);
        location.setWidth("620px");
        location.setValue(defaultPath);
        Span error = new Span();
        error.getStyle().set("color", "var(--lumo-error-text-color)");
        Button confirm = new Button("保存", event -> {
            String text = location.getValue();
            if (text == null || text.isBlank()) {
                error.setText("请填写保存位置");
                return;
            }
            Path path;
            try {
                path = Path.of(text.strip());
            } catch (RuntimeException e) {
                error.setText("保存位置不合法：" + e.getMessage());
                return;
            }
            try {
                action.accept(path);
                dialog.close();
            } catch (Exception e) {
                error.setText("保存失败：" + e.getMessage());
            }
        });
        confirm.setId("save-path-confirm" + idSuffix);
        confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelButton = new Button("取消", event -> dialog.close());
        cancelButton.setId("save-path-cancel" + idSuffix);
        dialog.add(new VerticalLayout(location, error, new HorizontalLayout(confirm, cancelButton)));
        dialog.open();
    }

    private static String defaultPath(String folder, String prefix, String extension) {
        return Path.of(System.getProperty("user.home"), "dbdesk", folder,
                prefix + "-" + System.currentTimeMillis() + "." + extension).toString();
    }

    /**
     * 导出**全部行**：重跑最后一条查询并流式写盘（界面上的表格是截断且限量的，不能拿来导出）。
     */
    private void exportCsvTo(Path target, String sql) {
        exportCancelled.set(false);
        exportCsv.setEnabled(false);
        exportCancel.setEnabled(true);
        exportStatus.setText("导出中…（全部行）");

        UI ui = getUI().orElse(null);
        long[] lastReported = {0};
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Path parent = target.toAbsolutePath().getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        return ResultExporter.export(jdbcUrl, sql, ResultExporter.Format.CSV, target,
                                exportCancelled::get,
                                rows -> {
                                    // 进度回调在导出线程上：切回 UI 线程，并且只在每 5 万行时刷一次
                                    if (ui != null && rows - lastReported[0] >= 50_000) {
                                        lastReported[0] = rows;
                                        ui.access(() -> exportStatus.setText(
                                                String.format("导出中… 已写 %,d 行", rows)));
                                    }
                                });
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                })
                .whenComplete((result, error) -> {
                    if (ui == null) {
                        return;
                    }
                    ui.access(() -> {
                        exportCsv.setEnabled(true);
                        exportCancel.setEnabled(false);
                        if (error != null) {
                            Throwable root = rootCause(error);
                            if (root instanceof ResultExporter.ExportCancelledException) {
                                exportStatus.setText(root.getMessage());
                            } else {
                                exportStatus.setText("导出失败：" + rootMessage(error));
                            }
                            return;
                        }
                        exportStatus.setText(String.format("已导出 %,d 行到 %s（%,d 字节 / %d ms）",
                                result.rows(), target, result.bytes(), result.elapsedMillis()));
                    });
                });
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 出错语句在脚本里的起始行偏移（0-based），用来把"语句内行号"换算成编辑器行号。 */
    private static int lineOffset(String script, String statement) {
        if (script == null || statement == null) {
            return 0;
        }
        int index = script.indexOf(statement);
        if (index < 0) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < index; i++) {
            if (script.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 本地历史：列出当前会话最近 20 条，选中后回填编辑器。 */
    private void openHistory() {
        List<SqlHistoryStore.Entry> entries = history.recent(20);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("SQL 历史（当前会话，最近 " + entries.size() + " 条）");
        dialog.setWidth("720px");

        ListBox<SqlHistoryStore.Entry> list = new ListBox<>();
        list.setId("history-list" + idSuffix);
        list.setItems(entries);
        list.setItemLabelGenerator(entry -> entry.at().replace("T", " ").replace("Z", "")
                + "  ·  " + entry.preview());
        list.setHeight("360px");
        if (!entries.isEmpty()) {
            list.setValue(entries.get(0));
        }

        Button insert = new Button("插入到编辑器", event -> {
            SqlHistoryStore.Entry selected = list.getValue();
            if (selected != null) {
                editor.setValue(selected.sql());
                dialog.close();
            }
        });
        insert.setId("history-insert" + idSuffix);
        insert.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button close = new Button("关闭", event -> dialog.close());

        dialog.add(new VerticalLayout(list, new HorizontalLayout(insert, close)));
        dialog.open();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private void cancelExecution() {
        boolean cancelled = executor != null && executor.cancel();
        status.setText(cancelled ? "已发出取消…" : "没有正在执行的语句");
    }

    private JdbcSqlExecutor executor() {
        if (executor == null) {
            executor = new JdbcSqlExecutor(jdbcUrl);
        }
        return executor;
    }

    private JdbcPageSource pageSource() {
        if (pageSource == null) {
            pageSource = new JdbcPageSource(jdbcUrl);
        }
        return pageSource;
    }

    /** 测试用：当前状态文案。 */
    public String statusText() {
        return status.getText();
    }


    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (executor != null) {
            executor.close();
            executor = null;
        }
        if (pageSource != null) {
            pageSource.close();
            pageSource = null;
        }
    }
}
