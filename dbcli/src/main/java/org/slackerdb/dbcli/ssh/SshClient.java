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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

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
    private final int timeoutMillis;

    private Session session;
    private ChannelSftp sftp;

    public SshClient() {
        this(30_000);
    }

    public SshClient(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void connect(SshConnectOptions options) throws JSchException {
        if (options.keyFile() != null && !options.keyFile().isBlank()) {
            jsch.addIdentity(options.keyFile());
        }
        session = jsch.getSession(options.user(), options.host(), options.port());
        if (options.password() != null) {
            session.setPassword(options.password());
        }
        // 首次连接不做 known_hosts 校验（自动接受主机密钥），
        // 否则测试机换机器指纹就会连不上（这是刻意的取舍，文档里已注明不做凭据/主机脱敏与管理）
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(timeoutMillis);
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /** 输出一律按 UTF-8 解码（项目只支持 UTF-8）。 */
    private static Charset charset() {
        return StandardCharsets.UTF_8;
    }

    /** 执行一条远程命令，分别取回 stdout/stderr 与退出码。 */
    @Override
    public SshCommandResult execute(String command) throws JSchException, IOException {
        requireSession();
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setErrStream(stderr);
        try {
            channel.connect(timeoutMillis);
            String stdout;
            try (InputStream in = channel.getInputStream()) {
                stdout = new String(in.readAllBytes(), charset());
            }
            // 等待远端把退出码发回来（getExitStatus 在通道关闭前不可靠）
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                sleepQuietly(20);
            }
            int exitStatus = channel.getExitStatus();
            return new SshCommandResult(command, stdout,
                    new String(stderr.toByteArray(), charset()), exitStatus);
        } finally {
            channel.disconnect();
        }
    }

    // ------------------------------------------------------------------ SFTP

    /** 按需打开并复用 SFTP 通道。 */
    public ChannelSftp sftp() throws JSchException {
        requireSession();
        if (sftp == null || !sftp.isConnected()) {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeoutMillis);
        }
        return sftp;
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

    @Override
    public void close() {
        if (sftp != null) {
            sftp.disconnect();
            sftp = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    private void requireSession() throws JSchException {
        if (!isConnected()) {
            throw new JSchException("SSH 会话尚未建立，请先 _SSH connect");
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
