package org.slackerdb.protocol;

import io.netty.channel.ChannelHandlerContext;
import org.slackerdb.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class RowDescription extends PostgresMessage {
    //  RowDescription (B)
    //    Byte1('T')
    //      Identifies the message as a row description.
    //    Int32
    //      Length of message contents in bytes, including self.
    //    Int16
    //      Specifies the number of fields in a row (can be zero).
    //      Then, for each field, there is the following:
    //
    //      String
    //        The field name.
    //      Int32
    //        If the field can be identified as a column of a specific table, the object ID of the table; otherwise zero.
    //      Int16
    //        If the field can be identified as a column of a specific table, the attribute number of the column; otherwise zero.
    //      Int32
    //        The object ID of the field's data type.
    //      Int16
    //        The data type size (see pg_type.typlen). Note that negative values denote variable-width types.
    //      Int32
    //        The type modifier (see pg_attribute.atttypmod). The meaning of the modifier is type-specific.
    //      Int16
    //        The format code being used for the field. Currently, will be zero (text) or one (binary).
    //        In a RowDescription returned from the statement variant of Describe, the format code is not yet known and will always be zero.
    //
    //   在 PostgresSQL 中，pg_attribute 系统表存储了关于表中每一列的信息。pg_attribute 表的 atttypmod 字段描述了与特定列类型相关的附加类型修饰符。具体来说，atttypmod 是一个整型值，通常包含类型的附加信息，如长度或精度。这些修饰符取决于列的数据类型。
    //        pg_attribute.atttypmod 的作用
    //        atttypmod 的值和列的类型密切相关，不同的数据类型有不同的附加修饰符。例如：
    //
    //        字符类型（如 varchar 和 char）:
    //           atttypmod 存储字符类型的最大长度。例如，对于 varchar(255) 类型，
    //           atttypmod 存储的值是 255 加上一个额外的字节（通常为 4）。所以 atttypmod 的值为 259。
    //        数值类型（如 numeric 和 decimal）:
    //           atttypmod 存储数值类型的精度和小数点后的位数。例如，对于 numeric(10, 2) 类型，atttypmod 存储的值是将精度和小数点后的位数编码成一个整数。
    //        日期类型（如 timestamp 和 time）:
    //           atttypmod 存储日期类型的精度。例如，对于 timestamp(3) 类型，atttypmod 存储的是 3。
    //           对于其他数据类型，atttypmod 的值可能会有所不同。对于没有附加修饰符的类型，atttypmod 的值通常为 -1。

    private List<Field>  fields;
    public void setFields(List<Field> fields)
    {
        this.fields = fields;
    }
    @Override
    public void process(ChannelHandlerContext ctx, Object request, ByteArrayOutputStream out) throws IOException {
        out.write((byte)'T');

        int fieldSizeSum = 0;
        for (Field field : fields)
        {
            fieldSizeSum = fieldSizeSum + field.getFieldSize();
        }
        out.write(Utils.int32ToBytes(4 + 2 + fieldSizeSum));
        out.write(Utils.int16ToBytes((short)fields.size()));
        for (Field field : fields)
        {
            out.write(field.name);
            out.write((byte)0);
            out.write(Utils.int32ToBytes(field.objectIdOfTable));
            out.write(Utils.int16ToBytes(field.attributeNumberOfColumn));
            out.write(Utils.int32ToBytes(field.dataTypeId));
            out.write(Utils.int16ToBytes(field.dataTypeSize));
            out.write(Utils.int32ToBytes(field.dataTypeModifier));
            out.write(Utils.int16ToBytes(field.formatCode));
        }
    }
}
