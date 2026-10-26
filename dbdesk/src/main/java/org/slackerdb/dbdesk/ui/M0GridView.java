package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.router.Route;
import org.slackerdb.dbdesk.access.JdbcPageSource;
import org.slackerdb.dbdesk.access.QueryPage;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

/**
 * M0-V2 验证页：把 SQL 结果接进 Vaadin Grid，用 {@code DataProvider.fromCallbacks} 做**懒加载**。
 *
 * <p>这一页只回答三个问题（验收数字见 {@code javagui-m0-checklist.md} §1）：</p>
 * <ol>
 *   <li>100 万行 × 50 列能不能"滚得动"（帧率）；</li>
 *   <li>滚动时浏览器内存会不会随行数线性涨；</li>
 *   <li>单值 &gt;1 MB 是否按约定截断。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>：不做编辑器、不做表头排序/筛选、不做列宽自适应 —— 那些属于 M1 的 SQL 工作台；
 * 这里多写一行 UI，就多一行要返工的代码。</p>
 *
 * <p>访问层（{@code org.slackerdb.dbdesk.access}）不知道 Vaadin 的存在，本类只消费
 * {@link QueryPage}；连接是 {@code transient} 的，页面被序列化时不会把 JDBC 连接带进去。</p>
 */
@Route("m0-grid")
public class M0GridView extends VerticalLayout {

    private static final String DEFAULT_URL = "jdbc:postgresql://127.0.0.1:4409/m0";
    private static final String DEFAULT_SQL = "select * from m0_probe";

    private final TextField urlField = new TextField("JDBC URL");
    private final TextArea sqlField = new TextArea("SQL");
    private final Button loadButton = new Button("加载");
    private final Span status = new Span("未加载");
    private final Grid<List<Object>> grid = new Grid<>();

    /** JDBC 连接不可序列化，也不该跟着 UI 会话走。 */
    private transient JdbcPageSource source;
    private String activeSql;
    private long totalRows;

    public M0GridView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("M0-V2 网格取数验证");

        urlField.setValue(DEFAULT_URL);
        urlField.setWidth("420px");
        urlField.setId("url-field");

        sqlField.setValue(DEFAULT_SQL);
        sqlField.setWidthFull();
        sqlField.setHeight("80px");
        sqlField.setId("sql-field");

        loadButton.setId("load-button");
        loadButton.addClickListener(event -> load());

        status.setId("status-text");

        HorizontalLayout toolbar = new HorizontalLayout(loadButton, status);
        toolbar.setDefaultVerticalComponentAlignment(Alignment.CENTER);

        grid.setId("result-grid");
        grid.setSizeFull();
        grid.addThemeVariants(GridVariant.LUMO_COMPACT);
        grid.setColumnReorderingAllowed(false);

        add(title, urlField, sqlField, toolbar, grid);
        setFlexGrow(1, grid);
    }

    /** 加载：先取一页摸清列结构，再挂上懒加载的 DataProvider（不 setItems 全量）。 */
    private void load() {
        activeSql = sqlField.getValue() == null ? "" : sqlField.getValue().strip();
        closeSource();
        grid.removeAllColumns();
        status.setText("加载中…");

        try {
            JdbcPageSource pageSource = source();
            long countStart = System.nanoTime();
            totalRows = pageSource.count(activeSql);
            long countMillis = (System.nanoTime() - countStart) / 1_000_000;

            QueryPage firstPage = pageSource.fetch(activeSql, 0, 100);
            buildColumns(firstPage);
            grid.setItems(DataProvider.fromCallbacks(this::fetchPage, query -> (int) Math.min(totalRows, Integer.MAX_VALUE)));

            status.setText(String.format("共 %,d 行 · 首页 %d 行 / 1 页 %d ms · count(*) %d ms%s",
                    totalRows, firstPage.rowCount(), firstPage.elapsedMillis(), countMillis,
                    firstPage.truncated() ? " · 有单元格被截断(>1MB)" : ""));
        } catch (SQLException e) {
            status.setText("失败：" + e.getMessage());
            grid.removeAllColumns();
        }
    }

    private Stream<List<Object>> fetchPage(Query<List<Object>, Void> query) {
        try {
            return source().fetch(activeSql, query.getOffset(), query.getLimit()).rows().stream();
        } catch (SQLException e) {
            // M0：把错误直接抛成运行时异常，让页面显示错误；M1 要换成一格错误状态 + 重试
            throw new IllegalStateException("取数失败：" + e.getMessage(), e);
        }
    }

    private void buildColumns(QueryPage page) {
        List<String> columns = page.columns();
        for (int i = 0; i < columns.size(); i++) {
            int index = i;
            String type = i < page.columnTypes().size() ? page.columnTypes().get(i) : null;
            Grid.Column<List<Object>> column = grid.addColumn(row -> row.get(index));
            column.setHeader(columns.get(i));
            column.setAutoWidth(true);
            if (type != null) {
                column.setTooltipGenerator(row -> type);
            }
        }
    }

    private JdbcPageSource source() {
        if (source == null) {
            source = new JdbcPageSource(urlField.getValue());
        }
        return source;
    }

    private void closeSource() {
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        closeSource();
    }
}
