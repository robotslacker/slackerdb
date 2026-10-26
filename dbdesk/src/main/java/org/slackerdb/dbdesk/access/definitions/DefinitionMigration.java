package org.slackerdb.dbdesk.access.definitions;

import java.util.List;
import java.util.Map;

/**
 * 本地定义 → 服务端的一次性迁移（M5-3）。
 *
 * <p>行为刻意做成**幂等**：目标端已存在同 id 的就不动（计入 {@code skipped}），
 * 所以可以反复执行、也可以在迁移中途失败后重跑。</p>
 *
 * <p>冲突（同名不同 id）计入 {@code failed} 并继续 —— 一份坏定义不该挡住其余 99 份。</p>
 */
public final class DefinitionMigration {

    /** 迁移结果。{@code failed} 里带 "kind/id: 原因"，便于日志定位。 */
    public record Summary(int copied, int skipped, List<String> failed) {
        public boolean ok() {
            return failed.isEmpty();
        }
    }

    private static final List<String> KINDS = List.of("datasets", "charts", "dashboards", "portals");

    private DefinitionMigration() {
    }

    public static Summary fileToServer(FileDefinitionBackend source, DefinitionBackend target) {
        int copied = 0;
        int skipped = 0;
        List<String> failed = new java.util.ArrayList<>();
        for (String kind : KINDS) {
            for (Map<String, Object> item : source.list(kind)) {
                String id = item.get("id") == null ? "" : String.valueOf(item.get("id"));
                try {
                    if (!id.isBlank() && target.find(kind, id).isPresent()) {
                        skipped++;
                        continue;
                    }
                    target.save(kind, item);
                    copied++;
                } catch (Exception e) {
                    failed.add(kind + "/" + id + ": " + e.getMessage());
                }
            }
        }
        return new Summary(copied, skipped, List.copyOf(failed));
    }
}
