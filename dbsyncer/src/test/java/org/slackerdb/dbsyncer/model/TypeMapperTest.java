package org.slackerdb.dbsyncer.model;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeMapperTest {

    @Test
    void mapsPostgresTypes() {
        assertEquals("BIGINT", TypeMapper.map("postgres", column("int8", Types.BIGINT, 0, 0)));
        assertEquals("DECIMAL(10,2)", TypeMapper.map("postgres", column("numeric", Types.NUMERIC, 10, 2)));
        assertEquals("TIMESTAMP WITH TIME ZONE", TypeMapper.map("postgres", column("timestamptz", Types.TIMESTAMP, 0, 0)));
        assertEquals("JSON", TypeMapper.map("postgres", column("jsonb", Types.OTHER, 0, 0)));
        assertEquals("BLOB", TypeMapper.map("postgres", column("bytea", Types.BINARY, 0, 0)));
        assertEquals("VARCHAR", TypeMapper.map("postgres", column("text", Types.VARCHAR, 0, 0)));
    }

    @Test
    void mapsMySqlTypes() {
        assertEquals("TINYINT", TypeMapper.map("mysql", column("tinyint", Types.TINYINT, 3, 0)));
        assertEquals("INTEGER", TypeMapper.map("mysql", column("mediumint", Types.INTEGER, 0, 0)));
        SourceTable.SourceColumn unsigned = column("bigint", Types.BIGINT, 0, 0);
        unsigned.typeDefinition = "bigint unsigned";
        assertEquals("DECIMAL(20,0)", TypeMapper.map("mysql", unsigned));
        assertEquals("JSON", TypeMapper.map("mysql", column("json", Types.LONGVARCHAR, 0, 0)));
        assertEquals("VARCHAR", TypeMapper.map("mysql", column("enum", Types.CHAR, 0, 0)));
    }

    private static SourceTable.SourceColumn column(String type, int jdbcType, int size, int digits) {
        return new SourceTable.SourceColumn("c", type, jdbcType, size, digits);
    }
}
