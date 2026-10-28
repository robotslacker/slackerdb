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

    private final ComboBox<String> table = new ComboBox<>(I18n.t("import.target"));
    private final TextField newTable = new TextField(I18n.t("import.newTable"));
    private final Button loadTables = new Button(I18n.t("import.readTables"));

    private Upload upload;
    private final ComboBox<Delimiter> delimiter = new ComboBox<>(I18n.t("import.delimiter"));
    private final Checkbox header = new Checkbox(I18n.t("import.header"));
    private final TextField nullText = new TextField(I18n.t("import.nullText"));
    private final ComboBox<Encoding> charset = new ComboBox<>(I18n.t("import.encoding"));
    private final IntegerField batchSize = new IntegerField(I18n.t("import.batchSize"));
    private final ComboBox<ErrorPolicy> errorPolicy = new ComboBox<>(I18n.t("import.errorPolicy"));

    private final Grid<List<String>> preview = new Grid<>();
    private final Grid<Mapping> mapping = new Grid<>(Mapping.class, false);
    private final Button run = new Button(I18n.t("import.start"));
    private final Button cancel = new Button(I18n.t("common.cancel"));
    private final Span status = new Span(I18n.t("import.status.ready"));

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

    /**
     * 分隔符选项：显示文案走 {@link #labelKey()}，传给导入器的仍是原来的
     * {@link #wireValue()}（{@code \t} 也保持原来的两字符写法）。
     */
    private enum Delimiter {
        COMMA("import.delimiter.comma", ","),
        SEMICOLON("import.delimiter.semicolon", ";"),
        TAB("import.delimiter.tab", "\\t"),
        PIPE("import.delimiter.pipe", "|");

        private final String labelKey;
        private final String wireValue;

        Delimiter(String labelKey, String wireValue) {
            this.labelKey = labelKey;
            this.wireValue = wireValue;
        }

        String labelKey() {
            return labelKey;
        }

        String wireValue() {
            return wireValue;
        }
    }

    /**
     * 编码选项：显示文案与 value 分开，value 仍是原来传给导入器的
     * {@code UTF-8} / {@code GBK}，改语言不会影响解析行为。
     */
    private enum Encoding {
        UTF8("import.encoding.utf8", "UTF-8"),
        GBK("import.encoding.gbk", "GBK");

        private final String labelKey;
        private final String wireValue;

        Encoding(String labelKey, String wireValue) {
            this.labelKey = labelKey;
            this.wireValue = wireValue;
        }

        String labelKey() {
            return labelKey;
        }

        String wireValue() {
            return wireValue;
        }
    }

    /**
     * 错误策略选项：原来把中文标签当 value 再和 {@code "遇到错误停止"} 比较；
     * 现在由 {@link #stopOnError()} 直接给出传给导入器的布尔值，行为不变。
     */
    private enum ErrorPolicy {
        STOP("import.errorPolicy.stop", true),
        SKIP("import.errorPolicy.skip", false);

        private final String labelKey;
        private final boolean stopOnError;

        ErrorPolicy(String labelKey, boolean stopOnError) {
            this.labelKey = labelKey;
            this.stopOnError = stopOnError;
        }

        String labelKey() {
            return labelKey;
        }

        boolean stopOnError() {
            return stopOnError;
        }
    }

    public ImportView() {
        setPadding(true);
        setSpacing(true);
        setHeightFull();
        I18n.setTitle("nav.import");

        H2 title = new H2(I18n.t("import.title"));
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("import.guard"));
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
                    status.setText(I18n.t("import.uploaded", target.toString(), Long.toString(size)));
                    refreshPreview();
                }));
            } catch (Exception e) {
                getUI().ifPresent(ui -> ui.access(() ->
                        status.setText(I18n.t("import.uploadFailed", I18n.message(e)))));
            }
        });
        upload.setId("import-upload");
        UploadTexts.apply(upload);
        upload.setMaxFiles(1);
        upload.setAcceptedFileTypes(".csv", ".txt");
        upload.setMaxFileSize(100 * 1024 * 1024);

        delimiter.setId("import-delimiter");
        delimiter.setItems(Delimiter.values());
        delimiter.setItemLabelGenerator(option -> I18n.t(option.labelKey()));
        delimiter.setValue(Delimiter.COMMA);
        header.setId("import-header");
        header.setValue(true);
        nullText.setId("import-null");
        nullText.setWidth("120px");
        charset.setId("import-charset");
        charset.setItems(Encoding.values());
        charset.setItemLabelGenerator(option -> I18n.t(option.labelKey()));
        charset.setValue(Encoding.UTF8);
        batchSize.setId("import-batch");
        batchSize.setValue(1_000);
        errorPolicy.setId("import-policy");
        errorPolicy.setItems(ErrorPolicy.values());
        errorPolicy.setItemLabelGenerator(option -> I18n.t(option.labelKey()));
        errorPolicy.setValue(ErrorPolicy.STOP);

        preview.setId("import-preview");
        preview.setWidthFull();
        preview.setHeight("200px");

        mapping.setId("import-mapping");
        mapping.setWidthFull();
        mapping.setHeight("220px");
        mapping.addColumn(Mapping::getSource).setHeader(I18n.t("import.column.csv")).setAutoWidth(true);
        mapping.addComponentColumn(row -> {
            ComboBox<String> target = new ComboBox<>();
            target.setItems(currentColumns);
            target.setAllowCustomValue(true);
            target.setValue(row.getTarget());
            target.setWidthFull();
            target.addValueChangeListener(event -> row.setTarget(event.getValue()));
            return target;
        }).setHeader(I18n.t("import.column.target")).setFlexGrow(1);

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
                new H3(I18n.t("import.preview")), preview,
                new H3(I18n.t("import.mapping")), mapping,
                new HorizontalLayout(run, cancel, status));
        setFlexGrow(1, mapping);

        loadTables();
    }

    private String jdbcUrl() {
        return ConnectionManager.current().jdbcUrl();
    }

    private CsvImporter.Options options() {
        String text = delimiter.getValue() == null
                ? Delimiter.COMMA.wireValue() : delimiter.getValue().wireValue();
        char separator = "\\t".equals(text) ? '\t' : (text.isEmpty() ? ',' : text.charAt(0));
        String encoding = charset.getValue() == null
                ? Encoding.UTF8.wireValue() : charset.getValue().wireValue();
        Charset cs = "GBK".equals(encoding) ? Charset.forName("GBK") : StandardCharsets.UTF_8;
        int size = batchSize.getValue() == null ? 1_000 : batchSize.getValue();
        return new CsvImporter.Options(separator, header.getValue(),
                nullText.getValue() == null ? "" : nullText.getValue(),
                size, errorPolicy.getValue() == ErrorPolicy.STOP, cs,
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
                status.setText(I18n.t("import.status.noTables"));
            }
        } catch (Exception e) {
            status.setText(I18n.t("import.status.loadTablesFailed", I18n.message(e)));
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
                        .setHeader(I18n.t("import.column.indexed", Integer.toString(i + 1)));
            }
            preview.setItems(rows);

            currentColumns = table.getValue() == null ? List.of()
                    : targetColumns().stream().map(MetadataReader.ColumnRef::name).toList();
            List<Mapping> mappings = new ArrayList<>();
            List<String> firstRow = rows.isEmpty() ? List.of() : rows.get(0);
            for (int i = 0; i < width; i++) {
                String source = header.getValue() && i < firstRow.size() ? firstRow.get(i)
                        : I18n.t("import.column.indexed", Integer.toString(i + 1));
                String target = currentColumns.stream()
                        .filter(name -> name.equalsIgnoreCase(source)).findFirst().orElse(null);
                mappings.add(new Mapping(source, target));
            }
            mapping.setItems(mappings);
        } catch (Exception e) {
            status.setText(I18n.t("import.status.previewFailed", I18n.message(e)));
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
            status.setText(I18n.t("import.status.noFile"));
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
                status.setText(I18n.t("import.status.createTableFailed", I18n.message(e)));
                return;
            }
        }
        if (target == null || target.isBlank()) {
            status.setText(I18n.t("import.status.noTarget"));
            return;
        }

        List<String> targets = new ArrayList<>();
        for (Mapping row : mapping.getListDataView().getItems().toList()) {
            targets.add(row.getTarget());
        }
        cancelFlag.set(false);
        run.setEnabled(false);
        cancel.setEnabled(true);
        status.setText(I18n.t("import.status.running"));

        String tableName = target;
        CsvImporter.Options opts = options();
        String url = jdbcUrl();
        var ui = getUI().orElse(null);
        CompletableFuture.supplyAsync(() -> {
            try {
                return CsvImporter.importInto(url, tableName, targets, file, opts, cancelFlag::get);
            } catch (Exception e) {
                // 保留 cause：I18n.message(...) 在 ui.access 里按当前语言渲染 Localized 异常
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((result, error) -> {
            if (ui == null) {
                return;
            }
            ui.access(() -> {
                run.setEnabled(true);
                cancel.setEnabled(false);
                if (error != null) {
                    status.setText(I18n.t("import.status.failed", I18n.message(error)));
                    return;
                }
                StringBuilder text = new StringBuilder(I18n.t(
                        result.cancelled() ? "import.status.doneCancelled" : "import.status.done",
                        Long.toString(result.imported()), Long.toString(result.failed()),
                        Long.toString(result.elapsedMillis())));
                for (CsvImporter.RowError rowError : result.errors()) {
                    text.append('\n').append(I18n.t("import.status.rowError",
                            Long.toString(rowError.line()), rowError.message()));
                }
                status.setText(text.toString());
            });
        });
    }

    /** 测试用。 */
    public String statusText() {
        return status.getText();
    }
}
