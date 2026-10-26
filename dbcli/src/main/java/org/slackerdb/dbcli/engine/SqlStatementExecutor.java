package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.assertion.ResultModel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 一段 SQL 文本的**唯一**执行/输出路径：IR 解析出来的语句与"原样送库"的原始文本都走这里。
 *
 * <p>成功 → 表格 + 状态行（{@code _SET FEEDBACK OFF} 时只有表格）；失败 → 执行层给出的那一行错误进
 * stdout 并计一次错误（未连接时就是 {@code Not connected.}）。一条语句可能返回多个结果
 * （{@code CALL}/{@code DO}），逐个输出，最后那个成为 {@code _ASSERT} 看到的"最近结果"。</p>
 *
 * <p>从 {@link ScriptRunner} 抽出来的原因：这里只关心"执行层给了什么、怎么显示"，
 * 与"语句怎么被切出来、命令怎么分派"无关；混在一起时改显示规则要跨过 200 行推进逻辑。</p>
 */
final class SqlStatementExecutor {

    /**
     * {@code COPY … FROM STDIN} / {@code COPY … TO STDOUT}：数据流要占用 COPY 协议通道，
     * 本客户端没有这条通道，因此执行前就拒绝（否则服务端会等数据，客户端卡在 {@code execute()}）。
     */
    private static final Pattern COPY_STREAM =
            Pattern.compile("(?is)\\bcopy\\b.*?\\b(from\\s+stdin|to\\s+stdout)\\b");

    private final ScriptContext context;
    private final ErrorReporter errors;
    private final StatementDiagnostics diagnostics;

    SqlStatementExecutor(ScriptContext context, ErrorReporter errors, StatementDiagnostics diagnostics) {
        this.context = context;
        this.errors = errors;
        this.diagnostics = diagnostics;
    }

    /** 执行并输出一段 SQL 文本。 */
    void execute(String statement) {
        if (COPY_STREAM.matcher(statement).find()) {
            errors.report("COPY ... FROM STDIN / TO STDOUT is not supported: the data stream needs the "
                    + "COPY protocol; use COPY ... FROM '<file>' with the file on the server side.");
            return;
        }
        long startedAt = System.nanoTime();
        List<ResultModel> models = context.sqlExecutor().executeAll(statement);
        long elapsedNanos = System.nanoTime() - startedAt;
        ResultModel last = null;
        for (ResultModel model : models) {
            last = model;
            if (model.errorCode() != 0) {
                context.setLastResult(model);
                errors.report(context.sqlExecutor().lastError());
                diagnostics.reportElapsed(elapsedNanos);
                return;
            }
            if (!model.headers().isEmpty()) {
                context.output().printTable(model.headers(), model.columnTypes(), model.rows());
            }
            if (context.feedbackOn()) {
                context.output().printStatus(model.status());
            }
        }
        if (last != null) {
            context.setLastResult(last);
        }
        diagnostics.reportElapsed(elapsedNanos);
    }
}
