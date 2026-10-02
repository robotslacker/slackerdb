package org.slackerdb.dbserver.message;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slackerdb.dbserver.entity.SQLHistoryRecord;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.MissingResourceException;

public abstract class PostgresRequest {
    // 数据库实例
    protected final DBInstance dbInstance;

    protected byte[] requestContent;

    public void decode(byte[] data)
    {
        requestContent = data;
    }

    public byte[] encode()
    {
        return requestContent;
    }

    public abstract void process(ChannelHandlerContext ctx, Object request) throws IOException;

    public  PostgresRequest(DBInstance pDbInstance)
    {
        this.dbInstance = pDbInstance;
    }

    /** 当前请求所属的数据库实例（供协议辅助类使用）。 */
    public DBInstance getDbInstance()
    {
        return this.dbInstance;
    }

    public String getMessage(String code, Object... contents) {
        StringBuilder content;
        String pattern;
        try {
            pattern = this.dbInstance.resourceBundle.getString(code);
            content = new StringBuilder(MessageFormat.format(pattern, contents));
        } catch (MissingResourceException me)
        {
            content = new StringBuilder("MSG-" + code + ":");
            for (Object object : contents) {
                if (object != null) {
                    content.append(object).append("|");
                }
                else {
                    content.append("null|");
                }
            }
        }
        return content.toString();
    }

    public  int getCurrentSessionId(ChannelHandlerContext ctx)
    {
        AttributeKey<Integer> sessionKey = AttributeKey.valueOf("SessionId");
        if (ctx.channel().hasAttr(sessionKey))
        {
            Integer sessionId = ctx.channel().attr(sessionKey).get();
            return sessionId != null ? sessionId : 0;
        }
        else
        {
            return 0;
        }
    }

    // ------------------------------------------------------------------ SQL 审计历史

    /**
     * 登记一条"语句开始执行"的审计历史，返回它在历史表里的 ID（供执行结束时更新）。
     *
     * <p>记录内容取自会话上的 {@code executingSQL} / {@code executingSqlId}，
     * 因此调用方必须<b>先</b>把这两个字段设置成本次要执行的语句。</p>
     *
     * <p>放在基类里是因为它是"任何执行语句的入口"都要做的事：扩展协议走
     * {@code ExecuteRequest}，简单查询走 {@code QueryRequest}，COPY 走
     * {@code CopyProtocolHandler}。三条路径共用同一份实现，避免审计口径再次分叉。</p>
     *
     * @return 历史记录 ID；{@code 0} 表示未开启历史（{@code sqlHistory=OFF} 或只读模式），
     *         {@code -1} 表示"尚未登记"（调用方在自己的异常分支里用它跳过更新）
     */
    public long insertSqlHistory(ChannelHandlerContext ctx)
    {
        // 插入SQL执行历史
        long sqlHistoryId = 0;
        if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                this.dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
            sqlHistoryId = this.dbInstance.backendSqlHistoryId.incrementAndGet();
            SQLHistoryRecord sqlHistoryRecord =
                    new SQLHistoryRecord(
                            "INSERT",
                            sqlHistoryId,
                            ProcessHandle.current().pid(),
                            getCurrentSessionId(ctx),
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).clientAddress,
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSQL,
                            this.dbInstance.getSession(getCurrentSessionId(ctx)).executingSqlId.get(),
                            LocalDateTime.now(),
                            null,
                            0,
                            0,
                            null
                    );
            this.dbInstance.sqlHistoryList.offer(sqlHistoryRecord);
        }
        return sqlHistoryId;
    }

    /**
     * 用语句的最终结果（SQL 码 / 影响行数 / 错误信息）收尾一条审计历史。
     *
     * @param sqlHistoryId {@link #insertSqlHistory(ChannelHandlerContext)} 返回的 ID
     * @param sqlCode      错误码（成功传 0）
     * @param affectedRows 影响行数（查询为返回行数）
     * @param errorMsg     错误信息（成功传 {@code null}）
     */
    public void updateSqlHistory(long sqlHistoryId, int sqlCode, long affectedRows, String errorMsg)
    {
        // 更新SQL执行历史
        if (!this.dbInstance.serverConfiguration.getAccess_mode().equals("READ_ONLY") &&
                this.dbInstance.serverConfiguration.getSqlHistory().equalsIgnoreCase("ON")) {
            SQLHistoryRecord sqlHistoryRecord =
                    new SQLHistoryRecord(
                            "UPDATE",
                            sqlHistoryId,
                            0,
                            0,
                            null,
                            null,
                            0,
                            null,
                            LocalDateTime.now(),
                            sqlCode,
                            affectedRows,
                            errorMsg
                    );
            this.dbInstance.sqlHistoryList.offer(sqlHistoryRecord);
        }
    }
}
