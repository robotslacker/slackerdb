package org.slackerdb.dbcli.ssh;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slackerdb.dbcli.common.CommandOutcome;
import org.slackerdb.dbcli.script.SqlAnalyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSH/SFTP 真实连通性集成测试（端到端验证）。
 *
 * <p><b>默认不运行</b>：只有显式给出主机时才启用，CI 无需任何 SSH 环境。跑法：</p>
 * <pre>
 *   mvn -B -pl dbcli test ^
 *     "-Ddbcli.ssh.host=192.168.183.128" "-Ddbcli.ssh.user=root" "-Ddbcli.ssh.password=123456"
 * </pre>
 *
 * <p><b>口令只从系统属性读，不写进源码</b>：仓库里不留任何凭据（避免把测试机的口令提交上去）。</p>
 *
 * <p>覆盖：口令认证连接、命令执行（stdout/退出码）、SFTP 往返（mkdir/put/get/listdir/rename/
 * chmod/truncate/remove）以及经分派器的 {@code _SSH} 全链路。</p>
 */
@EnabledIfSystemProperty(named = "dbcli.ssh.host", matches = ".+")
class SshClientIntegrationTest {

    /** 远端测试目录（用完即删，避免污染测试机）。 */
    private static final String REMOTE_DIR = "/tmp/dbcli-it";

    private static SshClient client;
    private static Path localDir;

    private static SshConnectOptions options() {
        String host = System.getProperty("dbcli.ssh.host");
        String user = System.getProperty("dbcli.ssh.user", "root");
        String password = System.getProperty("dbcli.ssh.password");
        String keyFile = System.getProperty("dbcli.ssh.keyfile");
        int port = Integer.parseInt(System.getProperty("dbcli.ssh.port", "22"));
        SshConnectOptions options = keyFile != null
                ? SshConnectOptions.keyFile(host, user, keyFile)
                : SshConnectOptions.password(host, user, password);
        return options.withPort(port);
    }

    @BeforeAll
    static void connectAndPrepare() throws Exception {
        client = new SshClient(20_000);
        client.connect(options());
        assertTrue(client.isConnected(), "连接后 isConnected 应为 true");

        // 准备一个干净的远端目录 + 一个本地临时文件
        client.execute("rm -rf " + REMOTE_DIR);
        SshCommandResult mkdir = client.execute("mkdir -p " + REMOTE_DIR);
        assertEquals(0, mkdir.exitStatus(), "准备目录失败: " + mkdir.errorText());

        localDir = Files.createTempDirectory("dbcli-ssh-it");
    }

    @AfterAll
    static void disconnectAndCleanup() throws Exception {
        if (client != null && client.isConnected()) {
            client.execute("rm -rf " + REMOTE_DIR);
            client.close();
        }
        if (localDir != null) {
            try (var paths = Files.walk(localDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 清理失败不影响测试结论
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("命令执行：stdout 与退出码（含算术展开，证明远端真的执行了）")
    void executeCommand() throws Exception {
        // 用 id -u 而不是解析 id 的输出：测试机可能是中文 locale（实测 "用户id=0(root)"），
        // 断言不能依赖被本地化过的文案
        SshCommandResult uid = client.execute("id -u");
        assertEquals(0, uid.exitStatus());
        assertEquals("0", uid.stdout().strip(), "应当以 root（uid 0）执行");

        SshCommandResult arith = client.execute("echo dbcli-$((1+2))");
        assertEquals("dbcli-3", arith.stdout().strip());

        SshCommandResult failed = client.execute("exit 7");
        assertEquals(7, failed.exitStatus(), "退出码必须透传");

        SshCommandResult stderr = client.execute("echo to-stderr 1>&2");
        assertTrue(stderr.stderr().contains("to-stderr"), "stderr 必须单独收集，实际: " + stderr.stderr());
        assertTrue(stderr.combinedOutput().contains("to-stderr"), "combinedOutput 应含 stderr");
    }

    @Test
    @DisplayName("SFTP 往返：put → get → listdir → rename → chmod → truncate → remove")
    void sftpRoundTrip() throws Exception {
        Path localFile = localDir.resolve("payload.txt");
        String content = "dbcli-sftp-往返\n第二行\n";
        Files.writeString(localFile, content, StandardCharsets.UTF_8);

        String remoteFile = REMOTE_DIR + "/payload.txt";
        client.sftpPut(localFile, remoteFile);

        // 远端内容一致（走 SFTP 下载校验，避免依赖远端命令）
        assertEquals(content, client.readRemoteText(remoteFile), "上传后内容应当一致");

        // listdir 能看到它
        assertTrue(client.sftpListDir(REMOTE_DIR).contains("payload.txt"),
                "listdir 应包含刚上传的文件，实际: " + client.sftpListDir(REMOTE_DIR));

        // 下载到另一个本地文件并比对
        Path downloaded = localDir.resolve("downloaded.txt");
        client.sftpGet(remoteFile, downloaded);
        assertEquals(content, Files.readString(downloaded, StandardCharsets.UTF_8));

        // rename
        String renamed = REMOTE_DIR + "/renamed.txt";
        client.sftpRename(remoteFile, renamed);
        assertFalse(client.sftpListDir(REMOTE_DIR).contains("payload.txt"), "改名后原名不应存在");
        assertTrue(client.sftpListDir(REMOTE_DIR).contains("renamed.txt"));

        // chmod（用 stat 验证真实权限）
        client.sftpChmod(renamed, Integer.parseInt("600", 8));
        SshCommandResult perm = client.execute("stat -c %a " + renamed);
        assertEquals("600", perm.stdout().strip(), "chmod 应当生效");

        // truncate
        client.sftpTruncate(renamed, 5);
        SshCommandResult size = client.execute("stat -c %s " + renamed);
        assertEquals("5", size.stdout().strip(), "truncate 应当生效");

        // remove
        client.sftpRemove(renamed);
        assertFalse(client.sftpListDir(REMOTE_DIR).contains("renamed.txt"), "删除后不应存在");
    }

    @Test
    @DisplayName("chdir/cwd 与 mkdir（带八进制权限）")
    void sftpDirectoryOps() throws Exception {
        String subDir = REMOTE_DIR + "/sub";
        client.sftpMkdir(subDir, Integer.parseInt("750", 8));

        String originalDir = client.sftpCwd();
        client.sftpChdir(subDir);
        assertTrue(client.sftpCwd().endsWith("/sub"), "chdir 后 cwd 应当变化，实际: " + client.sftpCwd());
        client.sftpChdir(originalDir);

        SshCommandResult perm = client.execute("stat -c %a " + subDir);
        assertEquals("750", perm.stdout().strip(), "mkdir 的 mode 应当生效（JSch 用 mkdir+chmod 等价实现）");
    }

    @Test
    @DisplayName("端口：SshConnectOptions.describe() 用真实端口；端口由系统属性给出（默认 22）")
    void portIsCarriedByTheOptions() {
        int port = Integer.parseInt(System.getProperty("dbcli.ssh.port", "22"));
        assertEquals(port, options().port(), "端口必须从系统属性进到连接选项");
        assertTrue(options().describe().endsWith(":" + port + (options().keyFile() != null
                        ? " (keyfile)" : " (password)")),
                "describe() 里应当能看到真实端口，实际: " + options().describe());
        assertTrue(client.isConnected(), "端口生效时连接应当成功（否则上面的 connect 就失败了）");
    }

    @Test
    @DisplayName("经分派器的 _SSH 全链路：connect/execute/sftp_listdir 产出冻结的同款文案")
    void dispatcherEndToEnd() throws Exception {
        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), SshClient::new);
        try {
            String host = System.getProperty("dbcli.ssh.host");
            String user = System.getProperty("dbcli.ssh.user", "root");
            String password = System.getProperty("dbcli.ssh.password");

            Map<String, Object> connectIr = new LinkedHashMap<>();
            connectIr.put("action", "connect");
            connectIr.put("host", host);
            connectIr.put("user", user);
            // PORT 子句的端到端验证：把系统属性给的端口放进 IR（真实跑法里由 _SSH … PORT <n> 产出）
            connectIr.put("port", Integer.parseInt(System.getProperty("dbcli.ssh.port", "22")));
            if (password != null) {
                connectIr.put("password", password);
            } else {
                connectIr.put("keyFile", System.getProperty("dbcli.ssh.keyfile"));
            }
            List<CommandOutcome> connected = dispatcher.execute(connectIr);
            assertEquals(List.of("SSH connected"), statuses(connected));

            List<CommandOutcome> executed = dispatcher.execute(
                    ir("action", "execute", "command", "echo dispatcher-ok"));
            assertEquals(List.of("dispatcher-ok", "< Command finished with [0]"), statuses(executed));

            List<CommandOutcome> listed = dispatcher.execute(
                    ir("action", "sftp_listdir", "dir", REMOTE_DIR));
            assertEquals(1, listed.size());
            assertTrue(listed.get(0) instanceof CommandOutcome.Table,
                    "sftp_listdir 应当产出结果集，实际: " + listed);

            assertEquals(List.of("SSH disconnected"), statuses(dispatcher.execute(ir("action", "disconnect"))));
        } finally {
            dispatcher.registry().close();
        }
    }

    @Test
    @DisplayName("_SSH … PORT <n> 全链路：脚本文本 → IR(port) → 真机连接（端口来自系统属性）")
    void sshPortEndToEnd() throws Exception {
        String host = System.getProperty("dbcli.ssh.host");
        String user = System.getProperty("dbcli.ssh.user", "root");
        String password = System.getProperty("dbcli.ssh.password");
        int port = Integer.parseInt(System.getProperty("dbcli.ssh.port", "22"));
        org.junit.jupiter.api.Assumptions.assumeTrue(password != null, "本次只验口令形态");

        String script = "_ssh connect " + host + " with user " + user + " password " + password
                + " port " + port;
        Map<String, Object> ir = SqlAnalyzer.analyze(script).parsedObject();
        assertEquals("connect", ir.get("action"), "IR: " + ir);
        assertEquals(port, ir.get("port"), "PORT 子句必须进 IR 的 port: " + ir);

        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), SshClient::new);
        try {
            assertEquals(List.of("SSH connected"), statuses(dispatcher.execute(ir)),
                    "带 PORT 的连接应当真的连上（端口是 " + port + "）");
            assertEquals(List.of("port-ok", "< Command finished with [0]"),
                    statuses(dispatcher.execute(ir("action", "execute", "command", "echo port-ok"))));
            assertEquals(List.of("SSH disconnected"), statuses(dispatcher.execute(ir("action", "disconnect"))));
        } finally {
            dispatcher.registry().close();
        }
    }

    private static List<String> statuses(List<CommandOutcome> outcomes) {
        return outcomes.stream()
                .filter(CommandOutcome.Status.class::isInstance)
                .map(CommandOutcome.Status.class::cast)
                .map(CommandOutcome.Status::text)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
