package org.slackerdb.dbproxy.instance;

import java.util.List;

/**
 * 把后端列表渲染成终端表格（CLI 的 {@code list} 与 {@code status} 共用）。
 *
 * <p>只输出 ASCII，避免中文控制台把对齐打乱。{@code SOURCE} 列区分
 * {@code management}（可治理）与 {@code external}（只转发）。</p>
 */
public final class InstanceText {

    private InstanceText() {
    }

    public static String table(List<BackendView> backends) {
        if (backends == null || backends.isEmpty()) {
            return "  (no databases)\n";
        }
        String[][] rows = new String[backends.size() + 1][];
        rows[0] = new String[]{"ALIAS", "SOURCE", "NAME", "STATE", "PID", "PG", "MGMT", "UPTIME", "HEALTH"};
        for (int i = 0; i < backends.size(); i++) {
            BackendView item = backends.get(i);
            rows[i + 1] = new String[]{
                    item.alias(),
                    item.source().wireName(),
                    item.dataName() == null ? "" : item.dataName(),
                    item.state().name(),
                    item.pidText(),
                    item.pgPort() > 0 ? String.valueOf(item.pgPort()) : "-",
                    item.mgmtPort() > 0 ? String.valueOf(item.mgmtPort()) : "-",
                    item.uptimeText(),
                    item.healthText()
            };
        }

        int[] widths = new int[rows[0].length];
        for (String[] row : rows) {
            for (int column = 0; column < row.length; column++) {
                widths[column] = Math.max(widths[column], row[column].length());
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append("  ");
            for (int column = 0; column < row.length; column++) {
                sb.append(pad(row[column], widths[column]));
                if (column != row.length - 1) {
                    sb.append("  ");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }
}
