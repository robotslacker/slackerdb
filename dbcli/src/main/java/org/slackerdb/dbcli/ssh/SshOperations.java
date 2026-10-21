package org.slackerdb.dbcli.ssh;

import java.nio.file.Path;
import java.util.List;

/**
 * SSH/SFTP 操作的抽象（{@link SshClient} 是真实实现，测试用假实现）。
 *
 * <p>为什么抽出接口：{@code _SSH} 的**动作分派与文案**（"SSH connected"、"&lt; Command finished with [0]"、
 * 各种 {@code OSError(...)} 文案）是纯逻辑，应该在**不连真实主机**的情况下也能被完整验证；
 * 真实连通性由 {@link SshClient} 的集成测试负责。两者分开后，CI 无需任何 SSH 主机。</p>
 */
public interface SshOperations extends AutoCloseable {

    /** 建立连接（认证失败等信息由异常带出）。 */
    void connect(SshConnectOptions options) throws Exception;

    boolean isConnected();

    /** 执行远程命令（stdout/stderr 分别收集，{@link SshCommandResult#combinedOutput()} 负责合并）。 */
    SshCommandResult execute(String command) throws Exception;

    String sftpCwd() throws Exception;

    void sftpChmod(String path, int permissions) throws Exception;

    void sftpChdir(String path) throws Exception;

    void sftpMkdir(String path, int permissions) throws Exception;

    void sftpChown(String path, int uid, int gid) throws Exception;

    void sftpGet(String remotePath, Path localFile) throws Exception;

    void sftpPut(Path localFile, String remotePath) throws Exception;

    void sftpRemove(String path) throws Exception;

    void sftpRename(String from, String to) throws Exception;

    List<String> sftpListDir(String path) throws Exception;

    void sftpTruncate(String path, long size) throws Exception;

    @Override
    void close();
}
