package org.slackerdb.dbdesk.access.chart;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 图表定义：**引用数据集**（只存 datasetId，不复制 SQL）+ 图表语义（类型 / X / Y / 分组 / 聚合）。
 *
 * <p>为什么存语义而不是 ECharts option：option 是渲染细节，换渲染库或升级 ECharts 时
 * 存 option 就得整体迁移；存"哪个列当 X、哪几列当 Y、是否按某列分组"则与渲染器无关。</p>
 */
public record Chart(String id, String name, String datasetId, String type,
                    String x, List<String> y, String series, String aggregate,
                    Map<String, Object> options, String updatedAt) {

    public static final List<String> TYPES = List.of("line", "bar", "pie", "scatter");
    public static final List<String> AGGREGATES = List.of("none", "sum", "avg", "count", "min", "max");

    public Chart {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("chart name must not be blank");
        }
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("chart must reference a dataset");
        }
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        name = name.strip();
        datasetId = datasetId.strip();
        type = type == null || type.isBlank() ? "line" : type.strip().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported chart type: " + type);
        }
        x = x == null || x.isBlank() ? null : x.strip();
        y = y == null ? List.of() : List.copyOf(y.stream().filter(v -> v != null && !v.isBlank()).toList());
        if (y.isEmpty()) {
            throw new IllegalArgumentException("chart must have at least one Y column");
        }
        series = series == null || series.isBlank() ? null : series.strip();
        aggregate = aggregate == null || aggregate.isBlank() ? "none" : aggregate.strip().toLowerCase(Locale.ROOT);
        if (!AGGREGATES.contains(aggregate)) {
            throw new IllegalArgumentException("unsupported aggregate: " + aggregate);
        }
        options = options == null ? Map.of() : Map.copyOf(options);
        updatedAt = updatedAt == null || updatedAt.isBlank() ? Instant.now().toString() : updatedAt;
    }

    public Chart withContent(String newName, String newDatasetId, String newType, String newX,
                             List<String> newY, String newSeries, String newAggregate) {
        return new Chart(id, newName, newDatasetId, newType, newX, newY, newSeries, newAggregate,
                options, Instant.now().toString());
    }
}
