package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.ai.AiChatClient;
import org.slackerdb.dbdesk.access.ai.AiChatMessage;

/**
 * AI Chat 页：复用 dbserver 的 {@code /aichat} WebSocket。
 *
 * <p>只有 {@code capabilities.features.aiChat=true} 时服务端才有该端点；直接访问本页时
 * 若能力未开启，只给出提示，不尝试连接。</p>
 */
@Route(value = "ai-chat", layout = MainLayout.class)
public class AiChatView extends VerticalLayout {

    private final Span status = new Span(I18n.t("ai.status.disconnected"));
    private final VerticalLayout messages = new VerticalLayout();
    private final TextArea input = new TextArea();
    private final Button connect = new Button(I18n.t("common.connect"));
    private final Button disconnect = new Button(I18n.t("connection.disconnect"));
    private final Button send = new Button(I18n.t("ai.send"));
    private final Button clear = new Button(I18n.t("ai.clear"));

    private transient AiChatClient chat;
    private boolean available;

    public AiChatView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        I18n.setTitle("nav.aiChat");

        H2 title = new H2(I18n.t("ai.title"));
        Span endpoint = new Span(ConnectionManager.current().managementUrl());
        endpoint.setId("ai-chat-endpoint");
        status.setId("ai-chat-status");

        messages.setId("ai-chat-messages");
        messages.setWidthFull();
        messages.setHeight("calc(100vh - 380px)");
        messages.getStyle().set("overflow-y", "auto")
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s)");

        input.setId("ai-chat-input");
        input.setWidthFull();
        input.setHeight("90px");
        input.setPlaceholder(I18n.t("ai.input.placeholder"));

        connect.setId("ai-chat-connect");
        connect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connect.addClickListener(event -> connect());
        disconnect.setId("ai-chat-disconnect");
        disconnect.setEnabled(false);
        disconnect.addClickListener(event -> disconnect());
        send.setId("ai-chat-send");
        send.addClickListener(event -> send());
        clear.setId("ai-chat-clear");
        clear.addClickListener(event -> clear());

        add(title,
                new HorizontalLayout(new Span(I18n.t("management.label")), endpoint),
                new HorizontalLayout(connect, disconnect, clear, status),
                messages,
                input,
                new HorizontalLayout(send));
        setFlexGrow(1, messages);

        checkAvailability();
    }

    private void checkAvailability() {
        ConnectionManager connection = ConnectionManager.current();
        available = connection.hasInstanceManagement()
                && connection.capabilities() != null
                && connection.capabilities().features().aiChat();
        if (!available) {
            connect.setEnabled(false);
            send.setEnabled(false);
            status.setText(I18n.t("ai.disabled.status"));
            addMessage("system", I18n.t("ai.disabled.message"));
        } else {
            addMessage("system", I18n.t("ai.hint.connect"));
        }
    }

    private void connect() {
        if (!available) {
            return;
        }
        UI ui = getUI().orElse(null);
        status.setText(I18n.t("connection.connecting"));
        chat = new AiChatClient(ConnectionManager.current().managementUrl());
        chat.connect(new AiChatClient.Listener() {
            @Override
            public void onConnected() {
                if (ui != null) {
                    ui.access(() -> {
                        status.setText(I18n.t("ai.status.connected"));
                        connect.setEnabled(false);
                        disconnect.setEnabled(true);
                        send.setEnabled(true);
                    });
                }
            }

            @Override
            public void onMessage(AiChatMessage message) {
                if (ui != null) {
                    ui.access(() -> addMessage(message.type(), message.content()));
                }
            }

            @Override
            public void onError(String error) {
                if (ui != null) {
                    ui.access(() -> {
                        status.setText(I18n.t("ai.status.error", error));
                        connect.setEnabled(true);
                        disconnect.setEnabled(false);
                    });
                }
            }

            @Override
            public void onClosed() {
                if (ui != null) {
                    ui.access(() -> {
                        status.setText(I18n.t("ai.status.closed"));
                        connect.setEnabled(true);
                        disconnect.setEnabled(false);
                    });
                }
            }
        });
    }

    private void disconnect() {
        if (chat != null) {
            chat.close();
            chat = null;
        }
        status.setText(I18n.t("ai.status.closed"));
        connect.setEnabled(true);
        disconnect.setEnabled(false);
    }

    private void send() {
        String text = input.getValue();
        if (text == null || text.isBlank()) {
            return;
        }
        if (chat == null || !chat.connected()) {
            status.setText(I18n.t("ai.status.notConnected"));
            return;
        }
        try {
            chat.send(text);
            addMessage("user", text);
            input.clear();
        } catch (Exception e) {
            status.setText(I18n.t("ai.status.sendFailed", I18n.message(e)));
        }
    }

    private void clear() {
        if (chat != null) {
            chat.clearHistory();
        }
        messages.removeAll();
        addMessage("system", I18n.t("ai.status.cleared"));
    }

    private void addMessage(String role, String content) {
        Div row = new Div();
        row.getStyle().set("margin", "4px 0")
                .set("padding", "6px 8px")
                .set("border-radius", "6px")
                .set("white-space", "pre-wrap");
        if ("user".equals(role)) {
            row.getStyle().set("background", "var(--lumo-primary-color-10pct)");
        } else if ("error".equals(role)) {
            row.getStyle().set("background", "var(--lumo-error-color-10pct)");
        } else {
            row.getStyle().set("background", "var(--lumo-contrast-5pct)");
        }
        Span who = new Span(I18n.t("ai.role.label", I18n.t(roleKey(role))));
        who.getStyle().set("font-weight", "600");
        row.add(who, new Span(content == null ? "" : content));
        messages.add(row);
    }

    /** 角色是协议值（user/system/error），显示文案单独翻译。 */
    private static String roleKey(String role) {
        return switch (role) {
            case "user" -> "ai.role.user";
            case "error" -> "ai.role.error";
            default -> "ai.role.system";
        };
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (chat != null) {
            chat.close();
            chat = null;
        }
    }
}
