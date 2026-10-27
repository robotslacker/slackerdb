package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.data.DataServiceClient;
import org.slackerdb.dbdesk.access.data.DataServiceDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据服务管理：列出、注册、注销、从 JSON 加载、保存到服务端、下载定义。
 */
@Route(value = "data-services", layout = MainLayout.class)
public class DataServicesView extends VerticalLayout {

    private final Grid<DataServiceDefinition> grid = new Grid<>(DataServiceDefinition.class, false);
    private final Span endpoint = new Span();
    private final Span status = new Span("就绪");
    private final HorizontalLayout downloadLinks = new HorizontalLayout();
    private final ObjectMapper json = new ObjectMapper();

    private final Button refresh = new Button("刷新");
    private final Button register = new Button("注册");
    private final Button load = new Button("加载 JSON");
    private final Button save = new Button("保存到服务端");
    private final Button download = new Button("下载 JSON");
    private final Button unregister = new Button("注销");

    private transient DataServiceClient client;

    public DataServicesView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("数据服务");
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span("当前连接没有实例级管理口，数据服务不可用。");
            guard.setId("data-service-guard");
            add(title, guard);
            return;
        }
        endpoint.setId("data-service-endpoint");
        endpoint.setText(ConnectionManager.current().managementUrl());
        status.setId("data-service-status");
        downloadLinks.setId("data-service-downloads");

        configureGrid();
        configureButtons();

        add(title,
                new HorizontalLayout(new Span("管理口："), endpoint),
                new HorizontalLayout(refresh, register, load, save, download, unregister),
                status,
                grid,
                downloadLinks);
        setFlexGrow(1, grid);

        refreshList();
    }

    private void configureGrid() {
        grid.setId("data-service-grid");
        grid.setWidthFull();
        grid.setHeight("420px");
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.addColumn(DataServiceDefinition::serviceType).setHeader("类型").setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::serviceName).setHeader("名称").setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::serviceVersion).setHeader("版本").setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::category).setHeader("分类").setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::description).setHeader("描述").setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::snapshotLimit).setHeader("快照限制").setAutoWidth(true);
        grid.addColumn(service -> parametersText(service.parameters())).setHeader("参数").setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::sql).setHeader("SQL").setAutoWidth(true);
    }

    private void configureButtons() {
        refresh.setId("data-service-refresh");
        refresh.addClickListener(event -> refreshList());

        register.setId("data-service-register");
        register.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        register.addClickListener(event -> openRegisterDialog());

        load.setId("data-service-load");
        load.addClickListener(event -> openLoadDialog());

        save.setId("data-service-save");
        save.addClickListener(event -> saveDefinitions());

        download.setId("data-service-download");
        download.addClickListener(event -> downloadDefinitions());

        unregister.setId("data-service-unregister");
        unregister.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        unregister.addClickListener(event -> confirmUnregister());
    }

    private void refreshList() {
        status.setText("读取中…");
        try {
            List<DataServiceDefinition> services = client().list();
            grid.setItems(services);
            status.setText(services.isEmpty() ? "没有已注册的数据服务" : "共 " + services.size() + " 条");
        } catch (Exception e) {
            grid.setItems(List.of());
            status.setText("读取失败：" + message(e));
        }
    }

    private void openRegisterDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("注册数据服务");
        dialog.setWidth("760px");

        TextField name = new TextField("服务名");
        name.setId("data-service-name");
        TextField version = new TextField("版本");
        version.setId("data-service-version");
        TextField category = new TextField("分类");
        category.setId("data-service-category");
        TextField searchPath = new TextField("Search Path");
        searchPath.setId("data-service-search-path");
        TextField snapshotLimit = new TextField("快照限制（如 5m）");
        snapshotLimit.setId("data-service-snapshot-limit");
        ComboBox<String> type = new ComboBox<>("类型");
        type.setId("data-service-type");
        type.setItems("GET", "POST");
        type.setValue("GET");

        TextArea sql = new TextArea("SQL");
        sql.setId("data-service-sql");
        sql.setWidthFull();
        sql.setHeight("150px");
        TextArea description = new TextArea("描述");
        description.setId("data-service-description");
        description.setWidthFull();
        TextArea parameters = new TextArea("参数（JSON 数组，可空）");
        parameters.setId("data-service-parameters");
        parameters.setWidthFull();
        parameters.setHeight("100px");
        parameters.setPlaceholder("[{\"name\":\"userId\",\"defaultValue\":\"1\"}]");
        Span dialogStatus = new Span();
        dialogStatus.setId("data-service-dialog-status");

        Button submit = new Button("注册", event -> {
            try {
                List<DataServiceDefinition.Parameter> parsed = parseParameters(parameters.getValue());
                DataServiceDefinition definition = new DataServiceDefinition(
                        name.getValue(), version.getValue(), category.getValue(), type.getValue(),
                        sql.getValue(), description.getValue(), searchPath.getValue(),
                        snapshotLimit.getValue(), parsed);
                client().register(definition);
                dialog.close();
                status.setText("已注册：" + definition.serviceName());
                refreshList();
            } catch (Exception e) {
                dialogStatus.setText("注册失败：" + message(e));
            }
        });
        submit.setId("data-service-submit");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("取消", event -> dialog.close());

        dialog.add(new VerticalLayout(
                new HorizontalLayout(name, version, type),
                new HorizontalLayout(category, searchPath, snapshotLimit),
                sql, description, parameters, dialogStatus,
                new HorizontalLayout(submit, cancel)));
        dialog.open();
    }

    private void openLoadDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("从 JSON 加载数据服务定义");
        Span dialogStatus = new Span("选择 .service 或 .json 文件");
        dialogStatus.setId("data-service-load-status");

        Upload upload = new Upload(event -> {
            try (var in = event.getInputStream()) {
                String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                client().load(raw);
                dialogStatus.setText("加载成功");
                dialog.close();
                status.setText("已加载 JSON 定义");
                refreshList();
            } catch (IOException e) {
                dialogStatus.setText("读取文件失败：" + e.getMessage());
            } catch (Exception e) {
                dialogStatus.setText("加载失败：" + message(e));
            }
        });
        upload.setId("data-service-upload");
        upload.setMaxFiles(1);
        upload.setAcceptedFileTypes(".service", ".json");
        upload.setMaxFileSize(20 * 1024 * 1024);

        Button close = new Button("关闭", event -> dialog.close());
        dialog.add(new VerticalLayout(dialogStatus, upload, new HorizontalLayout(close)));
        dialog.open();
    }

    private void saveDefinitions() {
        try {
            DataServiceClient.SaveResult result = client().save();
            status.setText("已保存" + (result.savedPath() == null ? "" : "：" + result.savedPath()));
        } catch (Exception e) {
            status.setText("保存失败：" + message(e));
        }
    }

    private void downloadDefinitions() {
        try {
            byte[] payload = client().download();
            downloadLinks.removeAll();
            downloadLinks.add(DownloadSupport.anchor("services.json", payload));
            status.setText("已生成下载：" + payload.length + " 字节");
        } catch (Exception e) {
            status.setText("下载失败：" + message(e));
        }
    }

    private void confirmUnregister() {
        DataServiceDefinition selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            status.setText("先选中一条数据服务");
            return;
        }
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("注销数据服务？");
        dialog.setText(selected.serviceType() + " " + selected.serviceName() + "@" + selected.serviceVersion());
        dialog.setCancelable(true);
        dialog.setConfirmText("注销");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            try {
                client().unregister(selected.serviceName(), selected.serviceVersion(), selected.serviceType());
                status.setText("已注销：" + selected.serviceName());
                refreshList();
            } catch (Exception e) {
                status.setText("注销失败：" + message(e));
            }
        });
        dialog.open();
    }

    private List<DataServiceDefinition.Parameter> parseParameters(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = json.readTree(text);
            if (!root.isArray()) {
                throw new IllegalArgumentException("参数必须是 JSON 数组");
            }
            List<DataServiceDefinition.Parameter> result = new ArrayList<>();
            for (JsonNode item : root) {
                result.add(new DataServiceDefinition.Parameter(
                        item.path("name").asText(null),
                        item.path("defaultValue").asText(null)));
            }
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("参数 JSON 非法：" + e.getMessage(), e);
        }
    }

    private static String parametersText(List<DataServiceDefinition.Parameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (DataServiceDefinition.Parameter parameter : parameters) {
            parts.add(parameter.name() + "=" + (parameter.defaultValue() == null ? "" : parameter.defaultValue()));
        }
        return String.join(", ", parts);
    }

    private DataServiceClient client() {
        if (client == null) {
            client = new DataServiceClient(ConnectionManager.current().managementUrl());
        }
        return client;
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** 测试用。 */
    public int serviceCount() {
        return (int) grid.getListDataView().getItems().count();
    }
}
