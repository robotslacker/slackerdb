package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.dbproxy.instance.InstanceConf;
import org.slackerdb.dbproxy.instance.InstancePaths;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 实例目录里可被 {@code create} 覆盖的路径（pid、log）必须按 conf 解析。
 *
 * <p>代理创建实例时自己写的是 {@code pid=instance.pid}、{@code log=logs/server.log}，
 * 但用户可以通过 key/value 换成任意名字（甚至绝对路径）。生命周期与日志接口都只能以
 * {@code instance.conf} 为准，不能依赖默认文件名。</p>
 */
class InstancePathsTest {

    @TempDir
    Path root;

    private InstancePaths paths() {
        return new InstancePaths("sales", root.resolve("sales"));
    }

    private static InstanceConf conf(String... keyValues) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            values.put(keyValues[index], keyValues[index + 1]);
        }
        // 用 render 生成，Windows 路径里的反斜杠才会按 Properties 规则转义
        return InstanceConf.parse(InstanceConf.render(values));
    }

    @Test
    @DisplayName("pid: the configured pid file is used instead of the default instance.pid")
    void pidFollowsConfiguredFile() throws Exception {
        InstancePaths paths = paths();

        assertEquals(paths.dir().resolve("instance.pid"),
                paths.pid(conf("pid", "instance.pid")));
        assertEquals(paths.dir().resolve("run").resolve("sales.pid"),
                paths.pid(conf("pid", "run/sales.pid")));

        Path absolute = root.resolve("shared").resolve("sales.pid").toAbsolutePath();
        assertEquals(absolute, paths.pid(conf("pid", absolute.toString())));

        // 未配置时沿用目录约定
        assertEquals(paths.dir().resolve("instance.pid"), paths.pid(conf()));
    }

    @Test
    @DisplayName("log: the file(s) configured by log= are used, CONSOLE is not a file")
    void logFollowsConfiguredFile() throws Exception {
        InstancePaths paths = paths();

        assertEquals(List.of(paths.dir().resolve("logs").resolve("server.log")),
                paths.logFiles(conf("log", "logs/server.log")));
        assertEquals(List.of(paths.dir().resolve("mylogs").resolve("custom.log")),
                paths.logFiles(conf("log", "mylogs/custom.log")));

        Path absolute = root.resolve("var").resolve("log").resolve("sales.log").toAbsolutePath();
        assertEquals(List.of(absolute), paths.logFiles(conf("log", absolute.toString())));

        // 逗号分隔的多个目标：CONSOLE 跳过，文件目标都保留
        assertEquals(List.of(paths.dir().resolve("logs").resolve("a.log"),
                        paths.dir().resolve("logs").resolve("b.log")),
                paths.logFiles(conf("log", "CONSOLE, logs/a.log, logs/b.log")));

        // 只写 CONSOLE 时没有 dbserver 日志文件（输出进了代理的 console.log）
        assertEquals(List.of(), paths.logFiles(conf("log", "CONSOLE")));

        // logfile= 是 dbserver 支持的别名
        assertEquals(List.of(paths.dir().resolve("x.log")),
                paths.logFiles(conf("logfile", "x.log")));

        // ${data} 的替换规则与 dbserver 一致
        assertEquals(List.of(paths.dir().resolve("logs").resolve("sales.log")),
                paths.logFiles(conf("data", "sales", "log", "logs/${data}.log")));

        // 未配置时退回代理写下的默认位置
        assertEquals(List.of(paths.dir().resolve("logs").resolve("server.log")),
                paths.logFiles(conf()));
    }
}
