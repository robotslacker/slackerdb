package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbproxy.client.AdminClient;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceException;
import org.slackerdb.dbproxy.instance.InstanceState;
import org.slackerdb.dbproxy.server.ProxyInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 通道（PG 口的 {@code !} 管理帧）端到端测试。
 *
 * <p>命令解析、下发、回显这一层不在 {@link InstanceLifecycleTest} 的覆盖范围内；
 * 这里用代理自带的 {@link AdminClient} 走一遍 create / list / destroy，
 * 确保 {@code dbproxy create|list|destroy} 真的能驱动实例目录。</p>
 */
@Timeout(120)
class AdminCliTest {

    @TempDir
    static Path root;

    private static ProxyInstance proxy;
    private static InstanceController controller;
    private static ServerConfiguration clientConfiguration;

    @BeforeAll
    static void init() throws Exception {
        ServerConfiguration configuration = new ServerConfiguration();
        configuration.setPort(0);
        configuration.setPortX(0);
        configuration.setData_Dir(root.toString());

        proxy = new ProxyInstance(configuration);
        proxy.start();
        controller = proxy.getInstanceController();

        // 客户端配置：只用来定位代理的 PG 口
        clientConfiguration = new ServerConfiguration();
        clientConfiguration.setBindHost("127.0.0.1");
        clientConfiguration.setPort(String.valueOf(configuration.getPort()));
    }

    @AfterAll
    static void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
    }

    @Test
    @DisplayName("cli: create --start false / list / destroy drive the instance directory")
    void cliCommandsDriveTheDirectory() {
        Map<String, String> createOptions = new LinkedHashMap<>();
        createOptions.put("alias", "clidb");
        createOptions.put("start", "false");
        AdminClient.doCommand(clientConfiguration, "create", createOptions);

        assertEquals(InstanceState.STOPPED, controller.status("clidb").state(),
                "create with --start false must only write the instance directory");
        assertTrue(Files.isRegularFile(root.resolve("clidb").resolve("instance.conf")));

        AdminClient.doCommand(clientConfiguration, "list", Map.of());

        AdminClient.doCommand(clientConfiguration, "destroy", Map.of("alias", "clidb"));
        assertThrows(InstanceException.class, () -> controller.status("clidb"));
        assertTrue(Files.notExists(root.resolve("clidb")));
    }

    @Test
    @DisplayName("cli: a rejected command must not create anything")
    void invalidAliasIsRejected() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("alias", "Bad/Alias");
        AdminClient.doCommand(clientConfiguration, "create", options);

        assertThrows(InstanceException.class, () -> controller.status("bad/alias"));
    }
}
