package org.slackerdb.dbserver.sql;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.dbserver.entity.Column;
import org.slackerdb.dbserver.entity.Field;
import org.slackerdb.dbserver.message.PostgresMessage;
import org.slackerdb.dbserver.message.response.CommandComplete;
import org.slackerdb.dbserver.message.response.DataRow;
import org.slackerdb.dbserver.message.response.ErrorResponse;
import org.slackerdb.dbserver.message.response.NoticeResponse;
import org.slackerdb.dbserver.message.response.ReadyForQuery;
import org.slackerdb.dbserver.message.response.RowDescription;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 把 {@link SessionSettingSql.Takeover} 渲染成协议应答。
 *
 * <p>简单查询路径（{@code QueryRequest}）与扩展协议路径（{@code ExecuteRequest}）
 * 都要接管同一批语句，应答内容必须一致；差别只有一个：简单查询要在命令完成后补
 * {@code ReadyForQuery}，扩展协议路径不发（由客户端的 Sync 触发）。</p>
 */
public final class SessionSettingResponder {

    private SessionSettingResponder() {
    }

    /**
     * @param withReadyForQuery 简单查询路径传 {@code true}
     */
    public static void respond(DBInstance dbInstance, ChannelHandlerContext ctx, Object request,
                               ByteArrayOutputStream out, SessionSettingSql.Takeover takeover,
                               boolean withReadyForQuery) throws IOException {
        if (takeover.notice() != null) {
            NoticeResponse notice = new NoticeResponse(dbInstance);
            notice.setNotice(takeover.notice());
            notice.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, NoticeResponse.class.getSimpleName(), out, dbInstance.logger);
        }

        if (takeover.isError()) {
            ErrorResponse errorResponse = new ErrorResponse(dbInstance);
            errorResponse.setErrorFile("SessionSetting");
            errorResponse.setErrorResponse(takeover.errorSqlState(), takeover.errorMessage());
            errorResponse.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out, dbInstance.logger);
        } else {
            CommandComplete commandComplete = new CommandComplete(dbInstance);
            commandComplete.setCommandResult(takeover.commandTag());
            commandComplete.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, dbInstance.logger);
        }

        if (withReadyForQuery) {
            ReadyForQuery readyForQuery = new ReadyForQuery(dbInstance);
            readyForQuery.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);
        }
    }

    /**
     * {@code SHOW <name>} 的单列行描述（text）。
     *
     * <p>公开出来是为了让 {@code DescribeHandler} 与 Execute 使用<b>同一份</b>列元数据 ——
     * 合成的结果集同样必须满足"声明的格式与字节一致"这个不变量。</p>
     */
    public static Field showField(String name) {
        Field field = new Field();
        field.name = name;
        field.objectIdOfTable = 0;
        field.attributeNumberOfColumn = 0;
        field.dataTypeId = 25;          // text
        field.dataTypeSize = -1;        // 变长类型
        field.dataTypeModifier = -1;
        field.formatCode = 0;           // varchar 没有二进制编码器：恒文本
        return field;
    }

    /**
     * 渲染 {@code SHOW <name>} 的结果集（一行一列）。
     *
     * @param withRowDescription 简单查询路径要发 RowDescription；扩展协议路径不发
     *                           （客户端已经从 {@code Describe('P')} 拿到行描述，重复发是协议违规）
     * @param withReadyForQuery  简单查询路径传 {@code true}
     */
    public static void respondShow(DBInstance dbInstance, ChannelHandlerContext ctx, Object request,
                                   ByteArrayOutputStream out, String name, String value,
                                   boolean withRowDescription, boolean withReadyForQuery) throws IOException {
        if (withRowDescription) {
            RowDescription rowDescription = new RowDescription(dbInstance);
            rowDescription.setFields(List.of(showField(name)));
            rowDescription.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out, dbInstance.logger);
        }

        Column column = new Column();
        column.columnValue = value.getBytes(StandardCharsets.UTF_8);
        column.columnLength = column.columnValue.length;
        DataRow dataRow = new DataRow(dbInstance);
        dataRow.setColumns(List.of(column));
        dataRow.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, DataRow.class.getSimpleName(), out, dbInstance.logger);

        CommandComplete commandComplete = new CommandComplete(dbInstance);
        commandComplete.setCommandResult("SHOW");
        commandComplete.process(ctx, request, out);
        PostgresMessage.writeAndFlush(ctx, CommandComplete.class.getSimpleName(), out, dbInstance.logger);

        if (withReadyForQuery) {
            ReadyForQuery readyForQuery = new ReadyForQuery(dbInstance);
            readyForQuery.process(ctx, request, out);
            PostgresMessage.writeAndFlush(ctx, ReadyForQuery.class.getSimpleName(), out, dbInstance.logger);
        }
    }
}
