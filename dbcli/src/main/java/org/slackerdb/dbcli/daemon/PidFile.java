package org.slackerdb.dbcli.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * pidfile：一个只存"进程号"的小文件，用于回答"后台那份还在不在"。
 *
 * <h2>谁写、谁读、谁删</h2>
 * <ul>
 *   <li><b>写</b>：真正在跑脚本的那个进程写自己（{@code start --pidfile} 的进程，也就是后台副本本身），
 *       而不是拉起它的父进程 —— 父进程在 Windows 上拿到的是中间 shell 的 pid，报出去只会误导
 *       （这一点与 {@link org.slackerdb.common.utils.AppDaemon#startInBackground} 的取舍一致）；</li>
 *   <li><b>读</b>：{@code status}/{@code stop} 只读文件，不猜；</li>
 *   <li><b>删</b>：<b>前台运行</b>结束时由本进程自己删（见 {@code Cli} 的 finally）；<b>后台副本</b>
 *       跑完<b>留着不删</b>——父进程正是靠读这个文件报出副本的 pid，而一份跑得很快的脚本会在
 *       父进程两次轮询（50ms）之间"写完又删掉"，父进程于是误报"pidfile 没写出来"
 *       （见 {@code Cli} 的 {@code --daemon-copy}）。留下的旧 pid 由 {@link #isAlive(long)}
 *       回答死活，下一轮启动时父进程也会先把旧文件删掉。</li>
 * </ul>
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
     * 写入 pid（覆盖写；父目录不存在时先建）。行尾固定 {@code \n}，便于跨平台读回。
     *
     * @throws IOException 目录建不出来或文件写不进去
     */
    public void write(long pid) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, pid + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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

    /** 删掉 pidfile（幂等；删不掉也不算失败——它只是个提示文件）。 */
    public void deleteQuietly() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 删不掉无法补救；调用方已经拿到结论（在跑/没在跑），不该因此报错
        }
    }

    /** 这个 pid 现在是否活着（{@code ProcessHandle} 是 JDK 9+ 的标准做法，不需要发信号）。 */
    public static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
