package org.slackerdb.dbcli.engine.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CommandCatalog#names()} 返回**登记顺序**（{@code Map.copyOf} 不保证迭代顺序，
 * 因此顺序单独存一份）。
 */
class CommandCatalogOrderTest {

    @Test
    @DisplayName("names() 按登记顺序；同一实现的多个名字相邻且按字母序（EXIT、QUIT）")
    void namesFollowRegistrationOrder() {
        assertEquals(List.of("ASSERT", "ECHO", "EXIT", "QUIT", "HELP", "HOST", "LOAD", "SET", "SLEEP",
                "SPOOL", "START", "WHENEVER"), CommandCatalog.names());
    }
}
