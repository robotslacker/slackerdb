package org.slackerdb.dbserver.service.filter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterEvaluatorTest {

    private static Map<String, Object> baseData() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.<Object>of(1, "Alice", new BigDecimal("100"), List.of("x", "y"),
                "2026-01-01T00:00:00"));
        rows.add(List.<Object>of(2, "bob", new BigDecimal("200"), List.of("x"),
                "2026-02-01T00:00:00"));
        rows.add(List.<Object>of(3, "Carol", new BigDecimal("300"), List.of(),
                "2026-03-01T00:00:00"));
        rows.add(Arrays.<Object>asList(4, null, null, null, null));
        return data(List.of("id", "name", "amount", "tags", "created"),
                List.of("INTEGER", "VARCHAR", "DECIMAL(10,2)", "LIST", "TIMESTAMP"), rows);
    }

    private static Map<String, Object> data(List<String> columnNames, List<String> columnTypes,
                                            List<List<Object>> rows) {
        Map<String, Object> data = new HashMap<>();
        data.put("columnNames", columnNames);
        data.put("columnTypes", columnTypes);
        data.put("dataset", rows);
        data.put("timestamp", 123L);
        data.put("affectedRows", rows.size());
        return data;
    }

    private static Map<String, Object> apply(String filterText) throws FilterException {
        return DataFilter.apply(baseData(), DataFilter.parse(filterText));
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rows(Map<String, Object> data) {
        return (List<List<Object>>) data.get("dataset");
    }

    private static List<Integer> ids(Map<String, Object> data) {
        List<Integer> ids = new ArrayList<>();
        for (List<Object> row : rows(data)) {
            ids.add((Integer) row.get(0));
        }
        return ids;
    }

    private static FilterException assertRejected(String filterText) {
        return assertThrows(FilterException.class, () -> apply(filterText));
    }

    @Test
    void filtersAndSorts() throws Exception {
        Map<String, Object> result = apply("id >= 2 AND id < 4 ORDER BY amount DESC");
        assertEquals(List.of(3, 2), ids(result));
        assertEquals(2, result.get("affectedRows"));
        assertFalse(result.containsKey("totalRows"));
    }

    @Test
    void nullThreeValuedLogic() throws Exception {
        assertEquals(List.of(1), ids(apply("name = 'Alice'")));
        assertEquals(List.of(2, 3), ids(apply("NOT name = 'Alice'")));
        assertEquals(List.of(4), ids(apply("name IS NULL")));
        assertEquals(List.of(1, 2, 3), ids(apply("name IS NOT NULL")));
    }

    @Test
    void inAndNotInWithNull() throws Exception {
        assertEquals(List.of(1), ids(apply("name IN ('Alice', NULL)")));
        assertEquals(List.of(), ids(apply("name NOT IN ('Alice', NULL)")));
        assertEquals(List.of(1, 2), ids(apply("name IN ('Alice', 'bob')")));
        assertEquals(List.of(3), ids(apply("name NOT IN ('Alice', 'bob')")));
    }

    @Test
    void likePatterns() throws Exception {
        assertEquals(List.of(1), ids(apply("name LIKE 'A%'")));
        assertEquals(List.of(2, 3), ids(apply("name LIKE '%o%'")));
        assertEquals(List.of(2), ids(apply("name LIKE 'b_b'")));
        assertEquals(List.of(2, 3), ids(apply("name NOT LIKE 'A%'")));
    }

    @Test
    void likeEscape() throws Exception {
        Map<String, Object> data = data(List.of("v"), List.of("VARCHAR"), List.of(
                List.<Object>of("100%"),
                List.<Object>of("100x")));
        Map<String, Object> result = DataFilter.apply(data,
                DataFilter.parse("v LIKE '100!%' ESCAPE '!'"));
        assertEquals(1, rows(result).size());
        assertEquals("100%", rows(result).get(0).get(0));
    }

    @Test
    void stringFunctions() throws Exception {
        assertEquals(List.of(1), ids(apply("lower(name) = 'alice'")));
        assertEquals(List.of(2), ids(apply("upper(name) = 'BOB'")));
        assertEquals(List.of(1), ids(apply("lower(trim(name)) = 'alice'")));
        assertEquals(List.of(2), ids(apply("length(name) = 3")));
        assertEquals(List.of(1), ids(apply("length(tags) = 2")));
        assertEquals(List.of(4), ids(apply("lower(name) IS NULL")));
    }

    @Test
    void trimOnlyRemovesSpaces() throws Exception {
        Map<String, Object> data = data(List.of("name"), List.of("VARCHAR"), List.of(
                List.<Object>of("  Alice  "),
                List.<Object>of("\tAlice\t")));
        assertEquals(1, rows(DataFilter.apply(data,
                DataFilter.parse("trim(name) = 'Alice'"))).size());
    }

    @Test
    void lowerRejectsNonStrings() {
        FilterException exception = assertRejected("lower(id) = '1'");
        assertTrue(exception.getMessage().contains("expects a string"));
    }

    @Test
    void orderByNullsLastIsDefault() throws Exception {
        assertEquals(List.of(1, 2, 3, 4), ids(apply("ORDER BY amount ASC")));
        assertEquals(List.of(3, 2, 1, 4), ids(apply("ORDER BY amount DESC")));
        assertEquals(List.of(4, 3, 2, 1), ids(apply("ORDER BY amount DESC NULLS FIRST")));
        assertEquals(List.of(4, 1, 2, 3), ids(apply("ORDER BY amount ASC NULLS FIRST")));
    }

    @Test
    void orderByFunctionAndStability() throws Exception {
        assertEquals(List.of(1, 3, 2, 4), ids(apply("ORDER BY length(name) DESC")));
        assertEquals(List.of(1, 3, 2, 4), ids(apply("ORDER BY length(name) DESC, id ASC")));
    }

    @Test
    void paging() throws Exception {
        Map<String, Object> page = apply("ORDER BY id LIMIT 2 OFFSET 1");
        assertEquals(List.of(2, 3), ids(page));
        assertEquals(2, page.get("affectedRows"));

        Map<String, Object> empty = apply("LIMIT 0");
        assertEquals(List.of(), ids(empty));
        assertEquals(0, empty.get("affectedRows"));

        Map<String, Object> beyond = apply("OFFSET 10");
        assertEquals(List.of(), ids(beyond));
    }

    @Test
    void numericStringIsComparable() throws Exception {
        assertEquals(List.of(1), ids(apply("amount = '100'")));
        assertEquals(List.of(2), ids(apply("id = '2'")));
    }

    @Test
    void rejectsIncomparableTypes() {
        FilterException exception = assertRejected("amount = 'abc'");
        assertTrue(exception.getMessage().contains("cannot compare"));
    }

    @Test
    void rejectsUnknownColumn() {
        FilterException exception = assertRejected("nope = 1");
        assertTrue(exception.getMessage().contains("unknown column 'nope'"));
        assertTrue(exception.getMessage().contains("Available columns"));
    }

    @Test
    void rejectsAmbiguousColumn() {
        Map<String, Object> duplicate = new HashMap<>();
        duplicate.put("columnNames", List.of("id", "id"));
        duplicate.put("columnTypes", List.of("INTEGER", "INTEGER"));
        duplicate.put("dataset", List.of(List.of(1, 2)));
        FilterException unquoted = assertThrows(FilterException.class,
                () -> DataFilter.apply(duplicate, DataFilter.parse("id = 1")));
        assertTrue(unquoted.getMessage().contains("ambiguous column 'id'"));

        FilterException quoted = assertThrows(FilterException.class,
                () -> DataFilter.apply(duplicate, DataFilter.parse("\"id\" = 1")));
        assertTrue(quoted.getMessage().contains("ambiguous column 'id'"));
    }

    @Test
    void rejectsUnsortableList() {
        FilterException exception = assertRejected("ORDER BY tags");
        assertTrue(exception.getMessage().contains("not sortable"));
    }

    @Test
    void filterDoesNotMutateInputData() throws Exception {
        Map<String, Object> original = baseData();
        Map<String, Object> result = DataFilter.apply(original, DataFilter.parse("id = 1"));
        assertEquals(4, rows(original).size());
        assertEquals(1, rows(result).size());
        assertEquals(4, original.get("affectedRows"));
        assertEquals(1, result.get("affectedRows"));
    }
}
