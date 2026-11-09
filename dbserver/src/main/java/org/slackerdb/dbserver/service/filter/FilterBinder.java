package org.slackerdb.dbserver.service.filter;

import org.slackerdb.dbserver.service.filter.FilterAst.ColumnRef;
import org.slackerdb.dbserver.service.filter.FilterAst.Comparison;
import org.slackerdb.dbserver.service.filter.FilterAst.Expr;
import org.slackerdb.dbserver.service.filter.FilterAst.FilterProgram;
import org.slackerdb.dbserver.service.filter.FilterAst.FunctionCall;
import org.slackerdb.dbserver.service.filter.FilterAst.InList;
import org.slackerdb.dbserver.service.filter.FilterAst.IsNull;
import org.slackerdb.dbserver.service.filter.FilterAst.Like;
import org.slackerdb.dbserver.service.filter.FilterAst.Literal;
import org.slackerdb.dbserver.service.filter.FilterAst.Not;
import org.slackerdb.dbserver.service.filter.FilterAst.Or;
import org.slackerdb.dbserver.service.filter.FilterAst.Predicate;
import org.slackerdb.dbserver.service.filter.FilterAst.SortKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把 AST 绑定到结果集列上，得到可直接逐行求值的结构。
 *
 * <p>列名解析规则：未加引号大小写不敏感；加双引号精确匹配。若多个结果列
 * 折叠到同一个名字，引用时按"歧义列"报错，绝不随便挑一个。</p>
 */
final class FilterBinder {

    /** 绑定后的取值表达式。 */
    interface BoundExpr {
        Object eval(List<Object> row) throws FilterException;

        String describe();
    }

    /** 绑定后的布尔谓词；{@code null} 表示 SQL UNKNOWN。 */
    interface BoundPredicate {
        Boolean test(List<Object> row) throws FilterException;
    }

    record BoundSortKey(BoundExpr expr, boolean ascending, boolean nullsFirst) {
    }

    record BoundFilter(BoundPredicate predicate, List<BoundSortKey> sortKeys, Integer limit, int offset) {
    }

    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final Map<String, Integer> exactIndex = new HashMap<>();
    private final Map<String, Integer> exactCount = new HashMap<>();
    private final Map<String, Integer> foldedIndex = new HashMap<>();
    private final Map<String, Integer> foldedCount = new HashMap<>();

    private FilterBinder(List<String> columnNames, List<String> columnTypes) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        for (int index = 0; index < columnNames.size(); index++) {
            String name = columnNames.get(index) == null ? "" : columnNames.get(index);
            exactIndex.putIfAbsent(name, index);
            exactCount.merge(name, 1, Integer::sum);
            String folded = name.toLowerCase(Locale.ROOT);
            foldedIndex.putIfAbsent(folded, index);
            foldedCount.merge(folded, 1, Integer::sum);
        }
    }

    static BoundFilter bind(FilterProgram program, List<String> columnNames, List<String> columnTypes)
            throws FilterException {
        return new FilterBinder(columnNames, columnTypes).bind(program);
    }

    private BoundFilter bind(FilterProgram program) throws FilterException {
        BoundPredicate predicate = program.predicate() == null ? null : bindPredicate(program.predicate());
        List<BoundSortKey> sortKeys = new ArrayList<>(program.orderBy().size());
        for (SortKey key : program.orderBy()) {
            sortKeys.add(new BoundSortKey(bindExpr(key.expr()), key.ascending(), key.nullsFirst()));
        }
        return new BoundFilter(predicate, List.copyOf(sortKeys), program.limit(), program.offset());
    }

    private BoundPredicate bindPredicate(Predicate predicate) throws FilterException {
        if (predicate instanceof Comparison comparison) {
            return new BoundComparison(bindExpr(comparison.left()), comparison.op(),
                    bindExpr(comparison.right()), comparison.position());
        }
        if (predicate instanceof FilterAst.And and) {
            return new BoundAnd(bindPredicate(and.left()), bindPredicate(and.right()));
        }
        if (predicate instanceof Or or) {
            return new BoundOr(bindPredicate(or.left()), bindPredicate(or.right()));
        }
        if (predicate instanceof Not not) {
            return new BoundNot(bindPredicate(not.operand()));
        }
        if (predicate instanceof IsNull isNull) {
            return new BoundIsNull(bindExpr(isNull.operand()), isNull.negated());
        }
        if (predicate instanceof InList inList) {
            List<BoundExpr> items = new ArrayList<>(inList.items().size());
            for (Expr item : inList.items()) {
                items.add(bindExpr(item));
            }
            return new BoundInList(bindExpr(inList.value()), List.copyOf(items), inList.negated());
        }
        if (predicate instanceof Like like) {
            Pattern pattern = FilterValues.compileLike(like.pattern(), like.escape(), like.position());
            return new BoundLike(bindExpr(like.value()), pattern, like.negated(), like.position());
        }
        throw new IllegalStateException("Unsupported filter predicate: " + predicate.getClass().getName());
    }

    private BoundExpr bindExpr(Expr expression) throws FilterException {
        if (expression instanceof Literal literal) {
            return new BoundLiteral(literal.value());
        }
        if (expression instanceof ColumnRef columnRef) {
            return bindColumn(columnRef);
        }
        if (expression instanceof FunctionCall functionCall) {
            return new BoundFunction(functionCall.name(), bindExpr(functionCall.argument()),
                    functionCall.position());
        }
        throw new IllegalStateException("Unsupported filter expression: " + expression.getClass().getName());
    }

    private BoundExpr bindColumn(ColumnRef reference) throws FilterException {
        Map<String, Integer> indexMap = reference.quoted() ? exactIndex : foldedIndex;
        Map<String, Integer> countMap = reference.quoted() ? exactCount : foldedCount;
        String key = reference.quoted() ? reference.name() : reference.name().toLowerCase(Locale.ROOT);

        Integer index = indexMap.get(key);
        if (index == null) {
            String available = columnNames.isEmpty() ? "<none>" : String.join(", ", columnNames);
            throw FilterException.at("unknown column '" + reference.name()
                    + "'. Available columns: " + available, reference.position());
        }
        int occurrences = countMap.getOrDefault(key, 1);
        if (occurrences > 1) {
            throw FilterException.at("ambiguous column '" + reference.name() + "' (matched "
                    + occurrences + " result columns)", reference.position());
        }
        String type = index < columnTypes.size() ? columnTypes.get(index) : "UNKNOWN";
        return new BoundColumn(index, columnNames.get(index), type);
    }

    // ---------------------------------------------------------------- 绑定节点

    record BoundLiteral(Object value) implements BoundExpr {
        @Override
        public Object eval(List<Object> row) {
            return value;
        }

        @Override
        public String describe() {
            return "value " + FilterValues.literalLabel(value);
        }
    }

    record BoundColumn(int index, String name, String type) implements BoundExpr {
        @Override
        public Object eval(List<Object> row) {
            return index < row.size() ? row.get(index) : null;
        }

        @Override
        public String describe() {
            return "column '" + name + "'";
        }
    }

    record BoundFunction(String name, BoundExpr argument, int position) implements BoundExpr {
        @Override
        public Object eval(List<Object> row) throws FilterException {
            Object value = argument.eval(row);
            if (value == null) {
                return null;
            }
            return FilterValues.applyFunction(name, value, position);
        }

        @Override
        public String describe() {
            return "function " + name + "(...)";
        }
    }

    record BoundComparison(BoundExpr left, FilterAst.Op op, BoundExpr right, int position)
            implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            Object leftValue = left.eval(row);
            Object rightValue = right.eval(row);
            if (leftValue == null || rightValue == null) {
                return null;
            }
            int order = FilterValues.compare(leftValue, rightValue,
                    left.describe(), right.describe(), position);
            return switch (op) {
                case EQ -> order == 0;
                case NE -> order != 0;
                case LT -> order < 0;
                case LE -> order <= 0;
                case GT -> order > 0;
                case GE -> order >= 0;
            };
        }
    }

    record BoundAnd(BoundPredicate left, BoundPredicate right) implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            Boolean leftValue = left.test(row);
            if (Boolean.FALSE.equals(leftValue)) {
                return Boolean.FALSE;
            }
            Boolean rightValue = right.test(row);
            if (Boolean.FALSE.equals(rightValue)) {
                return Boolean.FALSE;
            }
            if (leftValue == null || rightValue == null) {
                return null;
            }
            return Boolean.TRUE;
        }
    }

    record BoundOr(BoundPredicate left, BoundPredicate right) implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            Boolean leftValue = left.test(row);
            if (Boolean.TRUE.equals(leftValue)) {
                return Boolean.TRUE;
            }
            Boolean rightValue = right.test(row);
            if (Boolean.TRUE.equals(rightValue)) {
                return Boolean.TRUE;
            }
            if (leftValue == null || rightValue == null) {
                return null;
            }
            return Boolean.FALSE;
        }
    }

    record BoundNot(BoundPredicate operand) implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            Boolean value = operand.test(row);
            return value == null ? null : !value;
        }
    }

    record BoundIsNull(BoundExpr operand, boolean negated) implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            boolean isNull = operand.eval(row) == null;
            return negated ? !isNull : isNull;
        }
    }

    record BoundInList(BoundExpr value, List<BoundExpr> items, boolean negated)
            implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            Object target = value.eval(row);
            if (target == null) {
                return null;
            }
            boolean sawNull = false;
            for (BoundExpr item : items) {
                Object candidate = item.eval(row);
                if (candidate == null) {
                    sawNull = true;
                    continue;
                }
                if (FilterValues.compare(target, candidate, value.describe(), item.describe(), 0) == 0) {
                    return !negated;
                }
            }
            if (sawNull) {
                return null;
            }
            return negated;
        }
    }

    record BoundLike(BoundExpr value, Pattern pattern, boolean negated, int position)
            implements BoundPredicate {
        @Override
        public Boolean test(List<Object> row) throws FilterException {
            Object candidate = value.eval(row);
            if (candidate == null) {
                return null;
            }
            if (!(candidate instanceof String text)) {
                throw FilterException.at("LIKE requires a string value, got "
                        + FilterValues.typeName(candidate), position);
            }
            boolean matched = pattern.matcher(text).matches();
            return negated ? !matched : matched;
        }
    }
}
