package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.DbDeskPaths;
import org.slackerdb.dbdesk.access.sql.CsvImporter;
import org.slackerdb.dbdesk.access.sql.MetadataReader;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据导入：CSV → 表。
 *
 * <p>为什么是一整页而不是向导：步骤只有"选文件 → 映射 → 执行"，一屏放得下；
 * 分步反而让"文件解析失败/列对不上"这类问题更难回看。页面按顺序从上到下排：
 * 目标连接 → 表 → 文件与选项 → 预览/映射 → 执行与结果。</p>
 *
 * <p>执行在后台线程跑，取消用 {@link AtomicBoolean}（导入器在每行之间检查它）。</p>
 */
@Route(value = "import", layout = MainLayout.class)
public class ImportView extends VerticalLayout {

    private final ComboBox<String> table = new ComboBox<>("目标表");
    private final TextField newTable = new TextField("或新建表（DDL，可选）");
    private final Button loadTables = new Button("读取表");

    private Upload upload;
    private final ComboBox<String> delimiter = new ComboBox<>("分隔符");
    private final Checkbox header = new Checkbox("首行是表头");
    private final TextField nullText = new TextField("NULL 文本");
    private final ComboBox<String> charset = new ComboBox<>("编码");
    private final IntegerField batchSize = new IntegerField("每批提交行数");
    private final ComboBox<String> errorPolicy = new ComboBox<>("错误策略");

    private final Grid<List<String>> preview = new Grid<>();
    private final Grid<Mapping> mapping = new Grid<>(Mapping.class, false);
    private final Button run = new Button("开始导入");
    private final Button cancel = new Button("取消");
    private final Span status = new Span("就绪");

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private Path csvFile;
    private List<String> currentColumns = List.of();

    /** 映射行：CSV 列名 + 目标列（null 表示忽略）。 */
    public static class Mapping {
        private final String source;
        private String target;

        Mapping(String source, String target) {
            this.source = source;
            this.target = target;
        }

        public String getSource() {
            return source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }

    public ImportView() {
        setPadding(true);
        setSpacing(true);
        setHeightFull();

        H2 title = new H2("数据导入（CSV → 表）");
        if (!ConnectionManager.current().hasManagement()) {
            Span guard = new Span("当前连接不可用，请先连接带管理口的服务端。");
            guard.setId("import-guard");
            add(title, guard);
            return;
        }

        table.setId("import-table");
        table.setAllowCustomValue(true);
        table.addValueChangeListener(event -> refreshPreview());
        table.setWidth("260px");
        newTable.setId("import-new-table");
        newTable.setWidth("320px");
        loadTables.setId("import-load-tables");
        loadTables.addClickListener(event -> loadTables());

        // Vaadin 24 的 Upload 走 UploadHandler：事件里直接拿 InputStream（SucceededEvent 没有）
        upload = new Upload(event -> {
            try (var in = event.getInputStream()) {
                Path dir = DbDeskPaths.uploadsDir();
                Files.createDirectories(dir);
                String original = event.getFileName() == null ? "upload.csv" : event.getFileName();
                String safeName = original.replaceAll("[^0-9A-Za-z._-]", "_");
                Path target = dir.resolve("upload-" + System.nanoTime() + "-" + safeName);
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                csvFile = target;
                long size = Files.size(target);
                getUI().ifPresent(ui -> ui.access(() -> {
                    status.setText("已上传到暂存目录：" + target + "（" + size + " 字节）");
                    refreshPreview();
                }));
            } catch (Exception e) {
                getUI().ifPresent(ui -> ui.access(() -> status.setText("读取上传失败：" + e.getMessage())));
            }
        });
        upload.setId("import-upload");
        upload.setMaxFiles(1);
        upload.setAcceptedFileTypes(".csv", ".txt");
        upload.setMaxFileSize(100 * 1024 * 1024);

        delimiter.setId("import-delimiter");
        delimiter.setItems(",", ";", "\\t", "|");
        delimiter.setValue(",");
        header.setId("import-header");
        header.setValue(true);
        nullText.setId("import-null");
        nullText.setWidth("120px");
        charset.setId("import-charset");
        charset.setItems("UTF-8", "GBK");
        charset.setValue("UTF-8");
        batchSize.setId("import-batch");
        batchSize.setValue(1_000);
        errorPolicy.setId("import-policy");
        errorPolicy.setItems("遇到错误停止", "跳过错误行继续");
        errorPolicy.setValue("遇到错误停止");

        preview.setId("import-preview");
        preview.setWidthFull();
        preview.setHeight("200px");

        mapping.setId("import-mapping");
        mapping.setWidthFull();
        mapping.setHeight("220px");
        mapping.addColumn(Mapping::getSource).setHeader("CSV 列").setAutoWidth(true);
        mapping.addComponentColumn(row -> {
            ComboBox<String> target = new ComboBox<>();
            target.setItems(currentColumns);
            target.setAllowCustomValue(true);
            target.setValue(row.getTarget());
            target.setWidthFull();
            target.addValueChangeListener(event -> row.setTarget(event.getValue()));
            return target;
        }).setHeader("目标列").setFlexGrow(1);

        run.setId("import-run");
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        run.addClickListener(event -> startImport());
        cancel.setId("import-cancel");
        cancel.setEnabled(false);
        cancel.addClickListener(event -> cancelFlag.set(true));
        status.setId("import-status");

        add(title,
                new HorizontalLayout(loadTables),
                new HorizontalLayout(table, newTable),
                new HorizontalLayout(upload),
                new HorizontalLayout(delimiter, header, nullText, charset, batchSize, errorPolicy),
                new H3("预览"), preview,
                new H3("列映射"), mapping,
                new HorizontalLayout(run, cancel, status));
        setFlexGrow(1, mapping);

        loadTables();
    }

    private String jdbcUrl() {
        return ConnectionManager.current().jdbcUrl();
    }

    private CsvImporter.Options options() {
        String text = delimiter.getValue();
        char separator = "\\t".equals(text) ? '\t' : (text == null || text.isEmpty() ? ',' : text.charAt(0));
        Charset cs = "GBK".equals(charset.getValue()) ? Charset.forName("GBK") : StandardCharsets.UTF_8;
        int size = batchSize.getValue() == null ? 1_000 : batchSize.getValue();
        return new CsvImporter.Options(separator, header.getValue(),
                nullText.getValue() == null ? "" : nullText.getValue(),
                size, "遇到错误停止".equals(errorPolicy.getValue()), cs,
                true, 10_000);
    }

    private void loadTables() {
        try {
            List<MetadataReader.TableRef> tables = MetadataReader.tables(jdbcUrl());
            List<String> names = tables.stream().map(MetadataReader.TableRef::qualifiedName).toList();
            table.setItems(names);
            if (table.getValue() == null && !names.isEmpty()) {
                table.setValue(names.get(0));
            }
            if (names.isEmpty()) {
                status.setText("这个库里没有表");
            }
        } catch (Exception e) {
            status.setText("读表失败：" + e.getMessage());
        }
    }

    /** 只使用上传到 dbdesk 暂存目录的文件。 */
    private Path resolvedFile() {
        return csvFile;
    }

    private void refreshPreview() {
        Path file = resolvedFile();
        if (file == null) {
            return;
        }
        try {
            List<List<String>> rows = CsvImporter.preview(file, options(), 20);
            preview.removeAllColumns();
            int width = rows.isEmpty() ? 0 : rows.get(0).size();
            for (int i = 0; i < width; i++) {
                int index = i;
                preview.addColumn(row -> index < row.size() ? row.get(index) : "")
                        .setHeader("列 " + (i + 1));
            }
            preview.setItems(rows);

            currentColumns = table.getValue() == null ? List.of()
                    : targetColumns().stream().map(MetadataReader.ColumnRef::name).toList();
            List<Mapping> mappings = new ArrayList<>();
            List<String> firstRow = rows.isEmpty() ? List.of() : rows.get(0);
            for (int i = 0; i < width; i++) {
                String source = header.getValue() && i < firstRow.size() ? firstRow.get(i) : "列 " + (i + 1);
                String target = currentColumns.stream()
                        .filter(name -> name.equalsIgnoreCase(source)).findFirst().orElse(null);
                mappings.add(new Mapping(source, target));
            }
            mapping.setItems(mappings);
        } catch (Exception e) {
            status.setText("预览失败：" + e.getMessage());
        }
    }

    private List<MetadataReader.ColumnRef> targetColumns() {
        String value = table.getValue();
        if (value == null || value.isBlank() || !value.contains(".")) {
            return List.of();
        }
        String[] parts = value.split("\\.", 2);
        try {
            return MetadataReader.columns(jdbcUrl(), parts[0], parts[1]);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void startImport() {
        Path file = resolvedFile();
        if (file == null) {
            status.setText("先上传一个 CSV 文件");
            return;
        }
        String target = table.getValue();
        if ((target == null || target.isBlank()) && newTable.getValue() != null && !newTable.getValue().isBlank()) {
            try {
                String ddl = newTable.getValue();
                org.slackerdb.dbdesk.access.sql.SqlDdl.execute(jdbcUrl(), ddl);
                target = org.slackerdb.dbdesk.access.sql.SqlDdl.tableNameFromDdl(ddl);
                table.setValue(target);
            } catch (Exception e) {
                status.setText("建表失败：" + e.getMessage());
                return;
            }
        }
        if (target == null || target.isBlank()) {
            status.setText("先选目标表（或写 DDL 新建）");
            return;
        }

        List<String> targets = new ArrayList<>();
        for (Mapping row : mapping.getListDataView().getItems().toList()) {
            targets.add(row.getTarget());
        }
        cancelFlag.set(false);
        run.setEnabled(false);
        cancel.setEnabled(true);
        status.setText("导入中…");

        String tableName = target;
        CsvImporter.Options opts = options();
        String url = jdbcUrl();
        var ui = getUI().orElse(null);
        CompletableFuture.supplyAsync(() -> {
            try {
                return CsvImporter.importInto(url, tableName, targets, file, opts, cancelFlag::get);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((result, error) -> {
            if (ui == null) {
                return;
            }
            ui.access(() -> {
                run.setEnabled(true);
                cancel.setEnabled(false);
                if (error != null) {
                    status.setText("导入失败：" + errorMessage(error));
                    return;
                }
                StringBuilder text = new StringBuilder(String.format("成功 %d 行，失败 %d 行，耗时 %d ms%s",
                        result.imported(), result.failed(), result.elapsedMillis(),
                        result.cancelled() ? "（已取消）" : ""));
                for (CsvImporter.RowError rowError : result.errors()) {
                    text.append("\n第 ").append(rowError.line()).append(" 行：").append(rowError.message());
                }
                status.setText(text.toString());
            });
        });
    }

    private static String errorMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 测试用。 */
    public String statusText() {
        return status.getText();
    }
}
