import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台启动验收用的夹具：把自己的参数（先一行"自己是从哪个 jar 启动的"，然后是
 * {@code main(String[])} 收到的每一个参数）按行写回文件，然后立即退出。
 *
 * <p><b>为什么不能只靠断言命令行数组</b>：{@code cmd /c start} 会重新拼装命令行、POSIX 下要过一层
 * shell，这两种情况都可能把带空格的取值拆开或吃掉引号——"看起来对、跑起来错"。只有让真正被 fork
 * 出来的 JVM 把它收到的 {@code args} 写回来，才能确认参数逐字到达。</p>
 *
 * <p><b>为什么输出路径走系统属性</b>：这样 {@code args} 里就只有待验证的参数本身，
 * 期望值不需要为夹具的实现细节留位置。</p>
 *
 * <p>刻意放在默认包（无 package）：{@link org.slackerdb.common.utils.AppDaemonTest} 会把这份
 * class 文件就地打进一个临时 jar，再用 {@code java -jar} 启动它。</p>
 */
public final class DaemonArgsFixture {

    /** 输出文件路径的系统属性名。 */
    public static final String ARGS_FILE_PROPERTY = "daemon.args.file";

    private DaemonArgsFixture() {
    }

    public static void main(String[] args) throws Exception {
        String target = System.getProperty(ARGS_FILE_PROPERTY);
        if (target == null || target.isEmpty()) {
            System.exit(2);
        }

        List<String> lines = new ArrayList<>();

        // 自己是从哪个 jar 启动的（验证 -jar 的值有没有被引号/空格破坏）
        String self = DaemonArgsFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        lines.add(URLDecoder.decode(self, StandardCharsets.UTF_8));

        lines.addAll(List.of(args));

        Path file = Path.of(target);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }

        // 先写临时文件、再原子改名：这样"目标文件存在"就等于"内容已经完整"。
        // 不能直接写目标文件——Files.write 会先创建/截断再写内容，读的一方（测试）
        // 正好落在这两步之间就会读到**空文件**（Windows 上实测红过一次：reported=[]）。
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            // 极少数文件系统不支持原子改名：退化成覆盖式改名（此时读的一方靠测试侧的内容等待兜底）
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }

        // 立即退出：参数已经写完，测试机不该留下后台进程
        System.exit(0);
    }
}
