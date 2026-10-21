package org.slackerdb.dbcli.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 报告产物（{@code .suc}/{@code .dif}/{@code .html}）的收尾：**删掉与本次结论矛盾的那一份**。
 *
 * <p>"产物恒定"的含义是"磁盘上这组文件永远只描述这一次的结论"。{@code _compare} 与
 * {@code --ref} 以前各写一份删除逻辑（{@code --ref} 那份甚至没有），于是"上次失败留下的
 * {@code .dif}"会在本次一致之后继续存在，按产物判断的 CI 读到矛盾结论。</p>
 *
 * <p>删除是尽力而为：删不掉（只读目录/文件被占用）不改变本次结论。</p>
 */
public final class Artifacts {

    private Artifacts() {
    }

    /** 删除一个不再代表本次结论的产物；不存在或删不掉都静默。 */
    public static void deleteStale(Path artifact) {
        if (artifact == null) {
            return;
        }
        try {
            Files.deleteIfExists(artifact);
        } catch (IOException ignored) {
            // 见类注释
        }
    }
}
