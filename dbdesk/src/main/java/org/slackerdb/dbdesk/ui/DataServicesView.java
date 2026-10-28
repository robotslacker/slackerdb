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
    private final Span status = new Span(I18n.t("dataService.status.ready"));
    private final HorizontalLayout downloadLinks = new HorizontalLayout();
    private final ObjectMapper json = new ObjectMapper();

    private final Button refresh = new Button(I18n.t("common.refresh"));
    private final Button register = new Button(I18n.t("dataService.button.register"));
    private final Button load = new Button(I18n.t("dataService.button.loadJson"));
    private final Button save = new Button(I18n.t("dataService.button.saveToServer"));
    private final Button download = new Button(I18n.t("dataService.button.downloadJson"));
    private final Button unregister = new Button(I18n.t("dataService.button.unregister"));

    private transient DataServiceClient client;

    public DataServicesView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.dataServices");

        H2 title = new H2(I18n.t("dataService.title"));
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("dataService.guard"));
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
                new HorizontalLayout(new Span(I18n.t("management.label")), endpoint),
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
        grid.addColumn(DataServiceDefinition::serviceType)
                .setHeader(I18n.t("dataService.column.type")).setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::serviceName)
                .setHeader(I18n.t("dataService.column.name")).setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::serviceVersion)
                .setHeader(I18n.t("dataService.column.version")).setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::category)
                .setHeader(I18n.t("dataService.column.category")).setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::description)
                .setHeader(I18n.t("dataService.column.description")).setAutoWidth(true);
        grid.addColumn(DataServiceDefinition::snapshotLimit)
                .setHeader(I18n.t("dataService.column.snapshotLimit")).setAutoWidth(true);
        grid.addColumn(service -> parametersText(service.parameters()))
                .setHeader(I18n.t("dataService.column.parameters")).setAutoWidth(true);
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
        status.setText(I18n.t("common.loading"));
        try {
            List<DataServiceDefinition> services = client().list();
            grid.setItems(services);
            status.setText(services.isEmpty()
                    ? I18n.t("dataService.empty")
                    : I18n.t("dataService.count", services.size()));
        } catch (Exception e) {
            grid.setItems(List.of());
            status.setText(I18n.t("dataService.error.loadFailed", I18n.message(e)));
        }
    }

    private void openRegisterDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(I18n.t("dataService.register.title"));
        dialog.setWidth("760px");

        TextField name = new TextField(I18n.t("dataService.field.name"));
        name.setId("data-service-name");
        TextField version = new TextField(I18n.t("dataService.field.version"));
        version.setId("data-service-version");
        TextField category = new TextField(I18n.t("dataService.field.category"));
        category.setId("data-service-category");
        TextField searchPath = new TextField("Search Path");
        searchPath.setId("data-service-search-path");
        TextField snapshotLimit = new TextField(I18n.t("dataService.field.snapshotLimit"));
        snapshotLimit.setId("data-service-snapshot-limit");
        ComboBox<String> type = new ComboBox<>(I18n.t("dataService.field.type"));
        type.setId("data-service-type");
        type.setItems("GET", "POST");
        type.setValue("GET");

        TextArea sql = new TextArea("SQL");
        sql.setId("data-service-sql");
        sql.setWidthFull();
        sql.setHeight("150px");
        TextArea description = new TextArea(I18n.t("dataService.field.description"));
        description.setId("data-service-description");
        description.setWidthFull();
        TextArea parameters = new TextArea(I18n.t("dataService.field.parameters"));
        parameters.setId("data-service-parameters");
        parameters.setWidthFull();
        parameters.setHeight("100px");
        parameters.setPlaceholder("[{\"name\":\"userId\",\"defaultValue\":\"1\"}]");
        Span dialogStatus = new Span();
        dialogStatus.setId("data-service-dialog-status");

        Button submit = new Button(I18n.t("dataService.button.register"), event -> {
            try {
                List<DataServiceDefinition.Parameter> parsed = parseParameters(parameters.getValue());
                DataServiceDefinition definition = new DataServiceDefinition(
                        name.getValue(), version.getValue(), category.getValue(), type.getValue(),
                        sql.getValue(), description.getValue(), searchPath.getValue(),
                        snapshotLimit.getValue(), parsed);
                client().register(definition);
                dialog.close();
                status.setText(I18n.t("dataService.status.registered", definition.serviceName()));
                refreshList();
            } catch (Exception e) {
                dialogStatus.setText(I18n.t("dataService.error.registerFailed", I18n.message(e)));
            }
        });
        submit.setId("data-service-submit");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button(I18n.t("common.cancel"), event -> dialog.close());

        dialog.add(new VerticalLayout(
                new HorizontalLayout(name, version, type),
                new HorizontalLayout(category, searchPath, snapshotLimit),
                sql, description, parameters, dialogStatus,
                new HorizontalLayout(submit, cancel)));
        dialog.open();
    }

    private void openLoadDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(I18n.t("dataService.load.title"));
        Span dialogStatus = new Span(I18n.t("dataService.load.hint"));
        dialogStatus.setId("data-service-load-status");

        Upload upload = new Upload(event -> {
            try (var in = event.getInputStream()) {
                String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                client().load(raw);
                dialogStatus.setText(I18n.t("dataService.status.loaded"));
                dialog.close();
                status.setText(I18n.t("dataService.status.loadedJson"));
                refreshList();
            } catch (IOException e) {
                dialogStatus.setText(I18n.t("dataService.error.readFileFailed", I18n.message(e)));
            } catch (Exception e) {
                dialogStatus.setText(I18n.t("dataService.error.loadJsonFailed", I18n.message(e)));
            }
        });
        upload.setId("data-service-upload");
        UploadTexts.apply(upload);
        upload.setMaxFiles(1);
        upload.setAcceptedFileTypes(".service", ".json");
        upload.setMaxFileSize(20 * 1024 * 1024);

        Button close = new Button(I18n.t("common.close"), event -> dialog.close());
        dialog.add(new VerticalLayout(dialogStatus, upload, new HorizontalLayout(close)));
        dialog.open();
    }

    private void saveDefinitions() {
        try {
            DataServiceClient.SaveResult result = client().save();
            status.setText(result.savedPath() == null
                    ? I18n.t("dataService.status.saved")
                    : I18n.t("dataService.status.savedTo", result.savedPath()));
        } catch (Exception e) {
            status.setText(I18n.t("dataService.error.saveFailed", I18n.message(e)));
        }
    }

    private void downloadDefinitions() {
        try {
            byte[] payload = client().download();
            downloadLinks.removeAll();
            downloadLinks.add(DownloadSupport.anchor("services.json", payload));
            status.setText(I18n.t("dataService.status.downloadReady", payload.length));
        } catch (Exception e) {
            status.setText(I18n.t("dataService.error.downloadFailed", I18n.message(e)));
        }
    }

    private void confirmUnregister() {
        DataServiceDefinition selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            status.setText(I18n.t("dataService.unregister.required"));
            return;
        }
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(I18n.t("dataService.unregister.title"));
        dialog.setText(selected.serviceType() + " " + selected.serviceName() + "@" + selected.serviceVersion());
        dialog.setCancelable(true);
        dialog.setCancelText(I18n.t("common.cancel"));
        dialog.setConfirmText(I18n.t("dataService.button.unregister"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            try {
                client().unregister(selected.serviceName(), selected.serviceVersion(), selected.serviceType());
                status.setText(I18n.t("dataService.status.unregistered", selected.serviceName()));
                refreshList();
            } catch (Exception e) {
                status.setText(I18n.t("dataService.error.unregisterFailed", I18n.message(e)));
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
                throw new IllegalArgumentException(I18n.t("dataService.error.parametersNotArray"));
            }
            List<DataServiceDefinition.Parameter> result = new ArrayList<>();
            for (JsonNode item : root) {
                result.add(new DataServiceDefinition.Parameter(
                        item.path("name").asText(null),
                        item.path("defaultValue").asText(null)));
            }
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    I18n.t("dataService.error.parametersInvalid", I18n.message(e)), e);
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

    /** 测试用。 */
    public int serviceCount() {
        return (int) grid.getListDataView().getItems().count();
    }
}
