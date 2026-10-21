package org.slackerdb.dbcli.compare;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级差分的结果：一串编辑操作 + 统计数字。
 *
 * @param operations 编辑操作序列（按原始行序）
 */
public record DiffResult(List<Operation> operations) {

    /** 操作类型。 */
    public enum Kind {
        /** 两侧都有且相等。 */
        EQUAL,
        /** 只在参考侧（reference）有 —— 报告里通常记为"少了/不同"。 */
        DELETE,
        /** 只在目标侧（target）有。 */
        INSERT
    }

    /**
     * 一个编辑操作：{@code leftLine}/{@code rightLine} 是 1 基行号（不存在时为 -1）。
     *
     * @param kind      操作类型
     * @param leftLine  参考文件行号（1 基，-1 表示无）
     * @param rightLine 目标文件行号（1 基，-1 表示无）
     * @param text      行内容（EQUAL 时取参考侧文本；两侧内容在忽略大小写时可能不同）
     */
    public record Operation(Kind kind, int leftLine, int rightLine, String text) {
    }

    public int equalCount() {
        return count(Kind.EQUAL);
    }

    public int deletedCount() {
        return count(Kind.DELETE);
    }

    public int insertedCount() {
        return count(Kind.INSERT);
    }

    /** "不同"的行数：删除 + 插入（报告里 {@code '-'} 与 {@code '+'} 行数之和）。 */
    public int differentCount() {
        return deletedCount() + insertedCount();
    }

    public boolean identical() {
        return differentCount() == 0;
    }

    private int count(Kind kind) {
        int total = 0;
        for (Operation operation : operations) {
            if (operation.kind() == kind) {
                total++;
            }
        }
        return total;
    }

    /** 便于测试与调试：把操作序列压成 "{@code =a|-b|+c}" 形式。 */
    public String toCompactString() {
        StringBuilder builder = new StringBuilder();
        for (Operation operation : operations) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            switch (operation.kind()) {
                case EQUAL -> builder.append('=').append(operation.text());
                case DELETE -> builder.append('-').append(operation.text());
                case INSERT -> builder.append('+').append(operation.text());
            }
        }
        return builder.toString();
    }

    /** 只保留文本（测试用）。 */
    public List<String> texts() {
        List<String> texts = new ArrayList<>();
        for (Operation operation : operations) {
            texts.add(operation.text());
        }
        return texts;
    }
}
