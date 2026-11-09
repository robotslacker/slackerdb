package org.slackerdb.dbserver.service.filter;

import org.slackerdb.dbserver.service.filter.FilterBinder.BoundFilter;
import org.slackerdb.dbserver.service.filter.FilterBinder.BoundSortKey;

import java.util.ArrayList;
import java.util.List;

/**
 * 对已物化的结果集执行 WHERE → ORDER BY → OFFSET → LIMIT。
 *
 * <p>所有操作都发生在"行的引用列表"上：不会修改传入的 dataset，也不会修改缓存条目。
 * 过滤/排序失败（类型不匹配等）会抛出客户端可读的 {@link FilterException}。</p>
 */
final class FilterEvaluator {

    record FilterResult(List<List<Object>> rows) {
    }

    private FilterEvaluator() {
    }

    static FilterResult apply(BoundFilter filter, List<List<Object>> dataset) throws FilterException {
        int datasetSize = dataset.size();

        // 快路径：只过滤/分页、不排序
        if (filter.predicate() == null && filter.sortKeys().isEmpty()) {
            int from = Math.min(filter.offset(), datasetSize);
            int to = pageEnd(from, filter.limit(), datasetSize);
            if (from == 0 && to == datasetSize) {
                return new FilterResult(dataset);
            }
            return new FilterResult(new ArrayList<>(dataset.subList(from, to)));
        }

        List<List<Object>> rows = new ArrayList<>();
        if (filter.predicate() == null) {
            rows.addAll(dataset);
        } else {
            for (List<Object> row : dataset) {
                if (Boolean.TRUE.equals(filter.predicate().test(row))) {
                    rows.add(row);
                }
            }
        }

        if (!filter.sortKeys().isEmpty()) {
            sort(rows, filter.sortKeys());
        }

        int from = Math.min(filter.offset(), rows.size());
        int to = pageEnd(from, filter.limit(), rows.size());
        return new FilterResult(new ArrayList<>(rows.subList(from, to)));
    }

    private static int pageEnd(int from, Integer limit, int size) {
        if (limit == null) {
            return size;
        }
        return (int) Math.min((long) from + limit, size);
    }

    private static void sort(List<List<Object>> rows, List<BoundSortKey> keys)
            throws FilterException {
        try {
            rows.sort((left, right) -> {
                try {
                    return compareRows(left, right, keys);
                } catch (FilterException failure) {
                    throw new SortFailure(failure);
                }
            });
        } catch (SortFailure failure) {
            throw failure.filterException();
        }
    }

    private static int compareRows(List<Object> left, List<Object> right, List<BoundSortKey> keys)
            throws FilterException {
        for (BoundSortKey key : keys) {
            Object leftValue = key.expr().eval(left);
            Object rightValue = key.expr().eval(right);

            if (leftValue == null || rightValue == null) {
                if (leftValue == null && rightValue == null) {
                    continue;
                }
                // NULLS FIRST/LAST 与方向无关：DuckDB 默认 NULLS LAST（升序降序都一样）
                boolean leftIsNull = leftValue == null;
                return leftIsNull
                        ? (key.nullsFirst() ? -1 : 1)
                        : (key.nullsFirst() ? 1 : -1);
            }

            if (leftValue instanceof List || leftValue instanceof java.util.Map
                    || rightValue instanceof List || rightValue instanceof java.util.Map) {
                throw FilterException.at("ORDER BY key of type "
                        + FilterValues.typeName(leftValue) + " is not sortable (use length())", 0);
            }

            int order = FilterValues.compare(leftValue, rightValue,
                    key.expr().describe(), key.expr().describe(), 0);
            if (order != 0) {
                return key.ascending() ? order : -order;
            }
        }
        return 0;
    }

    /** List.sort 的 Comparator 不允许受检异常，这里做一次封装再还原。 */
    private static final class SortFailure extends RuntimeException {
        private final FilterException filterException;

        private SortFailure(FilterException filterException) {
            super(filterException.getMessage(), filterException);
            this.filterException = filterException;
        }

        private FilterException filterException() {
            return filterException;
        }
    }
}
