package org.slackerdb.dbserver.service.filter;

import org.slackerdb.dbserver.service.filter.FilterAst.FilterProgram;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据服务 API 的 filter 门面。
 *
 * <p>{@link #parse(String)} 只做语法分析，可以提前失败；{@link #apply(Map, FilterProgram)}
 * 在拿到结果集之后绑定列名并求值。返回值是一份新的 data Map（浅拷贝），
 * 原始 Map（可能是缓存条目）保持原样。</p>
 */
public final class DataFilter {

    private DataFilter() {
    }

    public static FilterProgram parse(String text) throws FilterException {
        return FilterParser.parse(text);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> apply(Map<String, Object> data, FilterProgram program)
            throws FilterException {
        Object columnNamesRaw = data.get("columnNames");
        Object datasetRaw = data.get("dataset");
        if (!(columnNamesRaw instanceof List<?>) || !(datasetRaw instanceof List<?>)) {
            throw new IllegalStateException("Result data is missing columnNames/dataset.");
        }

        List<String> columnNames = (List<String>) columnNamesRaw;
        List<String> columnTypes = data.get("columnTypes") instanceof List<?> typesRaw
                ? (List<String>) typesRaw
                : List.of();
        List<List<Object>> dataset = (List<List<Object>>) datasetRaw;

        FilterBinder.BoundFilter bound = FilterBinder.bind(program, columnNames, columnTypes);
        FilterEvaluator.FilterResult result = FilterEvaluator.apply(bound, dataset);

        Map<String, Object> filtered = new HashMap<>(data);
        filtered.put("dataset", result.rows());
        filtered.put("affectedRows", result.rows().size());
        return filtered;
    }
}
