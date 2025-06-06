/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.jdbc;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.PGResultSetMetaData;
import org.slackerdb.jdbc.core.BaseConnection;
import org.slackerdb.jdbc.core.Field;
import org.slackerdb.jdbc.core.ServerVersion;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.Gettable;
import org.slackerdb.jdbc.util.GettableHashMap;
import org.slackerdb.jdbc.util.JdbcBlackHole;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

public class PgResultSetMetaData implements ResultSetMetaData, PGResultSetMetaData {
  protected final BaseConnection connection;
  protected final Field[] fields;

  private boolean fieldInfoFetched;

  /**
   * Initialise for a result with a tuple set and a field descriptor set
   *
   * @param connection the connection to retrieve metadata
   * @param fields the array of field descriptors
   */
  public PgResultSetMetaData(BaseConnection connection, Field[] fields) {
    this.connection = connection;
    this.fields = fields;
    this.fieldInfoFetched = false;
  }

  @Override
  public int getColumnCount() throws SQLException {
    return fields.length;
  }

  /**
   * {@inheritDoc}
   *
   * <p>It is believed that PostgreSQL does not support this feature.
   *
   * @param column the first column is 1, the second is 2...
   * @return true if so
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    fetchFieldMetaData();
    Field field = getField(column);
    FieldMetadata metadata = field.getMetadata();
    return metadata != null && metadata.autoIncrement;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Does a column's case matter? ASSUMPTION: Any field that is not obviously case insensitive is
   * assumed to be case sensitive
   *
   * @param column the first column is 1, the second is 2...
   * @return true if so
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    Field field = getField(column);
    return connection.getTypeInfo().isCaseSensitive(field.getOID());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Can the column be used in a WHERE clause? Basically for this, I split the functions into two
   * types: recognised types (which are always useable), and OTHER types (which may or may not be
   * useable). The OTHER types, for now, I will assume they are useable. We should really query the
   * catalog to see if they are useable.
   *
   * @param column the first column is 1, the second is 2...
   * @return true if they can be used in a WHERE clause
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isSearchable(int column) throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Is the column a cash value? 6.1 introduced the cash/money type, which haven't been incorporated
   * as of 970414, so I just check the type name for both 'cash' and 'money'
   *
   * @param column the first column is 1, the second is 2...
   * @return true if its a cash column
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isCurrency(int column) throws SQLException {
    String typeName = getPGType(column);

    return "cash".equals(typeName) || "money".equals(typeName);
  }

  @Override
  public int isNullable(int column) throws SQLException {
    fetchFieldMetaData();
    Field field = getField(column);
    FieldMetadata metadata = field.getMetadata();
    return metadata == null ? ResultSetMetaData.columnNullable : metadata.nullable;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Is the column a signed number? In PostgreSQL, all numbers are signed, so this is trivial.
   * However, strings are not signed (duh!)
   *
   * @param column the first column is 1, the second is 2...
   * @return true if so
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isSigned(int column) throws SQLException {
    Field field = getField(column);
    return connection.getTypeInfo().isSigned(field.getOID());
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    Field field = getField(column);
    return connection.getTypeInfo().getDisplaySize(field.getOID(), field.getMod());
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    Field field = getField(column);
    return field.getColumnLabel();
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    return getColumnLabel(column);
  }

  @Override
  public String getBaseColumnName(int column) throws SQLException {
    Field field = getField(column);
    if (field.getTableOid() == 0) {
      return "";
    }
    fetchFieldMetaData();
    FieldMetadata metadata = field.getMetadata();
    return metadata == null ? "" : metadata.columnName;
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    return "";
  }

  private boolean populateFieldsWithMetadata(Gettable<FieldMetadata.Key, FieldMetadata> metadata) {
    boolean allOk = true;
    for (Field field : fields) {
      if (field.getMetadata() != null) {
        // No need to update metadata
        continue;
      }

      final FieldMetadata fieldMetadata =
          metadata.get(new FieldMetadata.Key(field.getTableOid(), field.getPositionInTable()));
      if (fieldMetadata == null) {
        allOk = false;
      } else {
        field.setMetadata(fieldMetadata);
      }
    }
    fieldInfoFetched |= allOk;
    return allOk;
  }

  private void fetchFieldMetaData() throws SQLException {
    if (fieldInfoFetched) {
      return;
    }

    if (populateFieldsWithMetadata(connection.getFieldMetadataCache())) {
      return;
    }

    StringBuilder sql = new StringBuilder(
        "SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, "
            + "a.attnotnull OR (t.typtype = 'd' AND t.typnotnull), ");

    if ( connection.haveMinimumServerVersion(ServerVersion.v10)) {
      sql.append("a.attidentity != '' OR pg_catalog.pg_get_expr(d.adbin, d.adrelid) LIKE '%nextval(%' ");
    } else {
      sql.append("pg_catalog.pg_get_expr(d.adbin, d.adrelid) LIKE '%nextval(%' ");
    }
    sql.append("FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON (c.relnamespace = n.oid) "
            + "JOIN pg_catalog.pg_attribute a ON (c.oid = a.attrelid) "
            + "JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid) "
            + "LEFT JOIN pg_catalog.pg_attrdef d ON (d.adrelid = a.attrelid AND d.adnum = a.attnum) "
            + "JOIN (");

    // 7.4 servers don't support row IN operations (a,b) IN ((c,d),(e,f))
    // so we've got to fake that with a JOIN here.
    //
    boolean hasSourceInfo = false;
    Set<String> oidSet = new HashSet<>();
    for (Field field : fields) {
      if (field.getMetadata() != null) {
        continue;
      }

      if (hasSourceInfo) {
        sql.append(" UNION ALL ");
      }

      sql.append("SELECT ");
      sql.append(field.getTableOid());
      if (!hasSourceInfo) {
        sql.append(" AS oid ");
      }
      sql.append(", ");
      sql.append(field.getPositionInTable());
      if (!hasSourceInfo) {
        sql.append(" AS attnum");
      }

      if (!hasSourceInfo) {
        hasSourceInfo = true;
      }
      oidSet.add(String.valueOf(field.getTableOid()));
    }
    sql.append(") vals ON (c.oid = vals.oid AND a.attnum = vals.attnum) ");

    if (!oidSet.isEmpty()) {
      sql.append("where c.oid in (").append(String.join(",", oidSet)).append(")");
    }

    if (!hasSourceInfo) {
      fieldInfoFetched = true;
      return;
    }

    Statement stmt = connection.createStatement();
    ResultSet rs = null;
    GettableHashMap<FieldMetadata.Key, FieldMetadata> md = new GettableHashMap<>();
    try {
      rs = stmt.executeQuery(sql.toString());
      while (rs.next()) {
        int table = (int) rs.getLong(1);
        int column = (int) rs.getLong(2);
        String columnName = castNonNull(rs.getString(3));
        String tableName = castNonNull(rs.getString(4));
        String schemaName = castNonNull(rs.getString(5));
        int nullable =
            rs.getBoolean(6) ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable;
        boolean autoIncrement = rs.getBoolean(7);
        FieldMetadata fieldMetadata =
            new FieldMetadata(columnName, tableName, schemaName, nullable, autoIncrement);
        FieldMetadata.Key key = new FieldMetadata.Key(table, column);
        md.put(key, fieldMetadata);
      }
    } finally {
      JdbcBlackHole.close(rs);
      JdbcBlackHole.close(stmt);
    }
    populateFieldsWithMetadata(md);
    connection.getFieldMetadataCache().putAll(md);
  }

  @Override
  public String getBaseSchemaName(int column) throws SQLException {
    fetchFieldMetaData();
    Field field = getField(column);
    FieldMetadata metadata = field.getMetadata();
    return metadata == null ? "" : metadata.schemaName;
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    Field field = getField(column);
    return connection.getTypeInfo().getPrecision(field.getOID(), field.getMod());
  }

  @Override
  public int getScale(int column) throws SQLException {
    Field field = getField(column);
    return connection.getTypeInfo().getScale(field.getOID(), field.getMod());
  }

  @Override
  public String getTableName(int column) throws SQLException {
    return getBaseTableName(column);
  }

  @Override
  public String getBaseTableName(int column) throws SQLException {
    fetchFieldMetaData();
    Field field = getField(column);
    FieldMetadata metadata = field.getMetadata();
    return metadata == null ? "" : metadata.tableName;
  }

  /**
   * {@inheritDoc}
   *
   * <p>As with getSchemaName(), we can say that if
   * getTableName() returns n/a, then we can too - otherwise, we need to work on it.
   *
   * @param column the first column is 1, the second is 2...
   * @return catalog name, or "" if not applicable
   * @exception SQLException if a database access error occurs
   */
  @Override
  public String getCatalogName(int column) throws SQLException {
    return "";
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    return getSQLType(column);
  }

  @Override
  public int getFormat(int column) throws SQLException {
    return getField(column).getFormat();
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    String type = getPGType(column);
    if (isAutoIncrement(column)) {
      if ("int4".equals(type)) {
        return "serial";
      } else if ("int8".equals(type)) {
        return "bigserial";
      } else if ("int2".equals(type) && connection.haveMinimumServerVersion(ServerVersion.v9_2)) {
        return "smallserial";
      }
    }

    return castNonNull(type);
  }

  /**
   * {@inheritDoc}
   *
   * <p>In reality, we would have to check the GRANT/REVOKE
   * stuff for this to be effective, and I haven't really looked into that yet, so this will get
   * re-visited.
   *
   * @param column the first column is 1, the second is 2, etc.*
   * @return true if so*
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isReadOnly(int column) throws SQLException {
    return false;
  }

  /**
   * {@inheritDoc}
   *
   * <p>In reality have to check
   * the GRANT/REVOKE stuff, which I haven't worked with as yet. However, if it isn't ReadOnly, then
   * it is obviously writable.
   *
   * @param column the first column is 1, the second is 2, etc.
   * @return true if so
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isWritable(int column) throws SQLException {
    return !isReadOnly(column);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Hmmm...this is a bad one, since the two
   * preceding functions have not been really defined. I cannot tell is the short answer. I thus
   * return isWritable() just to give us an idea.
   *
   * @param column the first column is 1, the second is 2, etc..
   * @return true if so
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    return false;
  }

  // ********************************************************
  // END OF PUBLIC INTERFACE
  // ********************************************************

  /**
   * For several routines in this package, we need to convert a columnIndex into a Field[]
   * descriptor. Rather than do the same code several times, here it is.
   *
   * @param columnIndex the first column is 1, the second is 2...
   * @return the Field description
   * @exception SQLException if a database access error occurs
   */
  protected Field getField(int columnIndex) throws SQLException {
    if (columnIndex < 1 || columnIndex > fields.length) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.",
              columnIndex, fields.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    return fields[columnIndex - 1];
  }

  protected @Nullable String getPGType(int columnIndex) throws SQLException {
    return connection.getTypeInfo().getPGType(getField(columnIndex).getOID());
  }

  protected int getSQLType(int columnIndex) throws SQLException {
    return connection.getTypeInfo().getSQLType(getField(columnIndex).getOID());
  }

  // ** JDBC 2 Extensions **

  // This can hook into our PG_Object mechanism

  @Override
  public String getColumnClassName(int column) throws SQLException {
    Field field = getField(column);
    String result = connection.getTypeInfo().getJavaClass(field.getOID());

    if (result != null) {
      return result;
    }

    int sqlType = getSQLType(column);
    if (sqlType == Types.ARRAY) {
      return "java.sql.Array";
    } else {
      String type = getPGType(column);
      if ("unknown".equals(type)) {
        return "java.lang.String";
      }
      return "java.lang.Object";
    }
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }
}
