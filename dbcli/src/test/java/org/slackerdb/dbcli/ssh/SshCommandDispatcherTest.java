package org.slackerdb.dbcli.ssh;

import org.slackerdb.dbcli.common.CommandOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _SSH} 动作分派与文案的契约测试。
 *
 * <p>用假 {@link SshOperations} 驱动，**不需要任何 SSH 主机**：这里验证的是"IR → 打印什么"
 * 的逻辑与逐字节冻结的文案。真实连通性见 {@code SshClientIntegrationTest}。</p>
 *
 * <p>覆盖的文案：{@code SSH connected} / {@code SSH disconnected} / {@code SSH session saved.} /
 * {@code SSH session restored.} / {@code < Command finished with [0]} / {@code SSH not connected.} /
 * {@code SFTP not connected.} / {@code Unkonwn ssh option [...]}（拼写已冻结）/
 * {@code mkdir failed. OSError (...)}（mkdir 多一个空格）/ 其它 {@code xxx failed. OSError(...)}。</p>
 */
class SshCommandDispatcherTest {

    /** 假实现：把调用记录下来，返回预设结果。 */
    private static final class FakeSsh implements SshOperations {
        private final List<String> calls = new ArrayList<>();
        private final Map<String, String> remoteFiles = new LinkedHashMap<>();
        private boolean connected;
        private String encoding = "UTF-8";
        private String commandOutput = "";
        private String commandError = "";
        private int exitStatus = 0;
        private boolean failOnSetup;
        private String setupError = "Auth fail";
        private String failNextCall;

        void setCommandResult(String output, String error, int status) {
            this.commandOutput = output;
            this.commandError = error;
            this.exitStatus = status;
            this.timedOut = false;
        }

        private boolean timedOut;

        /** 模拟"命令超时被中断"：已读到部分输出，没有退出码。 */
        void setCommandTimeout(String partialOutput, String message) {
            this.commandOutput = partialOutput;
            this.commandError = message;
            this.exitStatus = SshCommandResult.TIMEOUT_EXIT_STATUS;
            this.timedOut = true;
        }

        void failConnection(String message) {
            this.failOnSetup = true;
            this.setupError = message;
        }

        void failNextCallWith(String message) {
            this.failNextCall = message;
        }

        @Override
        public void setCommandTimeout(long timeoutMillis) {
            this.commandTimeoutMillis = timeoutMillis;
            calls.add("setCommandTimeout " + timeoutMillis);
        }

        private long commandTimeoutMillis = SshOperations.NO_COMMAND_TIMEOUT;

        long commandTimeoutMillis() {
            return commandTimeoutMillis;
        }

        @Override
        public void connect(SshConnectOptions options) throws Exception {
            calls.add("connect " + options.describe());
            if (failOnSetup) {
                throw new Exception(setupError);
            }
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public SshCommandResult execute(String command) throws Exception {
            calls.add("execute " + command);
            return new SshCommandResult(command, commandOutput, commandError, exitStatus, timedOut);
        }

        @Override
        public String sftpCwd() {
            calls.add("sftpCwd");
            return "/root";
        }

        @Override
        public void sftpChmod(String path, int permissions) {
            calls.add("sftpChmod " + path + " " + Integer.toOctalString(permissions));
        }

        @Override
        public void sftpChdir(String path) {
            calls.add("sftpChdir " + path);
        }

        @Override
        public void sftpMkdir(String path, int permissions) throws Exception {
            calls.add("sftpMkdir " + path + " " + Integer.toOctalString(permissions));
            failOnce();
        }

        @Override
        public void sftpChown(String path, int uid, int gid) {
            calls.add("sftpChown " + path + " " + uid + " " + gid);
        }

        @Override
        public void sftpGet(String remotePath, Path localFile) {
            calls.add("sftpGet " + remotePath + " -> " + localFile);
        }

        @Override
        public void sftpPut(Path localFile, String remotePath) {
            calls.add("sftpPut " + localFile + " -> " + remotePath);
            remoteFiles.put(remotePath, "content");
        }

        @Override
        public void sftpRemove(String path) throws Exception {
            calls.add("sftpRemove " + path);
            failOnce();
        }

        /** 只让"下一次调用"失败（失败后清空，避免影响后面的测试断言）。 */
        private void failOnce() throws Exception {
            if (failNextCall != null) {
                String message = failNextCall;
                failNextCall = null;
                throw new Exception(message);
            }
        }

        @Override
        public void sftpRename(String from, String to) {
            calls.add("sftpRename " + from + " -> " + to);
        }

        @Override
        public List<String> sftpListDir(String path) {
            calls.add("sftpListDir " + path);
            return List.of("a.txt", "b.txt");
        }

        @Override
        public void sftpTruncate(String path, long size) {
            calls.add("sftpTruncate " + path + " " + size);
        }

        @Override
        public void close() {
            calls.add("close");
            connected = false;
        }
    }

    private static Map<String, Object> ir(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<String> statusTexts(List<CommandOutcome> outcomes) {
        List<String> texts = new ArrayList<>();
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Status status && status.text() != null) {
                texts.add(status.text());
            }
        }
        return texts;
    }

    private static String firstError(List<CommandOutcome> outcomes) {
        for (CommandOutcome outcome : outcomes) {
            if (outcome instanceof CommandOutcome.Error error) {
                return error.message();
            }
        }
        return null;
    }

    @Test
    @DisplayName("connect → SSH connected；disconnect → SSH disconnected")
    void connectAndDisconnect() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), () -> fake);

        List<CommandOutcome> connect = dispatcher.execute(
                ir("action", "connect", "host", "192.168.183.128", "user", "root", "password", "123456"));
        // connect 时就 chdir('.') 打开 SFTP，并给出一行状态
        assertTexts(List.of("SSH connected"), connect);
        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpChdir ."),
                "connect 时必须 chdir('.') 打开 SFTP");

        assertTexts(List.of("SSH disconnected"), dispatcher.execute(ir("action", "disconnect")));
    }

    @Test
    @DisplayName("认证失败 → AuthenticationException failed.（文案已冻结）")
    void authenticationFailure() {
        FakeSsh fake = new FakeSsh();
        fake.failConnection("Auth fail");
        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), () -> fake);

        List<CommandOutcome> outcomes = dispatcher.execute(
                ir("action", "connect", "host", "h", "user", "u", "password", "bad"));
        org.junit.jupiter.api.Assertions.assertEquals("AuthenticationException failed.", firstError(outcomes));
    }

    @Test
    @DisplayName("connect 的 PORT：给了就用它（describe 里是 host:port），没给用默认 22")
    void connectPort() {
        FakeSsh explicit = new FakeSsh();
        SshCommandDispatcher withPort = new SshCommandDispatcher(new SshSessionRegistry(), () -> explicit);
        withPort.execute(ir("action", "connect", "host", "node1", "user", "root",
                "password", "p", "port", 2222));
        assertTrue(explicit.calls.contains("connect root@node1:2222 (password)"),
                "IR 的 port 必须传到 SshConnectOptions，实际调用: " + explicit.calls);

        FakeSsh implied = new FakeSsh();
        SshCommandDispatcher withoutPort = new SshCommandDispatcher(new SshSessionRegistry(), () -> implied);
        withoutPort.execute(ir("action", "connect", "host", "node1", "user", "root", "password", "p"));
        assertTrue(implied.calls.contains("connect root@node1:22 (password)"),
                "IR 里没有 port 时应当是默认 22，实际调用: " + implied.calls);

        FakeSsh key = new FakeSsh();
        SshCommandDispatcher withKey = new SshCommandDispatcher(new SshSessionRegistry(), () -> key);
        withKey.execute(ir("action", "connect", "host", "node1", "user", "root",
                "keyFile", "/root/.ssh/id_rsa", "port", 22022));
        assertTrue(key.calls.contains("connect root@node1:22022 (keyfile)"),
                "KEYFILE 形态同样支持 PORT，实际调用: " + key.calls);
    }

    @Test
    @DisplayName("connect 的 PORT 非法：可读错误 + 不建连接（1..65535 之外或非数字）")
    void connectPortValidation() {
        int[] created = {0};
        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), () -> {
            created[0]++;
            return new FakeSsh();
        });

        for (Object bad : new Object[] {0, 65536, 70000, "abc"}) {
            List<CommandOutcome> outcomes = dispatcher.execute(
                    ir("action", "connect", "host", "node1", "user", "root", "password", "p", "port", bad));
            assertEquals("Invalid SSH port [" + bad + "]: expected an integer in 1..65535.",
                    firstError(outcomes), "非法端口 " + bad + " 必须当场报错");
        }
        assertEquals(0, created[0], "端口非法时不应创建连接对象（更不该真的去连）");
    }

    @Test
    @DisplayName("execute：输出逐行 + 最后一行 < Command finished with [rc]（stderr 也并进来）")
    void executeStreamsLinesThenExitStatus() {
        FakeSsh fake = new FakeSsh();
        fake.setCommandResult("line1\nline2\n", "warn\n", 3);
        SshCommandDispatcher dispatcher = connected(fake);

        List<CommandOutcome> outcomes = dispatcher.execute(ir("action", "execute", "command", "ls"));
        assertTexts(List.of("line1", "line2", "warn", "< Command finished with [3]"), outcomes);
    }

    @Test
    @DisplayName("execute 超时：已读到的部分输出照常打印，最后是一条可读错误（不是 exit status）")
    void executeTimeoutIsAnErrorWithPartialOutput() {
        FakeSsh fake = new FakeSsh();
        fake.setCommandTimeout("partial line\n", "SSH command timed out after 30 seconds.");
        SshCommandDispatcher dispatcher = connected(fake);

        List<CommandOutcome> outcomes = dispatcher.execute(ir("action", "execute", "command", "tail -f x"));
        assertTexts(List.of("partial line", "SSH command timed out after 30 seconds."), outcomes);
        assertTrue(firstError(outcomes) != null && firstError(outcomes).contains("timed out"),
                "超时必须计入错误（_WHENEVER / 错误计数才会生效）: " + outcomes);
        assertFalse(statusTexts(outcomes).stream().anyMatch(text -> text.startsWith("< Command finished")),
                "超时的命令没有退出码，不能报'已结束'");
    }

    @Test
    @DisplayName("_SSH SET TIMEOUT = <秒>：默认不限；设置对当前连接生效并被新连接继承；0 表示关闭")
    void commandTimeoutSetting() {
        FakeSsh first = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(first);
        assertEquals(SshOperations.NO_COMMAND_TIMEOUT, first.commandTimeoutMillis(),
                "默认不给远端命令加时限");

        // 连接中设置：当前连接立即生效
        assertTexts(List.of("SSH command timeout set to 30 seconds."),
                dispatcher.execute(ir("action", "set", "option", "timeout", "value", "30")));
        assertEquals(30_000L, first.commandTimeoutMillis());

        // 新连接继承设置（timeout 是分派器级设置，先设置后连接也生效）
        dispatcher.execute(ir("action", "disconnect"));
        FakeSsh second = new FakeSsh();
        SshCommandDispatcher inherited = new SshCommandDispatcher(dispatcher.registry(), () -> second);
        assertTexts(List.of("SSH command timeout set to 45 seconds."),
                inherited.execute(ir("action", "set", "option", "timeout", "value", "45")));
        inherited.execute(ir("action", "connect", "host", "h", "user", "u", "password", "p"));
        assertEquals(45_000L, second.commandTimeoutMillis(), "连接之前设置的值必须被新连接继承");

        // 0 = 关闭
        assertTexts(List.of("SSH command timeout disabled."),
                inherited.execute(ir("action", "set", "option", "timeout", "value", "0")));
        assertEquals(SshOperations.NO_COMMAND_TIMEOUT, second.commandTimeoutMillis());
    }

    @Test
    @DisplayName("_SSH SET TIMEOUT 非法值：可读错误（不给远端命令留下半个设置）")
    void commandTimeoutValidation() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(fake);

        assertEquals("Invalid SSH timeout [abc]: expected a non-negative number of seconds.",
                firstError(dispatcher.execute(ir("action", "set", "option", "timeout", "value", "abc"))));
        assertEquals("Invalid SSH timeout [-1]: expected a non-negative number of seconds.",
                firstError(dispatcher.execute(ir("action", "set", "option", "timeout", "value", "-1"))));
        assertEquals(SshOperations.NO_COMMAND_TIMEOUT, fake.commandTimeoutMillis(),
                "非法值不得改动当前设置");
    }

    @Test
    @DisplayName("未连接时的错误文案：execute/set → SSH not connected.；sftp_* → SFTP not connected.")
    void notConnectedTexts() {
        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), FakeSsh::new);

        org.junit.jupiter.api.Assertions.assertEquals("SSH not connected.",
                firstError(dispatcher.execute(ir("action", "execute", "command", "ls"))));
        org.junit.jupiter.api.Assertions.assertEquals("SSH not connected.",
                firstError(dispatcher.execute(ir("action", "set", "option", "encoding", "value", "UTF-8"))));
        org.junit.jupiter.api.Assertions.assertEquals("SFTP not connected.",
                firstError(dispatcher.execute(ir("action", "sftp_cwd"))));
    }

    @Test
    @DisplayName("save/restore 文案；restore 不存在的名字立刻报错")
    void saveRestore() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(fake);

        assertTexts(List.of("SSH session saved."),
                dispatcher.execute(ir("action", "save", "sessionName", "node1")));
        assertTexts(List.of("SSH session restored."),
                dispatcher.execute(ir("action", "restore", "sessionName", "node1")));

        List<CommandOutcome> bad = dispatcher.execute(ir("action", "restore", "sessionName", "nope"));
        org.junit.jupiter.api.Assertions.assertTrue(firstError(bad).contains("not found"),
                "restore 不存在的名字应当立刻报错，实际: " + firstError(bad));
    }

    @Test
    @DisplayName("set encoding：UTF-8 接受（无输出）；非 UTF-8 明确拒绝；未知选项的拼写已冻结")
    void setOptionTexts() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(fake);

        List<CommandOutcome> utf8 = dispatcher.execute(
                ir("action", "set", "option", "encoding", "value", "UTF-8"));
        org.junit.jupiter.api.Assertions.assertNull(firstError(utf8), "UTF-8 应当被接受");
        org.junit.jupiter.api.Assertions.assertEquals(1, utf8.size());
        org.junit.jupiter.api.Assertions.assertNull(
                ((CommandOutcome.Status) utf8.get(0)).text(), "无输出");

        List<CommandOutcome> gbk = dispatcher.execute(
                ir("action", "set", "option", "encoding", "value", "GBK"));
        org.junit.jupiter.api.Assertions.assertTrue(firstError(gbk).contains("only supports UTF-8"),
                "非 UTF-8 编码要明确拒绝，实际: " + firstError(gbk));
        org.junit.jupiter.api.Assertions.assertFalse(fake.calls.contains("setEncoding GBK"),
                "被拒绝的编码不应落到客户端");

        List<CommandOutcome> bad = dispatcher.execute(
                ir("action", "set", "option", "nosuch", "value", "x"));
        org.junit.jupiter.api.Assertions.assertEquals("Unkonwn ssh option [nosuch].", firstError(bad));
    }

    @Test
    @DisplayName("sftp_listdir 产出一个结果集（表头 fileName）")
    void listDirProducesTable() {
        SshCommandDispatcher dispatcher = connected(new FakeSsh());
        List<CommandOutcome> outcomes = dispatcher.execute(ir("action", "sftp_listdir", "dir", "/tmp"));

        org.junit.jupiter.api.Assertions.assertEquals(1, outcomes.size());
        CommandOutcome.Table table = (CommandOutcome.Table) outcomes.get(0);
        org.junit.jupiter.api.Assertions.assertEquals(List.of("fileName"), table.headers());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(List.of("a.txt"), List.of("b.txt")), table.rows());
    }

    @Test
    @DisplayName("sftp 失败文案：mkdir 多一个空格；其余按动作名前缀")
    void sftpFailureTexts() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(fake);

        fake.failNextCallWith("Permission denied");
        org.junit.jupiter.api.Assertions.assertEquals("mkdir failed. OSError (Permission denied)",
                firstError(dispatcher.execute(ir("action", "sftp_mkdir", "dir", "/root/x", "dirMod", "755"))));

        fake.failNextCallWith("No such file");
        org.junit.jupiter.api.Assertions.assertEquals("remove failed. OSError(No such file)",
                firstError(dispatcher.execute(ir("action", "sftp_remove", "file", "/root/x"))));
    }

    @Test
    @DisplayName("sftp 参数按 IR 的键名与进制解析：chmod/dirMod 是八进制字符串")
    void sftpArgumentParsing() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(fake);

        dispatcher.execute(ir("action", "sftp_chmod", "fileName", "/root/f", "fileMod", "640"));
        dispatcher.execute(ir("action", "sftp_mkdir", "dir", "/root/d", "dirMod", "755"));
        dispatcher.execute(ir("action", "sftp_chown", "fileName", "/root/f", "uid", 0, "gid", 0));
        dispatcher.execute(ir("action", "sftp_put", "localFile", "a.txt", "remoteFile", "/root/a.txt"));
        dispatcher.execute(ir("action", "sftp_rename", "oldFile", "/root/a.txt", "newFile", "/root/b.txt"));
        dispatcher.execute(ir("action", "sftp_truncate", "file", "/root/a.txt", "fileSize", 12));

        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpChmod /root/f 640"),
                "640 必须按八进制解析，实际调用: " + fake.calls);
        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpMkdir /root/d 755"));
        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpChown /root/f 0 0"));
        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpPut a.txt -> /root/a.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpRename /root/a.txt -> /root/b.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(fake.calls.contains("sftpTruncate /root/a.txt 12"));
    }

    @Test
    @DisplayName("sftp 数值参数非法：收敛成一条错误输出，既不抛给引擎也不真的调远端")
    void sftpInvalidNumbers() {
        FakeSsh fake = new FakeSsh();
        SshCommandDispatcher dispatcher = connected(fake);

        // fileMod/dirMod 是八进制字符串：非数字或非八进制数字都在这里变成错误输出
        // （正常脚本走不到这条路：SqlIrVisitor.sftpNumber 已经在 IR 层把它们判成合法整数）
        String chmodError = firstError(dispatcher.execute(
                ir("action", "sftp_chmod", "fileName", "/root/f", "fileMod", "abc")));
        // 数值错误的文案由 UserNumber 统一给（与 _SET/_JOB/_SLEEP 同一种句式）
        org.junit.jupiter.api.Assertions.assertEquals(
                "Invalid SFTP file mode [abc]: expected an octal permission mode in 0..07777.", chmodError);

        // 负数/超范围不是合法权限位（八进制 0..07777）
        org.junit.jupiter.api.Assertions.assertEquals(
                "Invalid SFTP file mode [-1]: expected an octal permission mode in 0..07777.",
                firstError(dispatcher.execute(
                        ir("action", "sftp_chmod", "fileName", "/root/f", "fileMod", "-1"))));
        String truncateError = firstError(dispatcher.execute(
                ir("action", "sftp_truncate", "file", "/root/a.txt", "fileSize", "12x")));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Invalid SFTP file size [12x]: expected a non-negative integer.", truncateError);

        org.junit.jupiter.api.Assertions.assertTrue(
                fake.calls.stream().noneMatch(call -> call.startsWith("sftpChmod")
                        || call.startsWith("sftpTruncate")),
                "数值非法时不应产生远端调用，实际: " + fake.calls);
    }

    @Test
    @DisplayName("未实现的动作 → 明确的错误（不静默忽略）")
    void unsupportedAction() {
        SshCommandDispatcher dispatcher = connected(new FakeSsh());
        List<CommandOutcome> outcomes = dispatcher.execute(ir("action", "sftp_nosuch"));
        org.junit.jupiter.api.Assertions.assertEquals("Unsupported ssh action [sftp_nosuch].", firstError(outcomes));
    }

    private static SshCommandDispatcher connected(FakeSsh fake) {
        SshCommandDispatcher dispatcher = new SshCommandDispatcher(new SshSessionRegistry(), () -> fake);
        dispatcher.execute(ir("action", "connect", "host", "h", "user", "u", "password", "p"));
        return dispatcher;
    }

    private static void assertTexts(List<String> expected, List<CommandOutcome> outcomes) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, statusTexts(outcomes));
    }
}
