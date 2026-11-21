package org.slackerdb.dbsyncer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablePatternTest {

    @Test
    void exactAndGlobPatternsExpandCaptures() {
        TablePattern exact = TablePattern.compile("public.orders", true);
        assertTrue(exact.matches("public.orders"));
        assertFalse(exact.matches("public.orders_2026"));
        assertEquals("orders", exact.expandTarget("{table}", "orders", "public.orders"));

        TablePattern glob = TablePattern.compile("public.orders_*", true);
        assertTrue(glob.matches("public.orders_2026"));
        assertEquals("orders2026", glob.expandTarget("orders{$1}", "orders_2026", "public.orders_2026"));
        assertEquals("orders2026", glob.expandTarget("orders$1", "orders_2026", "public.orders_2026"));
        assertEquals("orders2026", glob.expandTarget("orders{1}", "orders_2026", "public.orders_2026"));
    }

    @Test
    void inlineRegexAndFullRegexSupportGroups() {
        TablePattern inline = TablePattern.compile("public.orders{.*}", true);
        assertTrue(inline.matches("public.orders_2026"));
        assertEquals("orders_2026", inline.expandTarget("orders{$1}", "orders_2026", "public.orders_2026"));

        TablePattern regex = TablePattern.compile("/^public\\.(orders|items)_(.*)$/", true);
        assertTrue(regex.matches("public.items_eu"));
        assertEquals("items_eu", regex.expandTarget("$1_$2", "items_eu", "public.items_eu"));
        assertEquals("items-eu", regex.expandTarget("${1}-${2}", "items_eu", "public.items_eu"));
    }

    @Test
    void caseInsensitiveMatchingAndInvalidGroup() {
        TablePattern pattern = TablePattern.compile("public.orders{.*}", false);
        assertTrue(pattern.matches("PUBLIC.ORDERS_EU"));
        assertEquals("orders_EU", pattern.expandTarget("orders{$1}", "orders_eu", "PUBLIC.ORDERS_EU"));
        assertThrows(IllegalArgumentException.class,
                () -> pattern.expandTarget("orders$2", "orders_eu", "PUBLIC.ORDERS_EU"));
    }
}
