package org.slackerdb.dbserver.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbserver.configuration.ServerConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * dbserver 的 Quack token 配置验收：默认为空（随机 token）、显式值裁剪后保存、
 * 过短取值在配置阶段报错，以及配置文件读写。
 *
 * <p>断言消息用英文：surefire 在中文控制台上会把非 ASCII 打成乱码。</p>
 *
 * <p>归入 {@code live} 组：Quack 依赖额外的 {@code quack} 扩展，默认不参与常规回归。</p>
 */
@Tag("live")
class QuackTokenConfigurationTest {

    @Test
    @DisplayName("Default is empty: an empty token means DuckDB generates a random one")
    void emptyByDefault() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        assertEquals("", configuration.getQuackToken());
    }

    @Test
    @DisplayName("Explicit token is trimmed and stored as-is")
    void storesExplicitToken() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        configuration.setQuackToken("  my-quack-token  ");
        assertEquals("my-quack-token", configuration.getQuackToken());

        configuration.setQuackToken("");
        assertEquals("", configuration.getQuackToken());

        configuration.setQuackToken(null);
        assertEquals("", configuration.getQuackToken());
    }

    @Test
    @DisplayName("Tokens shorter than four characters are rejected at configuration time")
    void rejectsTooShortToken() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();

        for (String invalid : new String[]{"a", "abc", "  abc  "}) {
            ServerException e = assertThrows(ServerException.class,
                    () -> configuration.setQuackToken(invalid),
                    "must be rejected: [" + invalid + "]");
            assertEquals("SLACKERDB-00005", e.getErrorCode(), invalid);
        }

        // The minimum length itself is accepted.
        configuration.setQuackToken("abcd");
        assertEquals("abcd", configuration.getQuackToken());
    }

    @Test
    @DisplayName("quack_token is read from the configuration file")
    void loadsFromConfigurationFile(@TempDir Path tempDir) throws Exception {
        Path conf = tempDir.resolve("dbserver.conf");
        Files.writeString(conf, "quack_addr=0.0.0.0:9494\nquack_token=token-from-file\n");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.loadConfigurationFile(conf.toString());

        assertEquals("0.0.0.0:9494", configuration.getQuackAddr());
        assertEquals("token-from-file", configuration.getQuackToken());
    }

    @Test
    @DisplayName("An empty quack_token in the configuration file keeps the random-token default")
    void emptyConfigurationValueMeansRandom(@TempDir Path tempDir) throws Exception {
        Path conf = tempDir.resolve("dbserver.conf");
        Files.writeString(conf, "quack_token=\n");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.loadConfigurationFile(conf.toString());

        assertEquals("", configuration.getQuackToken());
    }
}
