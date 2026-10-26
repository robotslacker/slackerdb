package org.slackerdb.dbcli.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

/**
 * SSH/SFTP 客户端：基于 JSch 实现。
 *
 * <h2>动作与方法的对应关系</h2>
 * <table>
 *   <tr><td>{@code _SSH connect …}</td><td>{@link #connect(SshConnectOptions)}</td></tr>
 *   <tr><td>{@code _SSH execute …}</td><td>{@link #execute(String)}</td></tr>
 *   <tr><td>{@code _SSH disconnect}</td><td>{@link #close()}</td></tr>
 *   <tr><td>{@code _SSH sftp_*}</td><td>{@link #sftpChmod} / {@link #sftpMkdir} / {@link #sftpGet} …</td></tr>
 *   <tr><td>{@code _SSH save/restore}</td><td>{@link SshSessionRegistry}（会话保存在注册表里）</td></tr>
 * </table>
 *
 * <h2>两条实现约定</h2>
 * <ol>
 *   <li><b>stderr 必须单独收集</b>：JSch 的 {@code setErrStream} 会在内部线程里把 stderr 抽走，
 *       这样读 stdout 不会与 stderr 抢缓冲区导致死锁（exec 通道只有一个窗口）。</li>
 *   <li><b>同一个连接上 execute 与 sftp 可交替使用</b>：SFTP 通道按需打开、常驻复用。</li>
 * </ol>
 */
public final class SshClient implements SshOperations {

    private final JSch jsch = new JSch();

    /** 建立 TCP 连接与打开通道的超时（不是命令本身的时限）。 */
    private final int connectTimeoutMillis;

    /**
     * {@code _SSH EXECUTE} 的单条命令时限（毫秒）：{@link SshOperations#NO_COMMAND_TIMEOUT} 表示不限。
     *
     * <p>默认不限：远端命令可以跑多久由使用者决定；需要上限时用
     * {@code _SSH SET TIMEOUT = <秒>}。字段是 volatile，因为设置可能来自控制台线程而读在作业线程。</p>
     */
    private volatile long commandTimeoutMillis = SshOperations.NO_COMMAND_TIMEOUT;

    /**
     * 连接与 SFTP 通道：{@code volatile} + 幂等 {@code close()}。
     *
     * <p>作业被中止时，**另一条线程**会调 {@code close()} 来打断阻塞中的远端调用
     * （见 {@code ScriptJobExecutor} 注册的取消回调）。因此这里的字段必须跨线程可见，
     * 且 {@code close()} 与用法之间要有明确的"已关闭"判据——否则关线程读到旧值会"没关成"，
     * 用线程还可能在 {@code requireSession()} 与 {@code openChannel()} 之间拿到 null 而 NPE。</p>
     */
    private volatile Session session;
    private volatile ChannelSftp sftp;

    /** 已经关闭（关闭后不再允许新建通道；{@code close()} 幂等）。 */
    private volatile boolean closed;

    public SshClient() {
        this(30_000);
    }

    /**
     * @param connectTimeoutMillis 建立连接/打开 SFTP 通道的超时（毫秒）；命令本身的时限默认不限
     */
    public SshClient(int connectTimeoutMillis) {
        this.connectTimeoutMillis = Math.max(connectTimeoutMillis, 1);
    }

    @Override
    public void setCommandTimeout(long timeoutMillis) {
        this.commandTimeoutMillis = Math.max(timeoutMillis, SshOperations.NO_COMMAND_TIMEOUT);
    }

    /** 当前命令时限（毫秒；0 = 不限），供测试与排错。 */
    public long commandTimeoutMillis() {
        return commandTimeoutMillis;
    }

    @Override
    public synchronized void connect(SshConnectOptions options) throws JSchException {
        // 同一个 client 二次 connect：先把上一条连接收掉，否则它再没有任何名字指向，句柄泄漏到进程结束
        closeQuietly();
        closed = false;
        if (options.keyFile() != null && !options.keyFile().isBlank()) {
            jsch.addIdentity(options.keyFile());
        }
        Session opened = jsch.getSession(options.user(), options.host(), options.port());
        if (options.password() != null) {
            opened.setPassword(options.password());
        }
        // 首次连接不做 known_hosts 校验（自动接受主机密钥），
        // 否则测试机换机器指纹就会连不上（这是刻意的取舍，文档里已注明不做凭据/主机脱敏与管理）
        opened.setConfig("StrictHostKeyChecking", "no");
        opened.connect(connectTimeoutMillis);
        this.session = opened;
    }

    @Override
    public boolean isConnected() {
        Session current = session;
        return !closed && current != null && current.isConnected();
    }

    /** 输出一律按 UTF-8 解码（项目只支持 UTF-8）。 */
    private static Charset charset() {
        return StandardCharsets.UTF_8;
    }

    /**
     * 执行一条远程命令，分别取回 stdout/stderr 与退出码。
     *
     * <p>读输出是"分片 + 截止时间"的循环，不是一次阻塞读：命令结束（远端关闭输出）就返回，
     * 线程被中断时立刻收手；设置了 {@code _SSH SET TIMEOUT = <秒>} 时到点断开通道，返回已读到的
     * 部分输出并标记 {@link SshCommandResult#timedOut()}。<b>默认没有时限</b>（不限）。</p>
     */
    @Override
    public SshCommandResult execute(String command) throws JSchException, IOException {
        Session current = requireSession();
        ChannelExec channel = (ChannelExec) current.openChannel("exec");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setErrStream(stderr);
        try {
            channel.connect(connectTimeoutMillis);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            boolean timedOut = readStdout(channel, stdout);
            if (timedOut) {
                String message = "SSH command timed out after " + seconds() + " seconds.";
                return new SshCommandResult(command, stdout.toString(charset()),
                        message, SshCommandResult.TIMEOUT_EXIT_STATUS, true);
            }
            awaitExitStatus(channel);
            int exitStatus = channel.getExitStatus();
            return new SshCommandResult(command, stdout.toString(charset()),
                    new String(stderr.toByteArray(), charset()), exitStatus);
        } finally {
            channel.disconnect();
        }
    }

    /**
     * 读 stdout，直到远端关闭输出或到超时。
     *
     * <p>为什么用 {@code available()} 轮询而不是 {@code readAllBytes()}：后者只在流结束时返回，
     * 对"永不结束"的远端命令（{@code tail -f}）就是永久阻塞，且不可被中断。分片读还能在
     * 作业被中止（线程 interrupt）时立刻收手。</p>
     *
     * @return 是否因为超时结束（{@code false} 表示读到了输出结束）
     */
    private boolean readStdout(ChannelExec channel, ByteArrayOutputStream stdout)
            throws IOException {
        long deadline = deadlineNanos();
        byte[] buffer = new byte[8192];
        try (InputStream in = channel.getInputStream()) {
            while (true) {
                int available = in.available();
                if (available > 0) {
                    int read = in.read(buffer, 0, Math.min(available, buffer.length));
                    if (read < 0) {
                        return false;
                    }
                    stdout.write(buffer, 0, read);
                    continue;
                }
                if (channel.isClosed()) {
                    return false;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("SSH command interrupted.");
                }
                if (deadline != NO_DEADLINE && System.nanoTime() >= deadline) {
                    return true;
                }
                sleepSlice(10L);
            }
        }
    }

    /** 等远端把退出码发回来（{@code getExitStatus} 在通道关闭前不可靠）；同样受超时与中断约束。 */
    private void awaitExitStatus(ChannelExec channel) throws IOException {
        long deadline = deadlineNanos();
        while (!channel.isClosed() && (deadline == NO_DEADLINE || System.nanoTime() < deadline)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException(
                        "SSH command interrupted while waiting for the exit status.");
            }
            sleepSlice(20L);
        }
    }

    /** 没有时限时的哨兵值（{@code System.nanoTime()} 不会接近它）。 */
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    /** 命令时限的截止时刻；{@code NO_DEADLINE} 表示不限（默认）。 */
    private long deadlineNanos() {
        long timeout = commandTimeoutMillis;
        if (timeout <= SshOperations.NO_COMMAND_TIMEOUT) {
            return NO_DEADLINE;
        }
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    }

    /** 超时秒数（文案用；不足 1 秒按 1 秒说）。 */
    private long seconds() {
        return Math.max(1L, commandTimeoutMillis / 1000L);
    }

    /** 可被中断的短睡：被打断时恢复中断位并转成可读 IO 异常，由调用方收尾（不在循环里忙等）。 */
    private static void sleepSlice(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SSH command interrupted.");
        }
    }

    // ------------------------------------------------------------------ SFTP

    /** 按需打开并复用 SFTP 通道。 */
    public ChannelSftp sftp() throws JSchException {
        Session current = requireSession();
        ChannelSftp existing = sftp;
        if (existing == null || !existing.isConnected()) {
            existing = (ChannelSftp) current.openChannel("sftp");
            existing.connect(connectTimeoutMillis);
            sftp = existing;
        }
        return existing;
    }

    @Override
    public void sftpChmod(String path, int permissions) throws JSchException, SftpException {
        sftp().chmod(permissions, path);
    }

    @Override
    public void sftpChdir(String path) throws JSchException, SftpException {
        sftp().cd(path);
    }

    @Override
    public String sftpCwd() throws JSchException, SftpException {
        return sftp().pwd();
    }

    /**
     * 建目录并设置权限。
     *
     * <p>JSch 的 {@code mkdir(String)} 不接受 mode，所以这里建完再 {@code chmod}
     * —— 效果等价，权限参数不会丢。</p>
     */
    @Override
    public void sftpMkdir(String path, int permissions) throws JSchException, SftpException {
        sftp().mkdir(path);
        sftp().chmod(permissions, path);
    }

    /**
     * 改属主/属组。
     *
     * <p>JSch 只有分开的 {@code chown(uid, path)} 与 {@code chgrp(gid, path)}，
     * 这里连续调两次，效果等价。</p>
     */
    @Override
    public void sftpChown(String path, int uid, int gid) throws JSchException, SftpException {
        sftp().chown(uid, path);
        sftp().chgrp(gid, path);
    }

    @Override
    public void sftpGet(String remotePath, Path localFile) throws JSchException, SftpException, IOException {
        Path parent = localFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (java.io.OutputStream out = Files.newOutputStream(localFile)) {
            sftp().get(remotePath, out);
        }
    }

    @Override
    public void sftpPut(Path localFile, String remotePath) throws JSchException, SftpException, IOException {
        try (InputStream in = Files.newInputStream(localFile)) {
            sftp().put(in, remotePath);
        }
    }

    @Override
    public void sftpRemove(String path) throws JSchException, SftpException {
        sftp().rm(path);
    }

    @Override
    public void sftpRename(String from, String to) throws JSchException, SftpException {
        sftp().rename(from, to);
    }

    /** 列目录：返回名字（跳过 {@code .} 与 {@code ..}），顺序按服务器返回顺序。 */
    @Override
    @SuppressWarnings("unchecked")
    public List<String> sftpListDir(String path) throws JSchException, SftpException {
        Vector<ChannelSftp.LsEntry> entries = sftp().ls(path);
        List<String> names = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : entries) {
            if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                continue;
            }
            names.add(entry.getFilename());
        }
        return names;
    }

    /** 读取远端文件的全部内容（测试与 {@code sftp_get} 的小文件场景用）。 */
    public String readRemoteText(String remotePath) throws JSchException, SftpException, IOException {
        try (InputStream in = sftp().get(remotePath)) {
            return new String(in.readAllBytes(), charset());
        }
    }

    /**
     * 截断远端文件到指定长度。
     *
     * <p><b>已知限制</b>：JSch 0.2.18 的 {@code ChannelSftp} <b>没有</b> {@code truncate}
     * （SFTP 的 SETSTAT 扩展未暴露）。这里用远端的 {@code truncate -s} 命令实现（coreutils 自带）；
     * 若目标机没有该命令，会拿到它的 stderr，分派器据此报
     * {@code truncate failed. OSError(...)}。若将来必须纯 SFTP 实现，需要直接发 SSH_FXP_SETSTAT
     * （JSch 未暴露），届时再评估。</p>
     */
    @Override
    public void sftpTruncate(String path, long size) throws JSchException, SftpException, IOException {
        SshCommandResult result = execute("truncate -s " + size + " " + shellQuote(path));
        if (!result.success()) {
            throw new SftpException(0, result.errorText().isBlank() ? "truncate failed" : result.errorText().strip());
        }
    }

    /** 单引号包裹并转义，避免路径里的空格/引号破坏命令。 */
    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 关闭连接（幂等，可从**另一条线程**调用）：先断 SFTP 再断 session。
     *
     * <p>作业中止路径靠它让"阻塞在远端命令上的读"立刻失败——{@code readAllBytes()} 不响应
     * interrupt，只能由 socket 断开唤醒。因此这里不做任何"等对方配合"的动作。</p>
     */
    @Override
    public synchronized void close() {
        closed = true;
        closeQuietly();
    }

    /** 关闭并清空两个字段（不置 {@code closed}：{@link #connect} 复用它来换代）。 */
    private void closeQuietly() {
        ChannelSftp currentSftp = sftp;
        sftp = null;
        if (currentSftp != null) {
            try {
                currentSftp.disconnect();
            } catch (RuntimeException ignored) {
                // 断开失败无法补救：session.disconnect() 会一并收掉通道
            }
        }
        Session currentSession = session;
        session = null;
        if (currentSession != null) {
            currentSession.disconnect();
        }
    }

    /** 当前可用的 session；已关闭或未连接时给可读错误（而不是 NPE）。 */
    private Session requireSession() throws JSchException {
        Session current = session;
        if (closed || current == null || !current.isConnected()) {
            throw new JSchException("SSH 会话尚未建立，请先 _SSH connect");
        }
        return current;
    }
}
