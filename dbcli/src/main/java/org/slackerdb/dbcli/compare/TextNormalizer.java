package org.slackerdb.dbcli.compare;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分与"空行处理"（**只做这两件事**）。
 *
 * <p>归一化（trim/case/mask）**不在这里**：展示文本必须保持原样（{@code .dif} 里
 * {@code ChinA} 的大小写被保留），比较时才按选项处理 —— 见 {@link LineEquality}。</p>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** 把文件内容切成行（不保留换行符；CRLF 与单独的 {@code \r} 都按换行处理）。 */
    public static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        // 末尾没有换行时，最后一段也算一行；以换行结尾则不补空行
        if (start < normalized.length()) {
            lines.add(normalized.substring(start));
        }
        return lines;
    }
}
