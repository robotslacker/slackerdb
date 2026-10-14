package org.slackerdb.dbserver.sql;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.CommandComplete;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.NoticeResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.dbserver.server.DBSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PG 事务控制语句的执行（{@code START TRANSACTION} / {@code BEGIN} / {@code COMMIT} / {@code ROLLBACK} /
 * {@code ABORT} / {@code END} / {@code SET TRANSACTION}）。
 *
 * <p><b>修的是什么</b>：{@code postgres_fdw} 用 {@code START TRANSACTION ISOLATION LEVEL REPEATABLE READ}
 * 开远端事务，而 DuckDB 不认这条语法，SlackDB 历史上把它改写成空串 —— 客户端收到 {@code BEGIN}
 * 成功应答，DuckDB 却<b>什么都没开</b>，于是远端 {@code ROLLBACK} 一个字节都回滚不了
 * （静默的语义错误，比报错危险得多）。现在改为真正执行 {@code BEGIN TRANSACTION}，
 * 并显式驱动会话事务状态。</p>
 *
 * <p><b>语义细节</b>：</p>
 * <ul>
 *   <li>已在事务中再发 {@code BEGIN/START}：按 PG 只是 WARNING（"there is already a transaction in progress"），
 *       这里回 NOTICE 且<b>不</b>再执行 BEGIN（否则 DuckDB 报 "cannot start a transaction within a transaction"）；</li>
 *   <li>失败事务里 {@code COMMIT}：PG 的语义等价于回滚 —— 这里执行 {@code ROLLBACK}，命令标签仍是 {@code COMMIT}；</li>
 *   <li>失败事务里 {@code BEGIN} / {@code SET TRANSACTION}：{@code 25P02}；</li>
 *   <li>{@code SET TRANSACTION} 在事务块之外：{@code 25P01}（PG 语义）；</li>
 *   <li>事务结束一律走 {@link DBSession#endTransaction()}，它同时销毁非 {@code WITH HOLD} 游标；</li>
 *   <li>子事务（{@code SAVEPOINT} / {@code RELEASE SAVEPOINT} / {@code ROLLBACK TO SAVEPOINT}）：
 *       DuckDB 没有子事务 → 事务块内回 {@code 0A000}、块外回 {@code 25P01}。
 *       关键是 {@code ROLLBACK TO SAVEPOINT sN} <b>不能</b>被当成普通 {@code ROLLBACK}
 *       （那会静默地把整个事务回滚掉）。</li>
 * </ul>
 */
public final class TransactionHandler {

    private TransactionHandler() {
    }

    /** 处理结果（供调用方登记审计）。{@code sqlState} 非空表示出错。 */
    public record TransactionResult(String commandTag, String sqlState, String errorMessage) {
        public boolean isError() {
            return sqlState != null;
        }
    }

    /**
     * 处理一条事务控制语句（应答写进 {@code out}，含可选的 NOTICE）。
     *
     * @return {@code null} = 不是事务控制语句（调用方继续原有流程）
     */
    public static TransactionResult handle(DBInstance dbInstance, ChannelHandlerContext ctx, Object request,
                                          ByteArrayOutputStream out, DBSession session, String sql,
                                          boolean withReadyForQuery) throws IOException {
        TransactionSql.TransactionStatement statement = TransactionSql.parse(sql);
        if (statement == null) {
            return null;
        }

        boolean failedTransaction = session.getTransactionState() == DBSession.TransactionState.FAILED;
        if (failedTransaction && (statement.kind() == TransactionSql.Kind.BEGIN
                || statement.kind() == TransactionSql.Kind.SET_TRANSACTION
                || statement.kind() == TransactionSql.Kind.SAVEPOINT)) {
            // PG 语义：aborted 事务块里除 COMMIT/ROLLBACK 之外一律 25P02
            return error(dbInstance, session, ctx, request, out, withReadyForQuery, "25P02",
                    "current transaction is aborted, commands ignored until end of transaction block");
        }

        // 子事务控制（SAVEPOINT / RELEASE SAVEPOINT / ROLLBACK TO SAVEPOINT）：
        // DuckDB 没有子事务，明确报错而不是让它退化成别的语义。
        // 特别注意 `ROLLBACK TO SAVEPOINT sN` 绝不能被当成普通 ROLLBACK（那会回滚整个事务）。
        if (statement.kind() == TransactionSql.Kind.SAVEPOINT) {
            if (!session.inTransaction()) {
                return error(dbInstance, session, ctx, request, out, withReadyForQuery, "25P01",
                        "SAVEPOINT can only be used in transaction blocks");
            }
            // 事务块内：DuckDB 没有子事务，这是明确的不支持（不是语法错误、更不是静默降级）
            return error(dbInstance, session, ctx, request, out, withReadyForQuery, "0A000",
                    "SAVEPOINT / RELEASE SAVEPOINT / ROLLBACK TO SAVEPOINT are not supported by "
                            + "SlackerDB: DuckDB has no subtransactions. "
                            + "End the transaction with COMMIT or ROLLBACK instead.");
        }

        String commandTag;
        String notice = null;
        try {
            switch (statement.kind()) {
                case BEGIN -> {
                    if (session.inTransaction()) {
                        // PG 在这里只发 WARNING 并保持当前事务；DuckDB 会直接报错，所以不执行
                        notice = "there is already a transaction in progress";
                    } else {
                        execute(session, statement.executeSql());
                        session.beginTransaction();
                        notice = statement.notice();
                    }
                    commandTag = "BEGIN";
                }
                case COMMIT -> {
                    if (!session.inTransaction()) {
                        // PG 语义：没有事务在进行时 COMMIT 只发 WARNING、不做任何事。
                        // 这里也不能真去执行 DuckDB 的 COMMIT —— DuckDB 在没有活动事务时会报错。
                        notice = "there is no transaction in progress";
                    } else {
                        // 失败事务里的 COMMIT 在 PG 里等价于回滚
                        execute(session, failedTransaction ? "ROLLBACK" : "COMMIT");
                        session.endTransaction();
                        notice = statement.notice();
                    }
                    commandTag = "COMMIT";
                }
                case ROLLBACK -> {
                    if (!session.inTransaction()) {
                        notice = "there is no transaction in progress";
                    } else {
                        execute(session, "ROLLBACK");
                        session.endTransaction();
                        notice = statement.notice();
                    }
                    commandTag = "ROLLBACK";
                }
                case SET_TRANSACTION -> {
                    if (!session.inTransaction()) {
                        return error(dbInstance, session, ctx, request, out, withReadyForQuery, "25P01",
                                "SET TRANSACTION can only be used in transaction blocks");
                    }
                    commandTag = "SET";
                    notice = statement.notice();
                }
                default -> {
                    return null;
                }
            }
        } catch (SQLException e) {
            return error(dbInstance, session, ctx, request, out, withReadyForQuery,
                    SqlStateMapper.fromException(e), e.getMessage());
        }

        if (notice != null) {
            NoticeResponse noticeResponse = new NoticeResponse(dbInstance);
            noticeResponse.setNotice(notice);
            noticeResponse.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, NoticeResponse.class.getSimpleName(), out, dbInstance.logger);
        }

        CommandComplete commandComplete = new CommandComplete(dbInstance);
        commandComplete.setCommandResult(commandTag);
        commandComplete.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, dbInstance.logger);

        if (withReadyForQuery) {
            ReadyForQuery readyForQuery = new ReadyForQuery(dbInstance);
            readyForQuery.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);
        }

        return new TransactionResult(commandTag, null, null);
    }

    private static void execute(DBSession session, String sql) throws SQLException {
        try (Statement handle = session.dbConnection.createStatement()) {
            handle.execute(sql);
        }
    }

    /** 回一个错误（并按协议路径决定是否补 ReadyForQuery）。 */
    private static TransactionResult error(DBInstance dbInstance, DBSession session, ChannelHandlerContext ctx, Object request,
                                           ByteArrayOutputStream out, boolean withReadyForQuery,
                                           String sqlState, String message) throws IOException {
        // 先把事务标记为失败，再写应答：ReadyForQuery 的状态字节必须反映"事务块已 aborted"（'E'）。
        // 若等调用方事后标记，客户端拿到的会是 'T'，与 PG 语义不符。
        session.markTransactionFailed();

        ErrorResponse errorResponse = new ErrorResponse(dbInstance);
        errorResponse.setErrorFile("TransactionHandler");
        errorResponse.setErrorResponse(sqlState, message);
        errorResponse.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, dbInstance.logger);

        if (withReadyForQuery) {
            ReadyForQuery readyForQuery = new ReadyForQuery(dbInstance);
            readyForQuery.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);
        }
        return new TransactionResult(null, sqlState, message);
    }
}
