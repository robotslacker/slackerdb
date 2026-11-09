package org.slackerdb.dbserver.service.filter;

import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.service.filter.FilterAst.FilterProgram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterParserTest {

    private static FilterProgram parse(String text) throws FilterException {
        return DataFilter.parse(text);
    }

    private static void assertInvalid(String text, String expectedFragment) {
        FilterException exception = assertThrows(FilterException.class, () -> parse(text),
                "expected filter to be rejected: " + text);
        assertTrue(exception.getMessage().contains(expectedFragment),
                "message should contain [" + expectedFragment + "] but was: " + exception.getMessage());
    }

    @Test
    void parsesComparisonAndLike() throws Exception {
        FilterProgram program = parse("age >= 18 AND city LIKE '北%'");
        assertNotNull(program.predicate());
        assertTrue(program.orderBy().isEmpty());
        assertNull(program.limit());
        assertEquals(0, program.offset());
    }

    @Test
    void whereKeywordIsOptional() throws Exception {
        assertNotNull(parse("WHERE age = 1").predicate());
        assertNotNull(parse("age = 1").predicate());
    }

    @Test
    void parsesOrderByAndPaging() throws Exception {
        FilterProgram program = parse(
                "name = 'x' ORDER BY amount DESC NULLS FIRST, lower(city) ASC LIMIT 10 OFFSET 5");
        assertEquals(2, program.orderBy().size());
        assertFalse(program.orderBy().get(0).ascending());
        assertTrue(program.orderBy().get(0).nullsFirst());
        assertTrue(program.orderBy().get(1).ascending());
        assertFalse(program.orderBy().get(1).nullsFirst());
        assertEquals(10, program.limit());
        assertEquals(5, program.offset());
    }

    @Test
    void nullsLastIsDefaultForBothDirections() throws Exception {
        FilterProgram ascending = parse("ORDER BY amount ASC");
        FilterProgram descending = parse("ORDER BY amount DESC");
        assertFalse(ascending.orderBy().get(0).nullsFirst());
        assertFalse(descending.orderBy().get(0).nullsFirst());
    }

    @Test
    void limitAllMeansUnlimited() throws Exception {
        assertNull(parse("LIMIT ALL").limit());
        assertNull(parse("a = 1 LIMIT ALL OFFSET 3").limit());
    }

    @Test
    void offsetAndLimitOrderIsFlexible() throws Exception {
        FilterProgram program = parse("a = 1 OFFSET 3 LIMIT 7");
        assertEquals(7, program.limit());
        assertEquals(3, program.offset());
    }

    @Test
    void supportsOnlyOrderOrOnlyPaging() throws Exception {
        FilterProgram orderOnly = parse("ORDER BY id");
        assertNull(orderOnly.predicate());
        assertEquals(1, orderOnly.orderBy().size());

        FilterProgram pagingOnly = parse("LIMIT 3");
        assertNull(pagingOnly.predicate());
        assertEquals(3, pagingOnly.limit());
    }

    @Test
    void parsesNestedFunctions() throws Exception {
        FilterProgram program = parse("lower(trim(name)) = 'x' ORDER BY length(tags) DESC");
        assertNotNull(program.predicate());
        assertEquals(1, program.orderBy().size());
    }

    @Test
    void functionNamesAreCaseInsensitive() throws Exception {
        assertNotNull(parse("LOWER(name) = 'x'").predicate());
        assertNotNull(parse("Length(name) > 2").predicate());
    }

    @Test
    void keywordLikeColumnRequiresQuotes() throws Exception {
        assertNotNull(parse("\"limit\" = 1").predicate());
        assertInvalid("limit = 1", "LIMIT must be a non-negative integer");
    }

    @Test
    void rejectsInvalidExpressions() {
        assertInvalid("", "empty filter expression");
        assertInvalid("   ", "empty filter expression");
        assertInvalid("a =", "expected a value");
        assertInvalid("a IN ()", "IN list must not be empty");
        assertInvalid("a LIKE name", "LIKE pattern must be a string literal");
        assertInvalid("substr(name) = 'a'", "unknown function 'substr'");
        assertInvalid("lower() = 'a'", "expects exactly 1 argument, got 0");
        assertInvalid("lower(a, b) = 'a'", "expects exactly 1 argument, got 2");
        assertInvalid("a NOT 1", "expected IN or LIKE after NOT");
        assertInvalid("a = 1 b = 2", "unexpected token 'b'");
        assertInvalid("LIMIT -1", "LIMIT must be a non-negative integer");
        assertInvalid("LIMIT 1.5", "LIMIT must be a non-negative integer");
        assertInvalid("OFFSET -2", "OFFSET must be a non-negative integer");
        assertInvalid("a = 'unterminated", "unterminated string literal");
        assertInvalid("ORDER BY", "ORDER BY requires a column name or function");        assertInvalid("LIMIT 1 ORDER BY a", "unexpected token 'ORDER'");
        assertInvalid("a IS 1", "expected NULL after IS");
        assertInvalid("a LIKE 'x' ESCAPE 'ab'", "ESCAPE must be a single character");
    }

    @Test
    void rejectsTooLongFilter() {
        String tooLong = "a = 1 AND ".repeat(1000);
        assertInvalid(tooLong, "filter text exceeds 8192 characters");
    }

    @Test
    void rejectsTooDeepNesting() {
        String deep = "(".repeat(40) + "a = 1" + ")".repeat(40);
        assertInvalid(deep, "nesting exceeds 32 levels");
    }

    @Test
    void rejectsTooManyInItems() {
        StringBuilder expression = new StringBuilder("a IN (1");
        for (int index = 0; index < 1001; index++) {
            expression.append(", 1");
        }
        expression.append(')');
        assertInvalid(expression.toString(), "IN list exceeds 1000 items");
    }
}
