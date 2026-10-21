package org.slackerdb.dbcli.compare;

import java.util.List;

/**
 * 一次 {@code _COMPARE} 的结果（纯数据，便于测试与多种输出格式复用）。
 *
 * @param referencePath 参考文件名（IR 的 {@code referenceFile}）
 * @param targetPath    目标文件名（IR 的 {@code targetFile}）
 * @param operations    编辑操作序列（已按归一化后的行号）
 * @param referenceLines 参考侧参与比对的行数（归一化后）
 * @param targetLines   目标侧参与比对的行数（归一化后）
 * @param skippedReference 参考侧被 skip 规则丢掉的行数
 * @param skippedTarget    目标侧被 skip 规则丢掉的行数
 * @param referenceFileLines 参考文件的**原始行**（mask 规则已应用、未做 skip/igblank 过滤），下标 = 行号 - 1
 * @param targetFileLines    目标文件的**原始行**（同上）。报告器据此取"工作侧文本"与定位被忽略的行，
 *                           这样**不需要在出报告时再读一遍文件**（比对与报告用同一份数据）
 */
public record CompareResult(
        String referencePath,
        String targetPath,
        List<DiffResult.Operation> operations,
        int referenceLines,
        int targetLines,
        int skippedReference,
        int skippedTarget,
        List<String> referenceFileLines,
        List<String> targetFileLines) {

    /** 取参考文件第 {@code number} 行（1 基）；越界返回 null。 */
    public String referenceFileLine(int number) {
        return lineAt(referenceFileLines, number);
    }

    /** 取目标文件第 {@code number} 行（1 基）；越界返回 null。 */
    public String targetFileLine(int number) {
        return lineAt(targetFileLines, number);
    }

    private static String lineAt(List<String> lines, int number) {
        if (number < 1 || number > lines.size()) {
            return null;
        }
        return lines.get(number - 1);
    }

    public int equalCount() {
        int total = 0;
        for (DiffResult.Operation operation : operations) {
            if (operation.kind() == DiffResult.Kind.EQUAL) {
                total++;
            }
        }
        return total;
    }

    public int deletedCount() {
        int total = 0;
        for (DiffResult.Operation operation : operations) {
            if (operation.kind() == DiffResult.Kind.DELETE) {
                total++;
            }
        }
        return total;
    }

    public int insertedCount() {
        int total = 0;
        for (DiffResult.Operation operation : operations) {
            if (operation.kind() == DiffResult.Kind.INSERT) {
                total++;
            }
        }
        return total;
    }

    /** "不同"的行数 = 删除 + 插入。 */
    public int differentCount() {
        return deletedCount() + insertedCount();
    }

    public boolean identical() {
        return differentCount() == 0;
    }
}
