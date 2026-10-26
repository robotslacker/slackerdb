package org.slackerdb.dbdesk.jsbridge;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.shared.Registration;

/**
 * CodeMirror 6 的 SQL 编辑器包装件（M0-V3）。
 *
 * <p><b>为什么需要包装件</b>：Vaadin 体系里"引入一段自定义 JS"只有两种做法 —— 手写
 * {@code @JsModule} 包一个组件，或者放弃 Java 写 UI。这里选前者，并且把它限制在
 * {@code jsbridge} 包里：视图层只依赖这个 Java 类，不出现 {@code @JsModule}/{@code @CssImport}
 * （M1 用 ArchUnit 守）。</p>
 *
 * <p><b>对应的客户端实现</b>：{@code src/main/frontend/src/sql-editor.ts}，约定见该类注释。</p>
 *
 * <p><b>代价（必须写进报告）</b>：一旦引入自定义前端代码，Vaadin 就不再走"预编译 bundle"，
 * 构建期要真的跑 Vite/npm —— 也就是 <b>构建机需要 Node + npm 源</b>，离线构建不再是零依赖。</p>
 */
@Tag("sql-editor")
@JsModule("./src/sql-editor.ts")
@NpmPackage(value = "codemirror", version = "6.0.2")
@NpmPackage(value = "@codemirror/lang-sql", version = "6.10.0")
public class SqlEditor extends Component implements HasSize {

    private static final String DEFAULT_SQL = "";

    private String lastKnownValue = DEFAULT_SQL;

    public SqlEditor() {
        this("");
    }

    public SqlEditor(String initialSql) {
        setHeight("240px");
        setValue(initialSql);
        // 客户端每次改内容（300 ms 防抖）都回传一份，供状态提示/草稿用
        addChangeListener(event -> lastKnownValue = event.getSql());
    }

    /** 服务端 → 客户端：替换编辑器内容。 */
    public void setValue(String sql) {
        lastKnownValue = sql == null ? "" : sql;
        getElement().setProperty("sql", lastKnownValue);
    }

    /** 客户端 → 服务端：最近一次同步到的内容（键入过程中由 change 事件回传）。 */
    public String getValue() {
        return lastKnownValue;
    }

    /**
     * 向浏览器要**当前**内容（一次往返）。
     *
     * <p>为什么不能只靠 {@link #getValue()}：那条路径是"客户端改了→change 事件回传"，
     * 有 300 ms 防抖，点"执行"的瞬间可能还没到 —— 会执行上一版 SQL（实测踩到：
     * 改了编辑器里的语句、点执行，跑的还是旧语句）。执行前必须问一次客户端。</p>
     */
    public void readValue(java.util.function.Consumer<String> consumer) {
        getElement().executeJs("return this.sql")
                .then(String.class, value -> consumer.accept(value == null ? "" : value));
    }

    /** 只读 / 可编辑两态。 */
    public void setReadOnly(boolean readOnly) {
        getElement().setProperty("readOnly", readOnly);
    }

    /**
     * 把光标移到指定行列（1-based）并滚动到可见位置 —— 执行报错时用来"定位到出错处"。
     *
     * <p>行列来自服务端错误文案（{@code LINE n: … ^}）；解析不出来时调用方不应调用本方法。</p>
     */
    public void setCursor(int line, int column) {
        if (line <= 0) {
            return;
        }
        getElement().executeJs("""
                const view = this.view;
                if (!view) return;
                const targetLine = Math.min(%d, view.state.doc.lines);
                const info = view.state.doc.line(targetLine);
                const offset = Math.min(Math.max(0, %d), info.length);
                view.dispatch({ selection: { anchor: info.from + offset }, scrollIntoView: true });
                view.focus();
                """.formatted(line, Math.max(0, column - 1)));
    }

    /** Ctrl/Cmd+Enter。 */
    public Registration addRunListener(ComponentEventListener<RunEvent> listener) {
        return addListener(RunEvent.class, listener);
    }

    /** 内容变化（客户端 300 ms 防抖后上送）。 */
    public Registration addChangeListener(ComponentEventListener<ChangeEvent> listener) {
        return addListener(ChangeEvent.class, listener);
    }

    @DomEvent("run")
    public static class RunEvent extends ComponentEvent<SqlEditor> {
        private final String sql;

        public RunEvent(SqlEditor source, boolean fromClient, @EventData("event.detail.value") String sql) {
            super(source, fromClient);
            this.sql = sql;
        }

        public String getSql() {
            return sql;
        }
    }

    @DomEvent("change")
    public static class ChangeEvent extends ComponentEvent<SqlEditor> {
        private final String sql;

        public ChangeEvent(SqlEditor source, boolean fromClient, @EventData("event.detail.value") String sql) {
            super(source, fromClient);
            this.sql = sql;
        }

        public String getSql() {
            return sql;
        }
    }
}
