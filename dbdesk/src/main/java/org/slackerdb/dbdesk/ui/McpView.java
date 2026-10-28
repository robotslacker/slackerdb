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
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.mcp.McpAdminClient;
import org.slackerdb.dbdesk.access.mcp.McpResource;
import org.slackerdb.dbdesk.access.mcp.McpService;
import org.slackerdb.dbdesk.access.mcp.McpTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 管理：工具 / 资源 / 服务三个面板。
 *
 * <p>列表走 {@code POST /mcp} 的 JSON-RPC；注册、注销、load/save/dump 走 {@code /mcp/*}
 * 管理端点。资源注销使用规范路由 {@code /mcp/unregisterMCPResource}。</p>
 */
@Route(value = "mcp", layout = MainLayout.class)
public class McpView extends VerticalLayout {

    public McpView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.mcp");

        H2 title = new H2(I18n.t("mcp.title"));
        if (!ConnectionManager.current().hasInstanceManagement()) {
            Span guard = new Span(I18n.t("mcp.guard"));
            guard.setId("mcp-guard");
            add(title, guard);
            return;
        }
        Span endpoint = new Span(I18n.t("management.label") + ConnectionManager.current().managementUrl());
        endpoint.setId("mcp-endpoint");

        TabSheet tabs = new TabSheet();
        tabs.setId("mcp-tabs");
        tabs.setSizeFull();

        McpToolsPanel tools = new McpToolsPanel();
        McpResourcesPanel resources = new McpResourcesPanel();
        McpServicesPanel services = new McpServicesPanel();
        tabs.add(I18n.t("mcp.tab.tools"), tools);
        tabs.add(I18n.t("mcp.tab.resources"), resources);
        tabs.add(I18n.t("mcp.tab.services"), services);
        tabs.setSelectedIndex(0);

        add(title, endpoint, tabs);
        setFlexGrow(1, tabs);

        tools.init();
        resources.init();
        services.init();
    }

    /** 三个面板共用的工具栏、加载/保存/下载/注销逻辑。 */
    private abstract static class McpPanel<T> extends VerticalLayout {

        protected final McpAdminClient client;
        protected final Grid<T> grid;
        protected final Span status = new Span(I18n.t("mcp.status.ready"));
        protected final HorizontalLayout downloadLinks = new HorizontalLayout();
        protected final String idPrefix;

        protected McpPanel(String idPrefix) {
            this.idPrefix = idPrefix;
            setSizeFull();
            setPadding(false);
            setSpacing(true);
            this.client = new McpAdminClient(ConnectionManager.current().managementUrl());
            this.grid = new Grid<>();
            this.grid.setId(idPrefix + "-grid");
            this.grid.setWidthFull();
            this.grid.setHeight("420px");
            this.grid.setSelectionMode(Grid.SelectionMode.SINGLE);
            this.status.setId(idPrefix + "-status");
            this.downloadLinks.setId(idPrefix + "-downloads");
        }

        protected final void init() {
            configureGrid();

            Button refresh = new Button(I18n.t("common.refresh"));
            refresh.setId(idPrefix + "-refresh");
            refresh.addClickListener(event -> refresh());

            Button register = new Button(I18n.t("mcp.register"));
            register.setId(idPrefix + "-register");
            register.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            register.addClickListener(event -> openRegisterDialog());

            Button load = new Button(I18n.t("mcp.loadJson"));
            load.setId(idPrefix + "-load");
            load.addClickListener(event -> openLoadDialog());

            Button save = new Button(I18n.t("mcp.saveToServer"));
            save.setId(idPrefix + "-save");
            save.addClickListener(event -> saveAll());

            Button download = new Button(I18n.t("mcp.downloadJson"));
            download.setId(idPrefix + "-download");
            download.addClickListener(event -> downloadAll());

            Button unregister = new Button(I18n.t("mcp.unregister"));
            unregister.setId(idPrefix + "-unregister");
            unregister.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            unregister.addClickListener(event -> confirmUnregister());

            add(new HorizontalLayout(refresh, register, load, save, download, unregister),
                    status, grid, downloadLinks);
            setFlexGrow(1, grid);
            refresh();
        }

        protected abstract void configureGrid();

        protected abstract List<T> loadItems() throws Exception;

        protected abstract String itemLabel(T item);

        protected abstract void openRegisterDialog();

        protected abstract void unregister(T item) throws Exception;

        protected abstract void loadJson(String rawJson) throws Exception;

        protected abstract byte[] dump() throws Exception;

        protected abstract String dumpFilename();

        protected abstract String save() throws Exception;

        protected final void refresh() {
            status.setText(I18n.t("common.loading"));
            try {
                List<T> items = loadItems();
                grid.setItems(items);
                status.setText(items.isEmpty()
                        ? I18n.t("common.empty")
                        : I18n.t("mcp.count", items.size()));
            } catch (Exception e) {
                grid.setItems(List.of());
                status.setText(I18n.t("mcp.error.loadFailed", I18n.message(e)));
            }
        }

        private void openLoadDialog() {
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle(I18n.t("mcp.load.title"));
            Span dialogStatus = new Span(I18n.t("mcp.load.hint"));
            dialogStatus.setId(idPrefix + "-load-status");

            Upload upload = new Upload(event -> {
                try (var in = event.getInputStream()) {
                    String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    loadJson(raw);
                    dialog.close();
                    status.setText(I18n.t("mcp.loaded"));
                    refresh();
                } catch (IOException e) {
                    dialogStatus.setText(I18n.t("mcp.error.readFileFailed", I18n.message(e)));
                } catch (Exception e) {
                    dialogStatus.setText(I18n.t("mcp.error.loadJsonFailed", I18n.message(e)));
                }
            });
            upload.setId(idPrefix + "-upload");
            UploadTexts.apply(upload);
            upload.setMaxFiles(1);
            upload.setAcceptedFileTypes(".json", ".tool", ".resource", ".service");
            upload.setMaxFileSize(20 * 1024 * 1024);

            dialog.add(new VerticalLayout(dialogStatus, upload,
                    new HorizontalLayout(new Button(I18n.t("common.close"), event -> dialog.close()))));
            dialog.open();
        }

        private void saveAll() {
            try {
                status.setText(save());
            } catch (Exception e) {
                status.setText(I18n.t("mcp.error.saveFailed", I18n.message(e)));
            }
        }

        private void downloadAll() {
            try {
                byte[] payload = dump();
                downloadLinks.removeAll();
                downloadLinks.add(DownloadSupport.anchor(dumpFilename(), payload));
                status.setText(I18n.t("mcp.status.downloadReady", payload.length));
            } catch (Exception e) {
                status.setText(I18n.t("mcp.error.downloadFailed", I18n.message(e)));
            }
        }

        private void confirmUnregister() {
            T selected = grid.asSingleSelect().getValue();
            if (selected == null) {
                status.setText(I18n.t("mcp.unregister.required"));
                return;
            }
            ConfirmDialog dialog = new ConfirmDialog();
            dialog.setHeader(I18n.t("mcp.unregister.title"));
            dialog.setText(itemLabel(selected));
            dialog.setCancelable(true);
            dialog.setCancelText(I18n.t("common.cancel"));
            dialog.setConfirmText(I18n.t("mcp.unregister"));
            dialog.setConfirmButtonTheme("error primary");
            dialog.addConfirmListener(event -> {
                try {
                    unregister(selected);
                    status.setText(I18n.t("mcp.unregistered", itemLabel(selected)));
                    refresh();
                } catch (Exception e) {
                    status.setText(I18n.t("mcp.error.unregisterFailed", I18n.message(e)));
                }
            });
            dialog.open();
        }

        @Override
        protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
            super.onDetach(detachEvent);
            client.close();
        }
    }

    private static final class McpToolsPanel extends McpPanel<McpTool> {

        private final ObjectMapper json = new ObjectMapper();

        McpToolsPanel() {
            super("mcp-tools");
        }

        @Override
        protected void configureGrid() {
            grid.addColumn(McpTool::name).setHeader(I18n.t("mcp.column.name")).setAutoWidth(true);
            grid.addColumn(McpTool::description).setHeader(I18n.t("mcp.column.description")).setAutoWidth(true);
            grid.addColumn(McpTool::category).setHeader(I18n.t("mcp.column.category")).setAutoWidth(true);
            grid.addColumn(tool -> tool.inputSchema() == null ? "" : String.valueOf(tool.inputSchema()))
                    .setHeader(I18n.t("mcp.column.inputSchema")).setAutoWidth(true);
        }

        @Override
        protected List<McpTool> loadItems() throws Exception {
            return client.tools();
        }

        @Override
        protected String itemLabel(McpTool item) {
            return item.name();
        }

        @Override
        protected void openRegisterDialog() {
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle(I18n.t("mcp.register.tool.title"));
            dialog.setWidth("760px");
            TextField name = new TextField(I18n.t("mcp.field.name"));
            name.setId("mcp-tool-name");
            TextField category = new TextField(I18n.t("mcp.field.category"));
            category.setId("mcp-tool-category");
            TextArea description = new TextArea(I18n.t("mcp.field.description"));
            description.setId("mcp-tool-description");
            description.setWidthFull();
            TextArea inputSchema = new TextArea(I18n.t("mcp.field.inputSchema"));
            inputSchema.setId("mcp-tool-schema");
            inputSchema.setWidthFull();
            inputSchema.setHeight("160px");
            Span dialogStatus = new Span();
            dialogStatus.setId("mcp-tool-dialog-status");

            Button submit = new Button(I18n.t("mcp.register"), event -> {
                try {
                    JsonNode schema = json.readTree(inputSchema.getValue());
                    if (!schema.isObject()) {
                        throw new IllegalArgumentException(I18n.t("mcp.error.schemaNotObject"));
                    }
                    client.registerTool(new McpTool(
                            name.getValue(), description.getValue(),
                            json.convertValue(schema, Object.class), category.getValue()));
                    dialog.close();
                    status.setText(I18n.t("mcp.registered", name.getValue()));
                    refresh();
                } catch (Exception e) {
                    dialogStatus.setText(I18n.t("mcp.error.registerFailed", I18n.message(e)));
                }
            });
            submit.setId("mcp-tool-submit");
            submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button cancel = new Button(I18n.t("common.cancel"), event -> dialog.close());
            dialog.add(new VerticalLayout(new HorizontalLayout(name, category), description,
                    inputSchema, dialogStatus, new HorizontalLayout(submit, cancel)));
            dialog.open();
        }

        @Override
        protected void unregister(McpTool item) throws Exception {
            client.unregisterTool(item.name());
        }

        @Override
        protected void loadJson(String rawJson) throws Exception {
            client.loadTools(rawJson);
        }

        @Override
        protected byte[] dump() throws Exception {
            return client.dumpTools();
        }

        @Override
        protected String dumpFilename() {
            return "mcp_tools.json";
        }

        @Override
        protected String save() throws Exception {
            McpAdminClient.SaveResult result = client.saveTools();
            return result.savedPath() == null
                    ? I18n.t("mcp.saved")
                    : I18n.t("mcp.saved.path", result.savedPath());
        }
    }

    private static final class McpResourcesPanel extends McpPanel<McpResource> {

        private final ObjectMapper json = new ObjectMapper();

        McpResourcesPanel() {
            super("mcp-resources");
        }

        @Override
        protected void configureGrid() {
            grid.addColumn(McpResource::uri).setHeader(I18n.t("mcp.column.uri")).setAutoWidth(true);
            grid.addColumn(McpResource::name).setHeader(I18n.t("mcp.column.name")).setAutoWidth(true);
            grid.addColumn(McpResource::description).setHeader(I18n.t("mcp.column.description")).setAutoWidth(true);
            grid.addColumn(McpResource::mimeType).setHeader(I18n.t("mcp.column.mime")).setAutoWidth(true);
            grid.addColumn(McpResource::category).setHeader(I18n.t("mcp.column.category")).setAutoWidth(true);
        }

        @Override
        protected List<McpResource> loadItems() throws Exception {
            return client.resources();
        }

        @Override
        protected String itemLabel(McpResource item) {
            return item.uri();
        }

        @Override
        protected void openRegisterDialog() {
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle(I18n.t("mcp.register.resource.title"));
            dialog.setWidth("760px");
            TextField uri = new TextField(I18n.t("mcp.field.uri"));
            uri.setId("mcp-resource-uri");
            TextField name = new TextField(I18n.t("mcp.field.name"));
            name.setId("mcp-resource-name");
            TextField mimeType = new TextField(I18n.t("mcp.field.mimeType"));
            mimeType.setId("mcp-resource-mime");
            TextField category = new TextField(I18n.t("mcp.field.category"));
            category.setId("mcp-resource-category");
            TextArea description = new TextArea(I18n.t("mcp.field.description"));
            description.setId("mcp-resource-description");
            description.setWidthFull();
            TextArea contents = new TextArea(I18n.t("mcp.field.contents"));
            contents.setId("mcp-resource-contents");
            contents.setWidthFull();
            contents.setHeight("140px");
            Span dialogStatus = new Span();
            dialogStatus.setId("mcp-resource-dialog-status");

            Button submit = new Button(I18n.t("mcp.register"), event -> {
                try {
                    JsonNode parsed = json.readTree(contents.getValue());
                    client.registerResource(new McpResource(
                            uri.getValue(), name.getValue(), description.getValue(), mimeType.getValue(),
                            json.convertValue(parsed, Object.class), category.getValue()));
                    dialog.close();
                    status.setText(I18n.t("mcp.registered", uri.getValue()));
                    refresh();
                } catch (Exception e) {
                    dialogStatus.setText(I18n.t("mcp.error.registerFailed", I18n.message(e)));
                }
            });
            submit.setId("mcp-resource-submit");
            submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button cancel = new Button(I18n.t("common.cancel"), event -> dialog.close());
            dialog.add(new VerticalLayout(new HorizontalLayout(uri, name), new HorizontalLayout(mimeType, category),
                    description, contents, dialogStatus, new HorizontalLayout(submit, cancel)));
            dialog.open();
        }

        @Override
        protected void unregister(McpResource item) throws Exception {
            client.unregisterResource(item.uri());
        }

        @Override
        protected void loadJson(String rawJson) throws Exception {
            client.loadResources(rawJson);
        }

        @Override
        protected byte[] dump() throws Exception {
            return client.dumpResources();
        }

        @Override
        protected String dumpFilename() {
            return "mcp_resources.json";
        }

        @Override
        protected String save() throws Exception {
            McpAdminClient.SaveResult result = client.saveResources();
            return result.savedPath() == null
                    ? I18n.t("mcp.saved")
                    : I18n.t("mcp.saved.path", result.savedPath());
        }
    }

    private static final class McpServicesPanel extends McpPanel<McpService> {

        private final ObjectMapper json = new ObjectMapper();

        McpServicesPanel() {
            super("mcp-services");
        }

        @Override
        protected void configureGrid() {
            grid.addColumn(McpService::name).setHeader(I18n.t("mcp.column.name")).setAutoWidth(true);
            grid.addColumn(McpService::apiName).setHeader(I18n.t("mcp.column.apiName")).setAutoWidth(true);
            grid.addColumn(McpService::version).setHeader(I18n.t("mcp.column.version")).setAutoWidth(true);
            grid.addColumn(McpService::method).setHeader(I18n.t("mcp.column.method")).setAutoWidth(true);
            grid.addColumn(McpService::category).setHeader(I18n.t("mcp.column.category")).setAutoWidth(true);
            grid.addColumn(McpService::description).setHeader(I18n.t("mcp.column.description")).setAutoWidth(true);
            grid.addColumn(service -> String.join(",", service.capabilities()))
                    .setHeader(I18n.t("mcp.column.capabilities")).setAutoWidth(true);
            grid.addColumn(service -> String.join(",", service.useCases()))
                    .setHeader(I18n.t("mcp.column.useCases")).setAutoWidth(true);
        }

        @Override
        protected List<McpService> loadItems() throws Exception {
            return client.services();
        }

        @Override
        protected String itemLabel(McpService item) {
            return item.name();
        }

        @Override
        protected void openRegisterDialog() {
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle(I18n.t("mcp.register.service.title"));
            dialog.setWidth("860px");
            TextField name = new TextField(I18n.t("mcp.field.name"));
            name.setId("mcp-service-name");
            TextField apiName = new TextField(I18n.t("mcp.field.apiName"));
            apiName.setId("mcp-service-api");
            TextField version = new TextField(I18n.t("mcp.field.version"));
            version.setId("mcp-service-version");
            ComboBox<String> method = new ComboBox<>(I18n.t("mcp.field.method"));
            method.setId("mcp-service-method");
            method.setItems("GET", "POST");
            method.setValue("GET");
            TextField category = new TextField(I18n.t("mcp.field.category"));
            category.setId("mcp-service-category");
            TextArea description = new TextArea(I18n.t("mcp.field.description"));
            description.setId("mcp-service-description");
            description.setWidthFull();
            TextArea capabilities = new TextArea(I18n.t("mcp.field.capabilities"));
            capabilities.setId("mcp-service-capabilities");
            capabilities.setWidthFull();
            capabilities.setHeight("80px");
            capabilities.setValue("[\"query\"]");
            TextArea useCases = new TextArea(I18n.t("mcp.field.useCases"));
            useCases.setId("mcp-service-usecases");
            useCases.setWidthFull();
            useCases.setHeight("80px");
            useCases.setValue("[\"Data query service\"]");
            TextArea parameters = new TextArea(I18n.t("mcp.field.parameters"));
            parameters.setId("mcp-service-parameters");
            parameters.setWidthFull();
            parameters.setHeight("80px");
            TextArea examples = new TextArea(I18n.t("mcp.field.examples"));
            examples.setId("mcp-service-examples");
            examples.setWidthFull();
            examples.setHeight("80px");
            Span dialogStatus = new Span();
            dialogStatus.setId("mcp-service-dialog-status");

            Button submit = new Button(I18n.t("mcp.register"), event -> {
                try {
                    client.registerService(new McpService(
                            name.getValue(), apiName.getValue(), version.getValue(), method.getValue(),
                            description.getValue(), category.getValue(),
                            stringList(capabilities.getValue()), stringList(useCases.getValue()),
                            jsonValue(parameters.getValue()), jsonValue(examples.getValue())));
                    dialog.close();
                    status.setText(I18n.t("mcp.registered", name.getValue()));
                    refresh();
                } catch (Exception e) {
                    dialogStatus.setText(I18n.t("mcp.error.registerFailed", I18n.message(e)));
                }
            });
            submit.setId("mcp-service-submit");
            submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button cancel = new Button(I18n.t("common.cancel"), event -> dialog.close());
            dialog.add(new VerticalLayout(
                    new HorizontalLayout(name, apiName, version, method),
                    new HorizontalLayout(category),
                    description, capabilities, useCases, parameters, examples, dialogStatus,
                    new HorizontalLayout(submit, cancel)));
            dialog.open();
        }

        @Override
        protected void unregister(McpService item) throws Exception {
            client.unregisterService(item.name());
        }

        @Override
        protected void loadJson(String rawJson) throws Exception {
            client.loadServices(rawJson);
        }

        @Override
        protected byte[] dump() throws Exception {
            return client.dumpServices();
        }

        @Override
        protected String dumpFilename() {
            return "mcp_services.json";
        }

        @Override
        protected String save() throws Exception {
            McpAdminClient.SaveResult result = client.saveServices();
            return result.savedPath() == null
                    ? I18n.t("mcp.saved")
                    : I18n.t("mcp.saved.path", result.savedPath());
        }

        private List<String> stringList(String text) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            JsonNode root = json.readTree(text);
            if (!root.isArray()) {
                throw new IllegalArgumentException(I18n.t("mcp.error.jsonStringArray"));
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : root) {
                values.add(item.asText());
            }
            return values;
        }

        private Object jsonValue(String text) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            JsonNode root = json.readTree(text);
            return json.convertValue(root, Object.class);
        }
    }
}
