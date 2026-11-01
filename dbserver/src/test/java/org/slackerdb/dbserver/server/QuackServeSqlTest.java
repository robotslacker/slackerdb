package org.slackerdb.dbserver.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 启动 Quack 服务的 SQL 拼装验收：未配置 token 时不传 {@code token} 参数（让 DuckDB
 * 随机生成），配置了则作为命名参数传入，且引号被正确转义。
 *
 * <p>归入 {@code live} 组：Quack 依赖额外的 {@code quack} 扩展，默认不参与常规回归。</p>
 */
@Tag("live")
class QuackServeSqlTest {

    @Test
    @DisplayName("No token: omit the token argument so DuckDB generates a random one")
    void omitsTokenWhenEmpty() {
        assertEquals(
                "CALL quack_serve('quack:0.0.0.0:9494', allow_other_hostname => true);",
                DBInstance.buildQuackServeSql("0.0.0.0:9494", ""));
        assertEquals(
                "CALL quack_serve('quack:0.0.0.0:9494', allow_other_hostname => true);",
                DBInstance.buildQuackServeSql("0.0.0.0:9494", null));
    }

    @Test
    @DisplayName("Explicit token: passed as a named argument")
    void passesToken() {
        assertEquals(
                "CALL quack_serve('quack:0.0.0.0:9494', token := 'my-token', allow_other_hostname => true);",
                DBInstance.buildQuackServeSql("0.0.0.0:9494", "my-token"));
    }

    @Test
    @DisplayName("Single quotes are escaped so they cannot break out of the SQL literal")
    void escapesQuotes() {
        assertEquals(
                "CALL quack_serve('quack:0.0.0.0:9494', token := 'a''b', allow_other_hostname => true);",
                DBInstance.buildQuackServeSql("0.0.0.0:9494", "a'b"));
    }
}
