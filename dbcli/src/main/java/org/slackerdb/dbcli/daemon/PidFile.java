package org.slackerdb.dbcli.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * pidfile：一个只存"进程号"的小文件，用于回答"后台那份还在不在"。
 *
 * <h2>谁写、谁读、谁删</h2>
 * <ul>
 *   <li><b>写</b>：真正在跑脚本的那个进程写自己（带 {@code --daemon} 的后台副本本身），
 *       而不是拉起它的父进程 —— 父进程在 Windows 上拿到的是中间 shell 的 pid，报出去只会误导；</li>
 *   <li><b>读</b>：父进程在读到自己要的 pid 之后立即返回（见 {@code CliDaemon}）；</li>
 *   <li><b>删</b>：<b>前台运行</b>结束时由本进程自己删（见 {@code Cli} 的 finally）；<b>后台副本</b>
 *       跑完<b>留着不删</b>——父进程正是靠读这个文件报出副本的 pid，而一份跑得很快的脚本会在
 *       父进程两次轮询之间"写完又删掉"，父进程于是误报"pidfile 没写出来"。</li>
 * </ul>
 *
 * <h2>写入是原子的</h2>
 * <p>先写到同目录的临时文件，再整体替换：读方（父进程每 50ms 轮询一次）不会读到空文件或
 * 半个数字。这一步很重要——读到的"半个 pid"会被当成真的报给使用者。</p>
 *
 * <h2>已知边界</h2>
 * <p>pidfile 是操作系统的老办法，只能回答"这个 pid 现在还活着吗"，答不了"这个 pid 还是不是当初那个进程"
 * （pid 会被复用）。要更准需要进程启动时间或文件锁，超出本工具的范围；这里如实写进手册。</p>
 */
public final class PidFile {

    private final Path path;

    public PidFile(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** 规范化后的绝对路径（错误文案与状态行里用它，避免同一个文件出现两种写法）。 */
    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.isRegularFile(path);
    }

    /**
     * 写入 pid：先写同目录临时文件，再整体替换（读方永远看不到半个数字）。行尾固定 {@code \n}，
     * 便于跨平台读回。
     *
     * @throws IOException 目录建不出来或文件写不进去
     */
    public void write(long pid) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, pid + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // 少数文件系统不支持原子移动：退回普通替换（仍然是"整份内容就位"之后才出现）
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 写入当前进程的 pid（{@code start --pidfile} 走这条）。 */
    public void writeCurrent() throws IOException {
        write(ProcessHandle.current().pid());
    }

    /**
     * 读回 pid。
     *
     * @return pid；文件不存在 / 读不出来 / 内容里没有数字时返回 null（调用方据此区分
     *         "文件都没有"与"文件在但里面的 pid 不是活的"）
     */
    public Long read() {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 只认"整行就是一个十进制数"：半个数字、带别的内容都当读不出来
        if (!trimmed.matches("\\d{1,19}")) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    /**
     * 删掉 pidfile。
     *
     * @return 是否确定已经不存在（文件本来就不存在也算成功）
     */
    public boolean delete() {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException failed) {
            // 删不掉时以"文件是否还在"为准：调用方据此决定要不要继续（见 CliDaemon）
            return !Files.exists(path);
        }
    }

    /** 删掉 pidfile（幂等；删不掉也不算失败——它只是个提示文件）。 */
    public void deleteQuietly() {
        delete();
    }

    /** 这个 pid 现在是否活着（{@code ProcessHandle} 是 JDK 9+ 的标准做法，不需要发信号）。 */
    public static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
