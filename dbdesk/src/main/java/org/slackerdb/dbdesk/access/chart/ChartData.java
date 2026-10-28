package org.slackerdb.dbdesk.access.chart;

import org.slackerdb.dbdesk.access.dataset.DatasetRunner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 把数据集结果按图表配置整理成"分类 + 多条序列"。
 *
 * <p>纯数据变换，不依赖 Vaadin/Jackson，便于单测。三条规则：</p>
 * <ol>
 *   <li>X 决定分类顺序（按首次出现排序，保证刷新后顺序稳定）；</li>
 *   <li>有分组列时，每个分组 × 每个 Y 列各成一条序列；</li>
 *   <li>同一 (分类, 分组) 出现多行时按 {@code aggregate} 合并（默认 {@code none} = 取最后一行）。</li>
 * </ol>
 *
 * <p>聚合默认交给 SQL 做（数据集里 group by）；这里的聚合只是"数据集没聚合时兜底"，
 * 避免同名点互相覆盖却看不出来。</p>
 */
public final class ChartData {

    /** 一条序列。{@code name} 为空表示无分组时的 Y 列名。 */
    public record Series(String name, List<Object> data) {
    }

    public record Result(List<Object> categories, List<Series> series, boolean truncated,
                         long elapsedMillis, String error) {

        public static Result error(String message) {
            return new Result(List.of(), List.of(), false, 0, message);
        }

        public boolean ok() {
            return error == null;
        }
    }

    private ChartData() {
    }

    public static Result build(DatasetRunner.Result source, Chart chart) {
        int xIndex = indexOf(source.columns(), chart.x());
        int seriesIndex = chart.series() == null ? -1 : indexOf(source.columns(), chart.series());
        List<Integer> yIndexes = new ArrayList<>();
        for (String column : chart.y()) {
            int index = indexOf(source.columns(), column);
            if (index < 0) {
                return Result.error(org.slackerdb.dbdesk.i18n.MessageResolver.render("chart.error.missingColumn", column));
            }
            yIndexes.add(index);
        }

        // 分类：X 列（没有 X 时用行号，饼图就是"每行一个扇区"）。
        // 每行的分类值要**记下来**给下面分组用 —— 两处分别计算会导致键对不上（饼图曾整列 null）。
        LinkedHashSet<Object> categories = new LinkedHashSet<>();
        List<Object> rowCategories = new ArrayList<>(source.rows().size());
        for (List<Object> row : source.rows()) {
            Object category = xIndex >= 0 && xIndex < row.size() ? row.get(xIndex) : categories.size() + 1;
            categories.add(category);
            rowCategories.add(category);
        }
        List<Object> categoryList = new ArrayList<>(categories);

        // 序列名 → (分类 → 值)，保持插入顺序
        Map<String, Map<Object, Object>> buckets = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex < source.rows().size(); rowIndex++) {
            List<Object> row = source.rows().get(rowIndex);
            Object category = rowCategories.get(rowIndex);
            Object group = seriesIndex >= 0 && seriesIndex < row.size() ? row.get(seriesIndex) : null;
            for (int i = 0; i < yIndexes.size(); i++) {
                int yIndex = yIndexes.get(i);
                Object value = yIndex < row.size() ? row.get(yIndex) : null;
                String name = chart.y().get(i);
                if (seriesIndex >= 0) {
                    name = org.slackerdb.dbdesk.i18n.MessageResolver.render("chart.series.group", name, group == null ? "null" : group);
                }
                buckets.computeIfAbsent(name, key -> new LinkedHashMap<>())
                        .merge(category, value, (oldValue, newValue) -> combine(oldValue, newValue, chart.aggregate()));
            }
        }

        List<Series> series = new ArrayList<>();
        for (Map.Entry<String, Map<Object, Object>> entry : buckets.entrySet()) {
            List<Object> data = new ArrayList<>(categoryList.size());
            for (Object category : categoryList) {
                data.add(entry.getValue().get(category));
            }
            series.add(new Series(entry.getKey(), data));
        }
        return new Result(categoryList, series, source.truncated(), source.elapsedMillis(), null);
    }

    private static Object combine(Object oldValue, Object newValue, String aggregate) {
        if ("none".equals(aggregate)) {
            return newValue;
        }
        if ("count".equals(aggregate)) {
            long oldCount = oldValue instanceof Number number ? number.longValue() : 0;
            return oldCount + 1;
        }
        BigDecimal left = toDecimal(oldValue);
        BigDecimal right = toDecimal(newValue);
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return switch (aggregate) {
            case "sum" -> left.add(right);
            case "avg" -> left.add(right).divide(BigDecimal.valueOf(2), 6, java.math.RoundingMode.HALF_UP);
            case "min" -> left.min(right);
            case "max" -> left.max(right);
            default -> right;
        };
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static int indexOf(List<String> columns, String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
