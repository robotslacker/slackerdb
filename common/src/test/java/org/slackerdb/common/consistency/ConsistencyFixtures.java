package org.slackerdb.common.consistency;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 契约 C-5 的一致性夹具读取器（test-jar）。
 *
 * <p>目的：{@code dbcli} 与控制台**不共享生产代码，但行为不许漂移**。
 * 两边各自的测试读同一份 TSV，谁改了渲染/切分/类型名的规则，谁的用例先红。</p>
 *
 * <p>格式：UTF-8 TSV，`#` 开头是注释；字段里的 `\n` / `\t` 写成转义（读取时还原）。</p>
 */
public final class ConsistencyFixtures {

    private ConsistencyFixtures() {
    }

    /** 读一份夹具，返回"按制表符切开并还原转义"的行列表。 */
    public static List<String[]> read(String name) throws IOException {
        String resource = "consistency/" + name;
        InputStream in = ConsistencyFixtures.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("consistency fixture not found on classpath: " + resource);
        }
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = unescape(columns[i]);
                }
                rows.add(columns);
            }
        }
        return rows;
    }

    private static String unescape(String value) {
        return value.replace("\\t", "\t").replace("\\r", "\r").replace("\\n", "\n");
    }
}
