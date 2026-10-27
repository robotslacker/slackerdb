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

    private final Span status = new Span("未连接");
    private final VerticalLayout messages = new VerticalLayout();
    private final TextArea input = new TextArea();
    private final Button connect = new Button("连接");
    private final Button disconnect = new Button("断开");
    private final Button send = new Button("发送");
    private final Button clear = new Button("清空");

    private transient AiChatClient chat;
    private boolean available;

    public AiChatView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("AI 助手");
        Span endpoint = new Span("管理口：" + ConnectionManager.current().managementUrl());
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
        input.setPlaceholder("输入消息后点「发送」");

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
                new HorizontalLayout(new Span("管理口："), endpoint),
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
            status.setText("服务端未启用 AI Chat（未配置 mcp_llm_server）");
            addMessage("system", "AI Chat 未启用。请在服务端配置 mcp_llm_server 后刷新。");
        } else {
            addMessage("system", "点击「连接」开始与 AI 助手对话。");
        }
    }

    private void connect() {
        if (!available) {
            return;
        }
        UI ui = getUI().orElse(null);
        status.setText("连接中…");
        chat = new AiChatClient(ConnectionManager.current().managementUrl());
        chat.connect(new AiChatClient.Listener() {
            @Override
            public void onConnected() {
                if (ui != null) {
                    ui.access(() -> {
                        status.setText("已连接");
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
                        status.setText("连接错误：" + error);
                        connect.setEnabled(true);
                        disconnect.setEnabled(false);
                    });
                }
            }

            @Override
            public void onClosed() {
                if (ui != null) {
                    ui.access(() -> {
                        status.setText("已断开");
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
        status.setText("已断开");
        connect.setEnabled(true);
        disconnect.setEnabled(false);
    }

    private void send() {
        String text = input.getValue();
        if (text == null || text.isBlank()) {
            return;
        }
        if (chat == null || !chat.connected()) {
            status.setText("尚未连接");
            return;
        }
        try {
            chat.send(text);
            addMessage("user", text);
            input.clear();
        } catch (Exception e) {
            status.setText("发送失败：" + message(e));
        }
    }

    private void clear() {
        if (chat != null) {
            chat.clearHistory();
        }
        messages.removeAll();
        addMessage("system", "已清空本地消息");
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
        Span who = new Span(role + "：");
        who.getStyle().set("font-weight", "600");
        row.add(who, new Span(content == null ? "" : content));
        messages.add(row);
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (chat != null) {
            chat.close();
            chat = null;
        }
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
