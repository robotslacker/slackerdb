package org.slackerdb.dbsyncer.model;

import java.util.ArrayList;
import java.util.List;

/** 源表结构（由 JDBC 元数据/系统表读取）。 */
public final class SourceTable {

    public final String schema;
    public final String table;
    public final String dbType;
    public final List<SourceColumn> columns = new ArrayList<>();
    public final List<String> primaryKey = new ArrayList<>();

    public SourceTable(String schema, String table, String dbType) {
        this.schema = schema;
        this.table = table;
        this.dbType = dbType;
    }

    public SourceColumn column(String name) {
        for (SourceColumn column : columns) {
            if (column.name.equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    public String qualifiedName() {
        return schema + "." + table;
    }

    public static final class SourceColumn {
        public final String name;
        public final String typeName;
        public final int jdbcType;
        public final int size;
        public final int decimalDigits;
        public boolean generated;
        public String typeDefinition;
        public String characterSet;
        /** PostgreSQL pg_type OID；运行期由 Relation 消息补齐。 */
        public int pgTypeOid;

        public SourceColumn(String name, String typeName, int jdbcType, int size, int decimalDigits) {
            this.name = name;
            this.typeName = typeName == null ? "" : typeName;
            this.jdbcType = jdbcType;
            this.size = size;
            this.decimalDigits = decimalDigits;
        }

    }
}

