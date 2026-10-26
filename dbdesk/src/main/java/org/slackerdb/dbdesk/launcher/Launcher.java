package org.slackerdb.dbdesk.launcher;

import org.slackerdb.dbdesk.DbDeskApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DbDesk 启动器：只启动 DbDesk 自己的本机 Web UI，不启动、不探测、不管理 dbserver。
 *
 * <p>DbDesk 是管理工具，连哪个 dbserver 由顶栏 Connect 对话框决定；
 * 服务端的启停由用户自己负责。启动完成后只打印访问地址，不自动打开浏览器。</p>
 *
 * <p><b>参数</b>：</p>
 * <pre>
 *   --port &lt;n&gt;      控制台端口（默认 8090，等价于 --server.port）
 *   --datadir &lt;dir&gt; 本地上传暂存目录（默认 ~/.slackerdb/dbdesk-data）
 *   --help          显示帮助
 * </pre>
 *
 * <p>其余参数原样交给 Spring Boot（例如 {@code --logging.level.root=DEBUG}）。</p>
 */
public final class Launcher {

    private static final int DEFAULT_CONSOLE_PORT = 8090;

    private final Options options = new Options();

    public static void main(String[] args) {
        new Launcher().run(args);
    }

    private void run(String[] args) {
        List<String> springArgs = options.parse(args);
        if (options.help) {
            System.out.println(Options.USAGE);
            return;
        }

        springArgs.add("--server.port=" + options.consolePort);
        System.out.printf("[launcher] DbDesk 已启动，请手动访问 http://127.0.0.1:%d/%n", options.consolePort);

        new SpringApplicationBuilder(DbDeskApplication.class)
                .run(springArgs.toArray(String[]::new));
    }

    /** 命令行参数（解析结果）。 */
    static final class Options {
        static final String USAGE = """
                用法：java -jar slackerdb-dbdesk-<version>.jar [选项] [--spring 参数…]

                  --port <n>      控制台端口（默认 8090）
                  --datadir <dir> 本地上传暂存目录（默认 ~/.slackerdb/dbdesk-data）
                  --help          显示本帮助

                DbDesk 只启动自己的 Web UI；dbserver 的启停由用户自己负责。
                """;

        int consolePort = DEFAULT_CONSOLE_PORT;
        boolean help;

        /** 返回要转交给 Spring Boot 的参数。 */
        List<String> parse(String[] args) {
            List<String> springArgs = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--port" -> consolePort = Integer.parseInt(args[++i]);
                    case "--datadir" -> System.setProperty("dbdesk.datadir", args[++i]);
                    case "--help", "-h" -> help = true;
                    default -> {
                        if (arg.startsWith("--datadir=")) {
                            System.setProperty("dbdesk.datadir", arg.substring("--datadir=".length()));
                        } else {
                            springArgs.add(arg);
                        }
                    }
                }
            }
            return springArgs;
        }
    }

    // 仅供测试/排查：把参数原样描述出来（不解析），便于断言"未知参数会转交 Spring"
    static List<String> describe(String[] args) {
        return Arrays.asList(args);
    }
}
