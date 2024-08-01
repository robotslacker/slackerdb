package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DescribeRequest  extends PostgresRequest {
    @Override
    public void decode(byte[] data) {
        super.decode(data);
    }

    @Override
    public void process(ChannelHandlerContext ctx, Object request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Field> fields = new ArrayList<>();

        try {
            // 获取之前记录PreparedStatement
            PreparedStatement preparedStatement = (PreparedStatement) ctx.channel().attr(AttributeKey.valueOf("PreparedStatement")).get();
            if (preparedStatement == null) {
                return;
            }

            // 获取返回的结构信息
            ResultSetMetaData resultSetMetaData = preparedStatement.getMetaData();
            for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                String columnTypeName = resultSetMetaData.getColumnTypeName(i);
                Field field = new Field();
                field.name = resultSetMetaData.getColumnName(i).getBytes(StandardCharsets.UTF_8);
                field.objectIdOfTable = 0;
                field.attributeNumberOfColumn = 0;
                field.dataTypeId = PostgresTypeOids.getTypeOid(columnTypeName);
                field.dataTypeSize = (short) 2147483647;
                field.dataTypeModifier = -1;
                switch (columnTypeName) {
                    case "VARCHAR":
                    case "TIMESTAMP":
                        field.formatCode = 0;
                        break;
                    default:
                        if (columnTypeName.startsWith("DECIMAL"))
                        {
                            // DECIMAL 应该是二进制格式，但是目前分析二进制格式的结果总是不对
                            // 所有这里用字符串进行返回
                            field.formatCode = 0;
                        }
                        else
                        {
                            field.formatCode = 1;
                        }
                }
                fields.add(field);
            }

            RowDescription rowDescription = new RowDescription();
            rowDescription.setFields(fields);
            rowDescription.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, RowDescription.class.getSimpleName(), out);
        }
        catch (SQLException e) {
            // 生成一个错误消息
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setErrorResponse(String.valueOf(e.getErrorCode()), e.getMessage());
            errorResponse.process(ctx, request, out);

            // 发送并刷新返回消息
            PostgresMessage.writeAndFlush(ctx, ErrorResponse.class.getSimpleName(), out);
        }
    }
}
