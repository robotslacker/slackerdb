package org.slackerdb.dbdesk.access.dataset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 数据集：**一个命名好的查询 + 它的参数 + 列的语义**。
 *
 * <p>为什么需要这一层：图表、仪表板、门户都要"取一批数据来画"。如果每个图表各存一条 SQL，
 * 改口径时就要满世界改 SQL；把"数据集"抽出来，图表只引用 dataset，口径改一次全部生效。</p>
 *
 * <p>SQL 里用 <b>{@code :name}</b> 声明参数（渲染时换成 {@code ?} 绑定，不做字符串拼接），
 * 参数默认值也存在定义里 —— 图表/仪表板打开时先按默认值出图，用户再改筛选。</p>
 *
 * <p><b>参数要放在有类型的上下文里</b>：`where col = :p` 或 `cast(:p as varchar)`。\n * `select :p as x` 这种"无类型参数列"服务端给出的列元数据不可靠（pgjdbc 取元数据会断言失败），\n * 因此不推荐；真要用就显式 cast。</p>\n *\n * <p>列的 {@code role}（dimension / measure / ignore）是给图表编辑器用的：
 * 维度放 X 轴或分组，度量放 Y 轴。它只影响建议，不限制用户选择。</p>
 */
public record Dataset(String id, String name, String sql, String jdbcUrl,
                      List<Param> params, List<Column> columns, String updatedAt) {

    /** 参数类型：text / number / date。 */
    public record Param(String name, String type, String defaultValue, String description) {
        public Param {
            if (name == null || name.isBlank()) {
                throw new org.slackerdb.dbdesk.i18n.LocalizedException("error.dataset.paramNameRequired");
            }
            name = name.strip();
            type = normalizeType(type);
            defaultValue = defaultValue == null ? "" : defaultValue;
            description = description == null ? "" : description;
        }
    }

    /** 列：类型 + 角色（dimension / measure / ignore）。 */
    public record Column(String name, String type, String role) {
        public Column {
            if (name == null || name.isBlank()) {
                throw new org.slackerdb.dbdesk.i18n.LocalizedException("error.dataset.columnNameRequired");
            }
            name = name.strip();
            type = type == null || type.isBlank() ? "other" : type.strip().toLowerCase(Locale.ROOT);
            role = role == null || role.isBlank() ? "dimension" : role.strip().toLowerCase(Locale.ROOT);
        }
    }

    public Dataset {
        if (name == null || name.isBlank()) {
            throw new org.slackerdb.dbdesk.i18n.LocalizedException("error.dataset.nameRequired");
        }
        if (sql == null || sql.isBlank()) {
            throw new org.slackerdb.dbdesk.i18n.LocalizedException("error.dataset.sqlRequired");
        }
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        name = name.strip();
        sql = sql.strip();
        jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.strip();
        params = params == null ? List.of() : List.copyOf(params);
        columns = columns == null ? List.of() : List.copyOf(columns);
        updatedAt = updatedAt == null || updatedAt.isBlank() ? Instant.now().toString() : updatedAt;
    }

    /** 参数名列表（按 SQL 里第一次出现的顺序），供执行器绑定。 */
    public List<String> paramOrder() {
        List<String> order = new ArrayList<>();
        for (Param param : params) {
            order.add(param.name());
        }
        return order;
    }

    /** 改内容（保留 id）。 */
    public Dataset withContent(String newName, String newSql, List<Param> newParams, List<Column> newColumns) {
        return new Dataset(id, newName, newSql, jdbcUrl, newParams, newColumns, Instant.now().toString());
    }

    public static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "text";
        }
        String value = type.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "number", "numeric", "int", "integer", "decimal" -> "number";
            case "date", "datetime", "timestamp" -> "date";
            default -> "text";
        };
    }
}
