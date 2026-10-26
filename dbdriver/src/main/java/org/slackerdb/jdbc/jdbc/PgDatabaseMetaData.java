/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.jdbc;

import static java.lang.System.lineSeparator;

import org.slackerdb.jdbc.Driver;
import org.slackerdb.jdbc.util.DriverInfo;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

enum DuckDBColumnType {
  BOOLEAN,
  TINYINT,
  SMALLINT,
  INTEGER,
  BIGINT,
  UTINYINT,
  USMALLINT,
  UINTEGER,
  UBIGINT,
  HUGEINT,
  UHUGEINT,
  FLOAT,
  DOUBLE,
  DECIMAL,
  VARCHAR,
  BLOB,
  TIME,
  // DuckDB 1.5 起的纳秒精度时间类型（duckdb-java 自 1.5.2 起同步到枚举里）
  TIME_NS,
  DATE,
  TIMESTAMP,
  TIMESTAMP_MS,
  TIMESTAMP_NS,
  TIMESTAMP_S,
  TIMESTAMP_WITH_TIME_ZONE,
  BIT,
  TIME_WITH_TIME_ZONE,
  INTERVAL,
  LIST,
  STRUCT,
  ENUM,
  UUID,
  JSON,
  MAP,
  ARRAY,
  UNKNOWN,
  UNION,
  // DuckDB 1.5 起新增的 VARIANT / GEOMETRY（duckdb-java 自 1.5.2 起同步到枚举里）
  VARIANT,
  GEOMETRY
}

public class PgDatabaseMetaData implements DatabaseMetaData {
    private static final int QUERY_SB_DEFAULT_CAPACITY = 512;
    private static final String TRAILING_COMMA = ", ";

    private static boolean appendEqualsQual(StringBuilder sb, String colName, String value) {
        // catalog - a catalog name; must match the catalog name as it is stored in
        // the database;
        // "" retrieves those without a catalog;
        // null means that the catalog name should not be used to narrow the search
        boolean hasParam = false;
        if (value != null) {
            sb.append("AND ");
            sb.append(colName);
            if (value.isEmpty())
            {
                sb.append(" IS NULL");
            } else
            {
                sb.append(" = ?");
                hasParam = true;
            }
            sb.append(lineSeparator());
        }
        return hasParam;
    }

    private static boolean appendLikeQual(StringBuilder sb, String colName, String pattern) {
        // schemaPattern - a schema name pattern; must match the schema name as it
        // is stored in the database;
        // "" retrieves those without a schema;
        // null means that the schema name should not be used to narrow the search
        boolean hasParam = false;
        if (pattern != null) {
            sb.append("AND ");
            sb.append(colName);
            if (pattern.isEmpty()) {
                sb.append(" IS NULL");
            } else {
                sb.append(" LIKE ? ");
                hasParam = true;
            }
            sb.append(lineSeparator());
        }
        return hasParam;
    }

    private static String nullPatternToWildcard(String pattern) {
        // tableNamePattern - a table name pattern; must match the table name as it
        // is stored in the database
        // columnNamePattern - a column name pattern; must match the table name as it
        // is stored in the database
        if (pattern == null) {
            // non-standard behavior.
            return "%";
        }
        return pattern;
    }

    public static int type_to_int(DuckDBColumnType type) {
      return switch (type) {
          case BOOLEAN -> Types.BOOLEAN;
          case TINYINT -> Types.TINYINT;
          // DuckDB 的无符号整型在 JDBC 里没有对应类型：统一取"能装下值域的上一档有符号类型"。
          // 取窄一档会直接丢精度（例如 UTINYINT 的 250 落到 TINYINT 就变负数）。
          case UTINYINT -> Types.SMALLINT;
          case SMALLINT -> Types.SMALLINT;
          case USMALLINT -> Types.INTEGER;
          case INTEGER -> Types.INTEGER;
          case UINTEGER -> Types.BIGINT;
          case BIGINT -> Types.BIGINT;
          case LIST, ARRAY -> Types.ARRAY;
          case FLOAT -> Types.FLOAT;
          case DOUBLE -> Types.DOUBLE;
          case DECIMAL -> Types.DECIMAL;
          case VARCHAR -> Types.VARCHAR;
          case TIME, TIME_NS -> Types.TIME;
          case DATE -> Types.DATE;
          case TIMESTAMP_S, TIMESTAMP_MS, TIMESTAMP, TIMESTAMP_NS -> Types.TIMESTAMP;
          case TIMESTAMP_WITH_TIME_ZONE -> Types.TIMESTAMP_WITH_TIMEZONE;
          case TIME_WITH_TIME_ZONE -> Types.TIME_WITH_TIMEZONE;
          case STRUCT -> Types.STRUCT;
          case BIT -> Types.BIT;
          case BLOB, GEOMETRY -> Types.BLOB;
          default -> Types.OTHER;
      };
    }

    static String dataMap;
    static {
        dataMap = makeCase(
            Arrays.stream(DuckDBColumnType.values())
                    .collect(Collectors.toMap(PgDatabaseMetaData::typeNameInDuckDB,
                            PgDatabaseMetaData::type_to_int)));
    }

    /**
     * 枚举名 → {@code duckdb_columns().data_type} 里的类型名。
     *
     * <p>只把 {@code *_WITH_TIME_ZONE} 家族换成带空格的形式（DuckDB 里确实叫
     * {@code TIMESTAMP WITH TIME ZONE}），其余类型名在 DuckDB 里保留下划线：
     * 曾经统一做 {@code replace("_", " ")}，于是 {@code TIME_NS} /
     * {@code TIMESTAMP_MS} / {@code TIMESTAMP_NS} / {@code TIMESTAMP_S} 的键变成了
     * "TIME NS" 之类，永远匹配不上，这些列一律落到兜底的 {@code Types.OTHER}。</p>
     */
    private static String typeNameInDuckDB(DuckDBColumnType type) {
        return type.name().replace("_WITH_TIME_ZONE", " WITH TIME ZONE");
    }

    private static <T> String makeCase(Map<String, T> values) {
        return values.entrySet()
              .stream()
              .map(ty -> {
                T value = ty.getValue();
              return String.format("WHEN '%s' THEN %s ", ty.getKey(),
                      value instanceof String ? String.format("'%s'", value) : value);
            })
            .collect(Collectors.joining());
    }

    /**
    * @param srcColumnName
    * @param destColumnName
    * @return
    */
    private static String makeDataMap(String srcColumnName, String destColumnName) {
        // 列表/数组列在 duckdb_columns() 里长成 "<元素类型>[]" / "<元素类型>[N]"，不是
        // LIST/ARRAY 字面量：先按后缀判成 Types.ARRAY，其余再走类型名映射。
        // 不做这一步的话数组列会落到兜底类型，客户端拿不到 Types.ARRAY。
        return String.format(
                "CASE WHEN %s LIKE '%%]' THEN %d "
                        + "ELSE CASE %s %s ELSE %d END END as %s",
                srcColumnName, Types.ARRAY, srcColumnName, dataMap, Types.OTHER, destColumnName);
    }

  public PgDatabaseMetaData(PgConnection conn) {
    this.connection = conn;
  }


  private @Nullable String keywords;

  protected final PgConnection connection; // The connection association

  @Override
  public boolean allProceduresAreCallable() throws SQLException {
    return true; // For now...
  }

  @Override
  public boolean allTablesAreSelectable() throws SQLException {
    return true; // For now...
  }

  @Override
  public String getURL() throws SQLException {
    return connection.getURL();
  }

  @Override
  public String getUserName() throws SQLException {
    return connection.getUserName();
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    return connection.isReadOnly();
  }

  // NULL 的排序位置。
  //
  // 这四个方法描述的是"服务端把 NULL 排在哪里"，JDBC 要求**恰好一个**为 true。
  // 服务端是 DuckDB，默认 `default_null_order = NULLS_LAST`，实测（1.5.6）：
  //     ORDER BY i ASC  -> 1 2 null      ORDER BY i DESC -> 2 1 null
  // 即 NULL **永远在最后**，与排序方向无关 => 正确的答案是 atEnd，而不是 high。
  //
  // 这里曾经从 pgjdbc 抄了 `high=true`，又把 `atStart` 写成 true（同一个问题两个 true，
  // 自相矛盾）。pgjdbc 的 high=true 是 PostgreSQL 的语义（PG 把 NULL 视为最大），
  // 在 DuckDB 上不成立。上游 duckdb-java 2.0 的 PR #924 把这一组改成动态读
  // `current_setting('default_null_order')`；我们不引入那次往返，直接固化"默认值"的答案，
  // 并在回归用例里用一条真实 ORDER BY 校验（见 JdbcDatabaseMetaDataTest）。

  @Override
  public boolean nullsAreSortedHigh() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtStart() throws SQLException {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtEnd() throws SQLException {
    return true;
  }

  /**
   * Retrieves the name of this database product. We hope that it is PostgreSQL, so we return that
   * explicitly.
   *
   * @return "PostgreSQL"
   */
  @Override
  public String getDatabaseProductName() throws SQLException {
    return "PostgreSQL";
  }

  @Override
  public String getDatabaseProductVersion() throws SQLException {
    return connection.getDBVersionNumber();
  }

  @Override
  public String getDriverName() {
    return DriverInfo.DRIVER_NAME;
  }

  @Override
  public String getDriverVersion() {
    return DriverInfo.DRIVER_VERSION;
  }

  @Override
  public int getDriverMajorVersion() {
    return DriverInfo.MAJOR_VERSION;
  }

  @Override
  public int getDriverMinorVersion() {
    return DriverInfo.MINOR_VERSION;
  }

  /**
   * Does the database store tables in a local file? No - it stores them in a file on the server.
   *
   * @return true if so
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean usesLocalFiles() throws SQLException {
    return false;
  }

  /**
   * Does the database use a file for each table? Well, not really, since it doesn't use local files.
   *
   * @return true if so
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean usesLocalFilePerTable() throws SQLException {
    return false;
  }

  /**
   * Does the database treat mixed case unquoted SQL identifiers as case sensitive and as a result
   * store them in mixed case? A JDBC-Compliant driver will always return false.
   *
   * @return true if so
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean supportsMixedCaseIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() throws SQLException {
    return true;
  }

  /**
   * Does the database treat mixed case quoted SQL identifiers as case sensitive and as a result
   * store them in mixed case? A JDBC compliant driver will always return true.
   *
   * @return true if so
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
    return false;
  }

  /**
   * What is the string used to quote SQL identifiers? This returns a space if identifier quoting
   * isn't supported. A JDBC Compliant driver will always use a double quote character.
   *
   * @return the quoting string
   * @throws SQLException if a database access error occurs
   */
  @Override
  public String getIdentifierQuoteString() throws SQLException {
    return "\"";
  }

    /**
    * {@inheritDoc}
    *
    * <p>From PostgreSQL 9.0+ return the keywords from pg_catalog.pg_get_keywords()</p>
    *
    * @return a comma separated list of keywords we use
    * @throws SQLException if a database access error occurs
    */
    @Override
    public String getSQLKeywords() throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT keyword_name FROM duckdb_keywords()")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1));
                sb.append(',');
            }
          return sb.toString();
        }
    }

  @Override
  public String getNumericFunctions() throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet rs = statement.executeQuery("SELECT DISTINCT function_name FROM duckdb_functions() "
                                               + "WHERE parameter_types[1] ='DECIMAL'"
                                               + "OR parameter_types[1] ='DOUBLE'"
                                               + "OR parameter_types[1] ='SMALLINT'"
                                               + "OR parameter_types[1] = 'BIGINT'")) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
            sb.append(rs.getString(1));
            sb.append(',');
        }
        return sb.toString();
    }
  }

  @Override
  public String getStringFunctions() throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet rs = statement.executeQuery(
             "SELECT DISTINCT function_name FROM duckdb_functions() WHERE parameter_types[1] = 'VARCHAR'")) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
            sb.append(rs.getString(1));
            sb.append(',');
        }
        return sb.toString();
    }
  }

  @Override
  public String getSystemFunctions() throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet rs = statement.executeQuery(
             "SELECT DISTINCT function_name FROM duckdb_functions() WHERE length(parameter_types) = 0")) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
            sb.append(rs.getString(1));
            sb.append(',');
        }
        return sb.toString();
    }
  }

  @Override
  public String getTimeDateFunctions() throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet rs = statement.executeQuery(
             "SELECT DISTINCT function_name FROM duckdb_functions() WHERE parameter_types[1] LIKE 'TIME%'")) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
            sb.append(rs.getString(1));
            sb.append(',');
        }
        return sb.toString();
    }
  }

  @Override
  public String getSearchStringEscape() throws SQLException {
    // This method originally returned "\\\\" assuming that it
    // would be fed directly into pg's input parser so it would
    // need two backslashes. This isn't how it's supposed to be
    // used though. If passed as a PreparedStatement parameter
    // or fed to a DatabaseMetaData method then double backslashes
    // are incorrect. If you're feeding something directly into
    // a query you are responsible for correctly escaping it.
    // With 8.2+ this escaping is a little trickier because you
    // must know the setting of standard_conforming_strings, but
    // that's not our problem.

    return "\\";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Postgresql allows any high-bit character to be used in an unquoted identifier, so we can't
   * possibly list them all.</p>
   *
   * <p>From the file src/backend/parser/scan.l, an identifier is ident_start [A-Za-z\200-\377_]
   * ident_cont [A-Za-z\200-\377_0-9\$] identifier {ident_start}{ident_cont}*</p>
   *
   * @return a string containing the extra characters
   * @throws SQLException if a database access error occurs
   */
  @Override
  public String getExtraNameCharacters() throws SQLException {
    return "";
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 6.1+
   */
  @Override
  public boolean supportsAlterTableWithAddColumn() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.3+
   */
  @Override
  public boolean supportsAlterTableWithDropColumn() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsColumnAliasing() throws SQLException {
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsConvert() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) throws SQLException {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 6.4+
   */
  @Override
  public boolean supportsOrderByUnrelated() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsGroupBy() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 6.4+
   */
  @Override
  public boolean supportsGroupByUnrelated() throws SQLException {
    return true;
  }

  /*
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 6.4+
   */
  @Override
  public boolean supportsGroupByBeyondSelect() throws SQLException {
    return true;
  }

  /*
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.1+
   */
  @Override
  public boolean supportsLikeEscapeClause() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsMultipleResultSets() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsMultipleTransactions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsNonNullableColumns() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This grammar is defined at:
   * <a href="http://www.microsoft.com/msdn/sdk/platforms/doc/odbc/src/intropr.htm">
   *     http://www.microsoft.com/msdn/sdk/platforms/doc/odbc/src/intropr.htm</a></p>
   *
   * <p>In Appendix C. From this description, we seem to support the ODBC minimal (Level 0) grammar.</p>
   *
   * @return true
   */
  @Override
  public boolean supportsMinimumSQLGrammar() throws SQLException {
    return true;
  }

  /**
   * Does this driver support the Core ODBC SQL grammar. We need SQL-92 conformance for this.
   *
   * @return false
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean supportsCoreSQLGrammar() throws SQLException {
    return false;
  }

  /**
   * Does this driver support the Extended (Level 2) ODBC SQL grammar. We don't conform to the Core
   * (Level 1), so we can't conform to the Extended SQL Grammar.
   *
   * @return false
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean supportsExtendedSQLGrammar() throws SQLException {
    return false;
  }

  /**
   * Does this driver support the ANSI-92 entry level SQL grammar? All JDBC Compliant drivers must
   * return true. We currently report false until 'schema' support is added. Then this should be
   * changed to return true, since we will be mostly compliant (probably more compliant than many
   * other databases) And since this is a requirement for all JDBC drivers we need to get to the
   * point where we can return true.
   *
   * @return true if connected to PostgreSQL 7.3+
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean supportsANSI92EntryLevelSQL() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return false
   */
  @Override
  public boolean supportsANSI92IntermediateSQL() throws SQLException {
    return false;
  }

  /**
   * {@inheritDoc}
   *
   * @return false
   */
  @Override
  public boolean supportsANSI92FullSQL() throws SQLException {
    return false;
  }

  /*
   * Is the SQL Integrity Enhancement Facility supported? Our best guess is that this means support
   * for constraints
   *
   * @return true
   *
   * @exception SQLException if a database access error occurs
   */
  @Override
  public boolean supportsIntegrityEnhancementFacility() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.1+
   */
  @Override
  public boolean supportsOuterJoins() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.1+
   */
  @Override
  public boolean supportsFullOuterJoins() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.1+
   */
  @Override
  public boolean supportsLimitedOuterJoins() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>PostgreSQL doesn't have schemas, but when it does, we'll use the term "schema".</p>
   *
   * @return {@code "schema"}
   */
  @Override
  public String getSchemaTerm() throws SQLException {
    return "schema";
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "function"}
   */
  @Override
  public String getProcedureTerm() throws SQLException {
    return "function";
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "database"}
   */
  @Override
  public String getCatalogTerm() throws SQLException {
    return "database";
  }

  @Override
  public boolean isCatalogAtStart() throws SQLException {
    return true;
  }

  @Override
  public String getCatalogSeparator() throws SQLException {
    return ".";
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.3+
   */
  @Override
  public boolean supportsSchemasInDataManipulation() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.3+
   */
  @Override
  public boolean supportsSchemasInProcedureCalls() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.3+
   */
  @Override
  public boolean supportsSchemasInTableDefinitions() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.3+
   */
  @Override
  public boolean supportsSchemasInIndexDefinitions() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.3+
   */
  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
    return false;
  }

  /**
   * We support cursors for gets only it seems. I dont see a method to get a positioned delete.
   *
   * @return false
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean supportsPositionedDelete() throws SQLException {
    return false; // For now...
  }

  @Override
  public boolean supportsPositionedUpdate() throws SQLException {
    return false; // For now...
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 6.5+
   */
  @Override
  public boolean supportsSelectForUpdate() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.1+
   */
  @Override
  public boolean supportsCorrelatedSubqueries() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 6.3+
   */
  @Override
  public boolean supportsUnion() throws SQLException {
    return true; // since 6.3
  }

  /**
   * {@inheritDoc}
   *
   * @return true if connected to PostgreSQL 7.1+
   */
  @Override
  public boolean supportsUnionAll() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc} In PostgreSQL, Cursors are only open within transactions.
   */
  @Override
  public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
    return false;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Can statements remain open across commits? They may, but this driver cannot guarantee that. In
   * further reflection. we are talking a Statement object here, so the answer is yes, since the
   * Statement is only a vehicle to ExecSQL()</p>
   *
   * @return true
   */
  @Override
  public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Can statements remain open across rollbacks? They may, but this driver cannot guarantee that.
   * In further contemplation, we are talking a Statement object here, so the answer is yes, since
   * the Statement is only a vehicle to ExecSQL() in Connection</p>
   *
   * @return true
   */
  @Override
  public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
    return true;
  }

  @Override
  public int getMaxCharLiteralLength() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxBinaryLiteralLength() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxColumnNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInGroupBy() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxColumnsInSelect() throws SQLException {
    return 0; // no limit
  }

  /**
   * {@inheritDoc} What is the maximum number of columns in a table? From the CREATE TABLE reference
   * page...
   *
   * <p>"The new class is created as a heap with no initial data. A class can have no more than 1600
   * attributes (realistically, this is limited by the fact that tuple sizes must be less than 8192
   * bytes)..."</p>
   *
   * @return the max columns
   * @throws SQLException if a database access error occurs
   */
  @Override
  public int getMaxColumnsInTable() throws SQLException {
    return 0;
  }

  /**
   * {@inheritDoc} How many active connection can we have at a time to this database? Well, since it
   * depends on postmaster, which just does a listen() followed by an accept() and fork(), its
   * basically very high. Unless the system runs out of processes, it can be 65535 (the number of
   * aux. ports on a TCP/IP system). I will return 8192 since that is what even the largest system
   * can realistically handle,
   *
   * @return the maximum number of connections
   * @throws SQLException if a database access error occurs
   */
  @Override
  public int getMaxConnections() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxIndexLength() throws SQLException {
    return 0; // no limit (larger than an int anyway)
  }

  @Override
  public int getMaxSchemaNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxProcedureNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxRowSize() throws SQLException {
    return 0; // 1 GB
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
    return false;
  }

  @Override
  public int getMaxStatementLength() throws SQLException {
    return 0; // actually whatever fits in size_t
  }

  @Override
  public int getMaxStatements() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getMaxTablesInSelect() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxUserNameLength() throws SQLException {
    return 0;
  }

  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
      return Connection.TRANSACTION_REPEATABLE_READ;
  }

  @Override
  public boolean supportsTransactions() throws SQLException {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>We only support TRANSACTION_SERIALIZABLE and TRANSACTION_READ_COMMITTED before 8.0; from 8.0
   * READ_UNCOMMITTED and REPEATABLE_READ are accepted aliases for READ_COMMITTED.</p>
   */
  @Override
  public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
      return level < Connection.TRANSACTION_SERIALIZABLE;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
    return false;
  }

  /**
   * Does a data definition statement within a transaction force the transaction to commit? It seems
   * to mean something like:
   *
   * <pre>
   * CREATE TABLE T (A INT);
   * INSERT INTO T (A) VALUES (2);
   * BEGIN;
   * UPDATE T SET A = A + 1;
   * CREATE TABLE X (A INT);
   * SELECT A FROM T INTO X;
   * COMMIT;
   * </pre>
   *
   * <p>Does the CREATE TABLE call cause a commit? The answer is no.</p>
   *
   * @return true if so
   * @throws SQLException if a database access error occurs
   */
  @Override
  public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
    return false;
  }

  /**
   * Turn the provided value into a valid string literal for direct inclusion into a query. This
   * includes the single quotes needed around it.
   *
   * @param s input value
   *
   * @return string literal for direct inclusion into a query
   * @throws SQLException if something wrong happens
   */
  protected String escapeQuotes(String s) throws SQLException {
    StringBuilder sb = new StringBuilder();
    if (!connection.getStandardConformingStrings()) {
      sb.append("E");
    }
    sb.append("'");
    sb.append(connection.escapeString(s));
    sb.append("'");
    return sb.toString();
  }

  @Override
  public ResultSet getProcedures(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String procedureNamePattern)
      throws SQLException {
      Statement statement = connection.createStatement();
      statement.closeOnCompletion();
      return statement.executeQuery("SELECT NULL WHERE FALSE");
  }

  @Override
  public ResultSet getProcedureColumns(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String procedureNamePattern, @Nullable String columnNamePattern)
      throws SQLException {
      Statement statement = connection.createStatement();
      statement.closeOnCompletion();
      return statement.executeQuery("SELECT NULL WHERE FALSE");
  }

    @Override
    public ResultSet getTables(@Nullable String catalog, @Nullable String schemaPattern,
        @Nullable String tableNamePattern, String @Nullable [] types) throws SQLException {
        StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);

        sb.append("SELECT").append(lineSeparator());
        sb.append("table_catalog AS 'TABLE_CAT'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_schema AS 'TABLE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_name AS 'TABLE_NAME'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_type AS 'TABLE_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("TABLE_COMMENT AS 'REMARKS'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL::VARCHAR AS 'TYPE_CAT'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL::VARCHAR AS 'TYPE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL::VARCHAR AS 'TYPE_NAME'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL::VARCHAR AS 'SELF_REFERENCING_COL_NAME'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL::VARCHAR AS 'REF_GENERATION'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("FROM (select database_name as table_catalog, schema_name as table_schema, table_name, CASE  WHEN "
                  + "(\"temporary\") THEN ('LOCAL TEMPORARY')  WHEN (\"internal\") THEN 'SYSTEM TABLE' ELSE 'TABLE' "
                  + "END AS table_type, comment AS TABLE_COMMENT")
            .append(lineSeparator());
        sb.append("from  duckdb_tables() x").append(lineSeparator());
        sb.append("union all select database_name,schema_name, view_name, CASE  WHEN (\"internal\") then 'SYSTEM "
                  + "VIEW' ELSE 'VIEW' END, comment from duckdb_views() x   ) x")
            .append(lineSeparator());
        sb.append("WHERE table_name LIKE ? ESCAPE '\\\\'").append(lineSeparator());
        boolean hasCatalogParam = appendEqualsQual(sb, "table_catalog", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "table_schema", schemaPattern);

        if (types != null && types.length > 0) {
          sb.append("AND table_type IN (").append(lineSeparator());
          for (int i = 0; i < types.length; i++) {
            if (i > 0) {
              sb.append(',');
            }
            sb.append('?');
          }
          sb.append(')');
        }

        // ordered by TABLE_TYPE, TABLE_CAT, TABLE_SCHEM and TABLE_NAME.
        sb.append("ORDER BY").append(lineSeparator());
        sb.append("table_type").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_catalog").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_schema").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_name").append(lineSeparator());

        PreparedStatement ps = connection.prepareStatement(sb.toString());

        int paramIdx = 1;
        ps.setString(paramIdx++, nullPatternToWildcard(tableNamePattern));

        if (hasCatalogParam) {
          ps.setString(paramIdx++, catalog);
        }
        if (hasSchemaParam) {
          ps.setString(paramIdx++, schemaPattern);
        }

        if (types != null && types.length > 0) {
          for (int i = 0; i < types.length; i++) {
            ps.setString(paramIdx + i, types[i]);
          }
        }
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
    Statement statement = connection.createStatement();
    statement.closeOnCompletion();
    return statement.executeQuery(
            "SELECT schema_name AS 'TABLE_SCHEM', catalog_name AS 'TABLE_CATALOG' FROM information_schema.schemata ORDER BY 1,2");
  }

    @Override
    public ResultSet getSchemas(@Nullable String catalog, @Nullable String schemaPattern) throws SQLException
    {
        StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
        sb.append("SELECT").append(lineSeparator());
        sb.append("schema_name AS 'TABLE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("catalog_name AS 'TABLE_CATALOG'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("FROM information_schema.schemata").append(lineSeparator());
        sb.append("WHERE TRUE").append(lineSeparator());
        boolean hasCatalogParam = appendEqualsQual(sb, "catalog_name", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append("ORDER BY").append(lineSeparator());
        sb.append("\"TABLE_CATALOG\"").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("\"TABLE_SCHEM\"").append(lineSeparator());

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int paramIdx = 0;
        if (hasCatalogParam) {
          ps.setString(++paramIdx, catalog);
        }
        if (hasSchemaParam) {
          ps.setString(++paramIdx, schemaPattern);
        }
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        String sql = """
                SELECT DISTINCT catalog_name AS 'TABLE_CAT' FROM information_schema.schemata ORDER BY 1
            """;
        Statement statement = connection.createStatement();
        statement.closeOnCompletion();
        return statement.executeQuery(sql);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        String[] tableTypesArray = new String[] {"TABLE", "LOCAL TEMPORARY", "VIEW", "SYSTEM VIEW"};
        StringBuilder stringBuilder = new StringBuilder(128);
        boolean first = true;
        for (String tableType : tableTypesArray) {
            if (!first) {
                stringBuilder.append("\nUNION ALL\n");
            }
            stringBuilder.append("SELECT '");
            stringBuilder.append(tableType);
            stringBuilder.append("'");
            if (first) {
                stringBuilder.append(" AS 'TABLE_TYPE'");
                first = false;
            }
        }
        stringBuilder.append("\nORDER BY TABLE_TYPE");
        Statement statement = connection.createStatement();
        statement.closeOnCompletion();
        return statement.executeQuery(stringBuilder.toString());
    }

  @Override
  public ResultSet getColumns(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String tableNamePattern,
      @Nullable String columnNamePattern) throws SQLException {
    StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
    sb.append("SELECT").append(lineSeparator());
    sb.append("database_name AS 'TABLE_CAT'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("schema_name AS 'TABLE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("table_name AS 'TABLE_NAME'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("column_name as 'COLUMN_NAME'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append(makeDataMap("regexp_replace(c.data_type, '\\(.*\\)', '')", "DATA_TYPE"))
            .append(TRAILING_COMMA)
            .append(lineSeparator());
    sb.append("c.data_type AS 'TYPE_NAME'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("numeric_precision AS 'COLUMN_SIZE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'BUFFER_LENGTH'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("numeric_scale AS 'DECIMAL_DIGITS'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("10 AS 'NUM_PREC_RADIX'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("CASE WHEN is_nullable = TRUE THEN 1 else 0 END AS 'NULLABLE'")
            .append(TRAILING_COMMA)
            .append(lineSeparator());
    sb.append("comment as 'REMARKS'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("column_default AS 'COLUMN_DEF'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SQL_DATA_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SQL_DATETIME_SUB'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'CHAR_OCTET_LENGTH'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("column_index AS 'ORDINAL_POSITION'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("CASE WHEN is_nullable = TRUE THEN 'YES' ELSE 'NO' END AS 'IS_NULLABLE'")
            .append(TRAILING_COMMA)
            .append(lineSeparator());
    sb.append("NULL AS 'SCOPE_CATALOG'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SCOPE_SCHEMA'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SCOPE_TABLE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SOURCE_DATA_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("'' AS 'IS_AUTOINCREMENT'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("'' AS 'IS_GENERATEDCOLUMN'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("FROM duckdb_columns() c").append(lineSeparator());
    sb.append("WHERE TRUE").append(lineSeparator());
    boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
    boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
    sb.append("AND table_name LIKE ? ESCAPE '\\\\'").append(lineSeparator());
    sb.append("AND column_name LIKE ? ESCAPE '\\\\'").append(lineSeparator());
    sb.append("ORDER BY").append(lineSeparator());
    sb.append("\"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("\"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("\"TABLE_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("\"column_index\"").append(lineSeparator());

    PreparedStatement ps = connection.prepareStatement(sb.toString());

    int paramIdx = 1;
    if (hasCatalogParam) {
      ps.setString(paramIdx++, catalog);
    }
    if (hasSchemaParam) {
      ps.setString(paramIdx++, schemaPattern);
    }
    ps.setString(paramIdx++, nullPatternToWildcard(tableNamePattern));
    ps.setString(paramIdx++, nullPatternToWildcard(columnNamePattern));
    ps.closeOnCompletion();
    return ps.executeQuery();
  }

    @Override
    public ResultSet getColumnPrivileges(
        @Nullable String catalog, @Nullable String schema,
        String table, @Nullable String columnNamePattern) throws SQLException
    {
        throw new SQLFeatureNotSupportedException("getColumnPrivileges");
    }

  @Override
  public ResultSet getTablePrivileges(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String tableNamePattern) throws SQLException {
      throw new SQLFeatureNotSupportedException("getTablePrivileges");
  }

    @Override
    public ResultSet getBestRowIdentifier(
        @Nullable String catalog, @Nullable String schema, String table,
        int scope, boolean nullable) throws SQLException {
            throw new SQLFeatureNotSupportedException("getBestRowIdentifier");
    }

    @Override
    public ResultSet getVersionColumns(
        @Nullable String catalog, @Nullable String schema, String table)
        throws SQLException {
            throw new SQLFeatureNotSupportedException("getVersionColumns");
    }

  @Override
  public ResultSet getPrimaryKeys(@Nullable String catalog, @Nullable String schema, String table)
      throws SQLException {
      StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
      sb.append("WITH constraint_columns AS (").append(lineSeparator());
      sb.append("SELECT").append(lineSeparator());
      sb.append("database_name AS \"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("schema_name AS \"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("table_name AS \"TABLE_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("unnest(constraint_column_names) AS \"COLUMN_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("NULL::VARCHAR AS \"PK_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("FROM duckdb_constraints").append(lineSeparator());
      sb.append("WHERE constraint_type = 'PRIMARY KEY'").append(lineSeparator());
      boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
      boolean hasSchemaParam = appendEqualsQual(sb, "schema_name", schema);
      sb.append("AND table_name = ?").append(lineSeparator());
      sb.append(")").append(lineSeparator());
      sb.append("SELECT").append(lineSeparator());
      sb.append("\"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TABLE_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"COLUMN_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("CAST(ROW_NUMBER() OVER (").append(lineSeparator());
      sb.append("PARTITION BY").append(lineSeparator());
      sb.append("\"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TABLE_NAME\"").append(lineSeparator());
      sb.append(") AS INT) AS \"KEY_SEQ\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"PK_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("FROM constraint_columns").append(lineSeparator());
      sb.append("ORDER BY").append(lineSeparator());
      sb.append("\"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TABLE_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"KEY_SEQ\"").append(lineSeparator());

      PreparedStatement ps = connection.prepareStatement(sb.toString());
      int paramIdx = 1;

      if (hasCatalogParam) {
          ps.setString(paramIdx++, catalog);
      }
      if (hasSchemaParam) {
          ps.setString(paramIdx++, schema);
      }
      ps.setString(paramIdx++, table);
      ps.closeOnCompletion();
      return ps.executeQuery();
  }

    @Override
    public ResultSet getImportedKeys(@Nullable String catalog, @Nullable String schema, String table)
        throws SQLException {
        return getCrossReference(null, null, null, catalog, schema, table);
    }

    @Override
    public ResultSet getExportedKeys(@Nullable String catalog, @Nullable String schema, String table)
      throws SQLException {
        return getCrossReference(catalog, schema, table, null, null, null);
    }

    /**
     * 外键关系（导入/导出/交叉引用三个 JDBC 方法共用一个实现）。
     *
     * <p>DuckDB 自 1.4 起把 {@code information_schema.referential_constraints} 填全了，
     * 上游 duckdb-java 也是从那时起用这组 information_schema 视图实现本方法（此前直接抛
     * {@code SQLFeatureNotSupportedException}）。这里跟上游对齐：DBeaver / dbdesk 这类
     * 反查外键的工具因此才能画出关系图。</p>
     */
    @Override
    public ResultSet getCrossReference(
      @Nullable String primaryCatalog, @Nullable String primarySchema, @Nullable String primaryTable,
      @Nullable String foreignCatalog, @Nullable String foreignSchema, @Nullable String foreignTable)
      throws SQLException {
      StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
      sb.append("SELECT").append(lineSeparator());
      sb.append("  pk_tc.table_catalog AS PKTABLE_CAT").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  pk_tc.table_schema AS PKTABLE_SCHEM").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  pk_tc.table_name AS PKTABLE_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  pk_kcu.column_name AS PKCOLUMN_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  fk_tc.table_catalog AS FKTABLE_CAT").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  fk_tc.table_schema AS FKTABLE_SCHEM").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  fk_tc.table_name AS FKTABLE_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  fk_kcu.column_name AS FKCOLUMN_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  fk_kcu.ordinal_position AS KEY_SEQ").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  CASE rc.update_rule").append(lineSeparator());
      sb.append("    WHEN 'CASCADE' THEN 0").append(lineSeparator());
      sb.append("    WHEN 'RESTRICT' THEN 1").append(lineSeparator());
      sb.append("    WHEN 'SET NULL' THEN 2").append(lineSeparator());
      sb.append("    WHEN 'SET DEFAULT' THEN 4").append(lineSeparator());
      sb.append("    ELSE 3").append(lineSeparator());
      sb.append("  END AS UPDATE_RULE").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  CASE rc.delete_rule").append(lineSeparator());
      sb.append("    WHEN 'CASCADE' THEN 0").append(lineSeparator());
      sb.append("    WHEN 'RESTRICT' THEN 1").append(lineSeparator());
      sb.append("    WHEN 'SET NULL' THEN 2").append(lineSeparator());
      sb.append("    WHEN 'SET DEFAULT' THEN 4").append(lineSeparator());
      sb.append("    ELSE 3").append(lineSeparator());
      sb.append("  END AS DELETE_RULE").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  rc.constraint_name AS FK_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  rc.unique_constraint_name AS PK_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  CASE").append(lineSeparator());
      sb.append("    WHEN fk_tc.is_deferrable = 'YES' AND fk_tc.initially_deferred = 'YES' THEN 5")
              .append(lineSeparator());
      sb.append("    WHEN fk_tc.is_deferrable = 'YES' AND fk_tc.initially_deferred = 'NO' THEN 6")
              .append(lineSeparator());
      sb.append("    ELSE 7").append(lineSeparator());
      sb.append("  END AS DEFERRABILITY").append(lineSeparator());
      sb.append("FROM information_schema.referential_constraints rc").append(lineSeparator());
      sb.append("  JOIN information_schema.table_constraints fk_tc").append(lineSeparator());
      sb.append("    ON fk_tc.constraint_catalog = rc.constraint_catalog").append(lineSeparator());
      sb.append("    AND fk_tc.constraint_schema = rc.constraint_schema").append(lineSeparator());
      sb.append("    AND fk_tc.constraint_name = rc.constraint_name").append(lineSeparator());
      sb.append("  JOIN information_schema.key_column_usage fk_kcu").append(lineSeparator());
      sb.append("    ON fk_kcu.constraint_catalog = rc.constraint_catalog").append(lineSeparator());
      sb.append("    AND fk_kcu.constraint_schema = rc.constraint_schema").append(lineSeparator());
      sb.append("    AND fk_kcu.constraint_name = rc.constraint_name").append(lineSeparator());
      sb.append("  JOIN information_schema.table_constraints pk_tc").append(lineSeparator());
      sb.append("    ON pk_tc.constraint_catalog = rc.unique_constraint_catalog").append(lineSeparator());
      sb.append("    AND pk_tc.constraint_schema = rc.unique_constraint_schema").append(lineSeparator());
      sb.append("    AND pk_tc.constraint_name = rc.unique_constraint_name").append(lineSeparator());
      sb.append("  JOIN information_schema.key_column_usage pk_kcu").append(lineSeparator());
      sb.append("    ON pk_kcu.constraint_catalog = pk_tc.constraint_catalog").append(lineSeparator());
      sb.append("    AND pk_kcu.constraint_schema = pk_tc.constraint_schema").append(lineSeparator());
      sb.append("    AND pk_kcu.constraint_name = pk_tc.constraint_name").append(lineSeparator());
      sb.append("    AND pk_kcu.ordinal_position = fk_kcu.position_in_unique_constraint")
              .append(lineSeparator());
      sb.append("WHERE TRUE").append(lineSeparator());
      boolean hasPkCatalogParam = appendEqualsQual(sb, "pk_tc.table_catalog", primaryCatalog);
      boolean hasPkSchemaParam = appendEqualsQual(sb, "pk_tc.table_schema", primarySchema);
      boolean hasFkCatalogParam = appendEqualsQual(sb, "fk_tc.table_catalog", foreignCatalog);
      boolean hasFkSchemaParam = appendEqualsQual(sb, "fk_tc.table_schema", foreignSchema);
      // 三个调用者各自只填一半参数：getImportedKeys 只给"外键侧"，getExportedKeys 只给
      // "主键侧"，getCrossReference 两侧都给。缺哪侧就不加那个条件。
      if (primaryTable != null) {
        sb.append("AND pk_tc.table_name = ?").append(lineSeparator());
      }
      if (foreignTable != null) {
        sb.append("AND fk_tc.table_name = ?").append(lineSeparator());
      }
      sb.append("ORDER BY").append(lineSeparator());
      sb.append("  PKTABLE_CAT").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  PKTABLE_SCHEM").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  PKTABLE_NAME").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  KEY_SEQ").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  FKTABLE_CAT").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  FKTABLE_SCHEM").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("  FKTABLE_NAME").append(lineSeparator());

      PreparedStatement ps = connection.prepareStatement(sb.toString());
      int paramIdx = 1;
      if (hasPkCatalogParam) {
        ps.setString(paramIdx++, primaryCatalog);
      }
      if (hasPkSchemaParam) {
        ps.setString(paramIdx++, primarySchema);
      }
      if (hasFkCatalogParam) {
        ps.setString(paramIdx++, foreignCatalog);
      }
      if (hasFkSchemaParam) {
        ps.setString(paramIdx++, foreignSchema);
      }
      if (primaryTable != null) {
        ps.setString(paramIdx++, primaryTable);
      }
      if (foreignTable != null) {
        ps.setString(paramIdx++, foreignTable);
      }
      ps.closeOnCompletion();
      return ps.executeQuery();
    }

    /**
     * 类型清单。
     *
     * <p>上游 duckdb-java 1.5.6 起用纯字面量 {@code UNION ALL} 实现（不查系统表），
     * 这里照搬过来：{@code getTypeInfo()} 是 {@code java.sql.DatabaseMetaData} 的必备查询，
     * 抛 {@code SQLFeatureNotSupportedException} 会让 DBeaver / dbdesk 之类的工具在
     * "显示数据类型"时直接报错。</p>
     */
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<TypeInfoEntry> entries = Arrays.asList(
                new TypeInfoEntry("BOOLEAN", Types.BIT, typePredBasic),
                new TypeInfoEntry("TINYINT", Types.TINYINT, typePredBasic),
                new TypeInfoEntry("SMALLINT", Types.SMALLINT, typePredBasic),
                new TypeInfoEntry("INTEGER", Types.INTEGER, typePredBasic),
                new TypeInfoEntry("BIGINT", Types.BIGINT, typePredBasic),
                new TypeInfoEntry("FLOAT", Types.FLOAT, typePredBasic),
                new TypeInfoEntry("REAL", Types.REAL, typePredBasic),
                new TypeInfoEntry("DOUBLE", Types.DOUBLE, typePredBasic),
                new TypeInfoEntry("DECIMAL", Types.NUMERIC, typePredBasic, 0, 38),
                new TypeInfoEntry("DECIMAL", Types.DECIMAL, typePredBasic, 0, 38),
                new TypeInfoEntry("VARCHAR", Types.CHAR, typePredChar),
                new TypeInfoEntry("VARCHAR", Types.VARCHAR, typePredChar),
                new TypeInfoEntry("VARCHAR", Types.LONGVARCHAR, typePredChar),
                new TypeInfoEntry("DATE", Types.DATE, typePredBasic),
                new TypeInfoEntry("TIME", Types.TIME, typePredBasic),
                new TypeInfoEntry("TIMESTAMP", Types.TIMESTAMP, typePredBasic),
                new TypeInfoEntry("BLOB", Types.BINARY, typePredChar),
                new TypeInfoEntry("BLOB", Types.VARBINARY, typePredChar),
                new TypeInfoEntry("BLOB", Types.LONGVARBINARY, typePredChar),
                new TypeInfoEntry("NULL", Types.LONGVARBINARY, typePredBasic));

        StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
        boolean first = true;
        for (TypeInfoEntry en : entries) {
            if (first) {
                sb.append("SELECT").append(lineSeparator());
                first = false;
            } else {
                sb.append("UNION ALL SELECT").append(lineSeparator());
            }
            sb.append("  '" + en.name + "'::VARCHAR AS TYPE_NAME").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  " + en.sqlType + "::INTEGER AS DATA_TYPE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  " + en.precision + "::INTEGER AS PRECISION").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  NULL::VARCHAR AS LITERAL_PREFIX").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  NULL::VARCHAR AS LITERAL_SUFFIX").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  NULL::VARCHAR AS CREATE_PARAMS").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  " + typeNullableUnknown + "::SMALLINT AS NULLABLE")
                    .append(TRAILING_COMMA)
                    .append(lineSeparator());
            sb.append("  TRUE::BOOL AS CASE_SENSITIVE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  " + en.searchable + "::SMALLINT AS SEARCHABLE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  FALSE::BOOL AS UNSIGNED_ATTRIBUTE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  FALSE::BOOL AS FIXED_PREC_SCALE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  FALSE::BOOL AS AUTO_INCREMENT").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  NULL::VARCHAR AS LOCAL_TYPE_NAME").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  " + en.scale + "::SMALLINT AS MINIMUM_SCALE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  " + en.scale + "::SMALLINT AS MAXIMUM_SCALE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  0::INTEGER AS SQL_DATA_TYPE").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  0::INTEGER AS SQL_DATETIME_SUB").append(TRAILING_COMMA).append(lineSeparator());
            sb.append("  10::INTEGER AS NUM_PREC_RADIX").append(TRAILING_COMMA).append(lineSeparator());
        }
        // UNION ALL 的最后一行不能带尾逗号：把最后一个 ", " 换成换行。
        int tail = sb.lastIndexOf(TRAILING_COMMA);
        if (tail >= 0) {
            sb.replace(tail, tail + TRAILING_COMMA.length(), lineSeparator());
        }

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    private static class TypeInfoEntry {
        final String name;
        final int sqlType;
        final int searchable;
        final int precision;
        final int scale;

        TypeInfoEntry(String name, int sqlType, int searchable) {
            this(name, sqlType, searchable, 0, 0);
        }

        TypeInfoEntry(String name, int sqlType, int searchable, int precision, int scale) {
            this.name = name;
            this.sqlType = sqlType;
            this.searchable = searchable;
            this.precision = precision;
            this.scale = scale;
        }
    }

    @Override
    public ResultSet getIndexInfo(
      @Nullable String catalog, @Nullable String schema, String tableName,
      boolean unique, boolean approximate) throws SQLException {
        StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
        sb.append("SELECT").append(lineSeparator());
        sb.append("database_name AS 'TABLE_CAT'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("schema_name AS 'TABLE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("table_name AS 'TABLE_NAME'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("index_name AS 'INDEX_NAME'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("CASE WHEN is_unique THEN 0 ELSE 1 END AS 'NON_UNIQUE'")
                .append(TRAILING_COMMA)
                .append(lineSeparator());
        sb.append("NULL AS 'TYPE'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL AS 'ORDINAL_POSITION'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL AS 'COLUMN_NAME'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL AS 'ASC_OR_DESC'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL AS 'CARDINALITY'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL AS 'PAGES'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("NULL AS 'FILTER_CONDITION'").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("FROM duckdb_indexes()").append(lineSeparator());
        sb.append("WHERE TRUE").append(lineSeparator());
        boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
        boolean hasSchemaParam = appendEqualsQual(sb, "schema_name", schema);
        sb.append("AND table_name = ?").append(lineSeparator());
        if (unique) {
            sb.append("AND is_unique = TRUE").append(lineSeparator());
        }
        sb.append("ORDER BY").append(lineSeparator());
        sb.append("\"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("\"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("\"TABLE_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("\"NON_UNIQUE\"").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("\"INDEX_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
        sb.append("\"ORDINAL_POSITION\"").append(lineSeparator());

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int paramIdx = 1;
        if (hasCatalogParam) {
            ps.setString(paramIdx++, catalog);
        }
        if (hasSchemaParam) {
            ps.setString(paramIdx++, schema);
        }
        ps.setString(paramIdx++, tableName);
        ps.closeOnCompletion();
        return ps.executeQuery();
  }

  // ** JDBC 2 Extensions **

  /**
   * 可滚动结果集：**支持**（{@code TYPE_FORWARD_ONLY} 与 {@code TYPE_SCROLL_INSENSITIVE}）。
   *
   * <p>这里曾经只报 {@code TYPE_FORWARD_ONLY}，与实现不符 —— 驱动的 {@code PgResultSet}
   * 完整保留了 pgjdbc 的滚动能力（服务端也有游标支持，见 {@code CursorSupportTest}）。
   * 少报的代价很实际：LibreOffice 的表格浏览器、DBeaver 的数据页签会据此**降级**
   * （duckdb-java 的 PR #569 就是为这类工具补的能力）。口径与上游 pgjdbc 一致：
   * 只把 {@code TYPE_SCROLL_SENSITIVE} 报成不支持（我们按 insensitive 实现）。</p>
   */
  @Override
  public boolean supportsResultSetType(int type) throws SQLException {
      return type != ResultSet.TYPE_SCROLL_SENSITIVE;
  }

  /**
   * 结果集并发：只支持 {@code CONCUR_READ_ONLY}。
   *
   * <p>可更新结果集（{@code CONCUR_UPDATABLE}）在本 fork 中整块裁剪
   * （见 {@code doc/README.md} §7），因此这里必须报 false。
   * 注意"能创建 updatable 语句"与"支持 updatable 结果集"是两件事：前者我们允许
   * （值合法），真正的 {@code updateXxx} 会抛 {@code SQLFeatureNotSupportedException}。</p>
   */
  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
      return type != ResultSet.TYPE_SCROLL_SENSITIVE && concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  /* lots of unsupported stuff... */
  @Override
  public boolean ownUpdatesAreVisible(int type) throws SQLException {
      throw new SQLFeatureNotSupportedException("ownDeletesAreVisible");
  }

  @Override
  public boolean ownDeletesAreVisible(int type) throws SQLException {
      throw new SQLFeatureNotSupportedException("ownInsertsAreVisible");
  }

  @Override
  public boolean ownInsertsAreVisible(int type) throws SQLException {
    // indicates that
    return true;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) throws SQLException {
      throw new SQLFeatureNotSupportedException("othersUpdatesAreVisible");
  }

  @Override
  public boolean othersDeletesAreVisible(int i) throws SQLException {
      throw new SQLFeatureNotSupportedException("othersDeletesAreVisible");
  }

  @Override
  public boolean othersInsertsAreVisible(int type) throws SQLException {
      throw new SQLFeatureNotSupportedException("othersInsertsAreVisible");
  }

  @Override
  public boolean updatesAreDetected(int type) throws SQLException {
      throw new SQLFeatureNotSupportedException("updatesAreDetected");
  }

  @Override
  public boolean deletesAreDetected(int i) throws SQLException {
      throw new SQLFeatureNotSupportedException("othersInsertsAreVisible");
  }

  @Override
  public boolean insertsAreDetected(int type) throws SQLException {
      throw new SQLFeatureNotSupportedException("insertsAreDetected");
  }

  @Override
  public boolean supportsBatchUpdates() throws SQLException {
    return true;
  }

  /**
   * 用户自定义类型（UDT）。
   *
   * <p>DuckDB 1.5 起 {@code duckdb_types()} 提供了完整的类型目录，上游 duckdb-java 1.5.2
   * 起据此实现本方法（此前抛 {@code SQLFeatureNotSupportedException}）。这里跟上游对齐：
   * DuckDB 内置类型都是 {@code internal = TRUE}，因此只返回用户自己 {@code CREATE TYPE}
   * 出来的 ENUM / STRUCT / UNION。</p>
   */
  @Override
  public ResultSet getUDTs(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String typeNamePattern, int @Nullable [] types) throws SQLException {
      String udtDataTypeExpr =
          "CASE WHEN logical_type IN ('STRUCT', 'UNION') THEN " + Types.STRUCT
                  + " ELSE " + Types.DISTINCT + " END";
      String baseTypeExpr = "CASE WHEN logical_type IN ('STRUCT', 'UNION') THEN NULL::SMALLINT "
                            + "WHEN logical_type = 'ENUM' THEN " + Types.VARCHAR + "::SMALLINT "
                            + "ELSE CAST(CASE logical_type " + dataMap + " ELSE " + Types.OTHER
                            + " END AS SMALLINT) END";

      StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
      sb.append("SELECT").append(lineSeparator());
      sb.append("database_name AS 'TYPE_CAT'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("schema_name AS 'TYPE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("type_name AS 'TYPE_NAME'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("NULL::VARCHAR AS 'CLASS_NAME'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append(udtDataTypeExpr).append(" AS 'DATA_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("comment AS 'REMARKS'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append(baseTypeExpr).append(" AS 'BASE_TYPE'").append(lineSeparator());
      sb.append("FROM duckdb_types()").append(lineSeparator());
      sb.append("WHERE internal = FALSE").append(lineSeparator());
      boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
      boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
      sb.append("AND type_name LIKE ? ESCAPE '\\\\'").append(lineSeparator());

      if (types != null && types.length > 0) {
          sb.append("AND (").append(udtDataTypeExpr).append(") IN (");
          for (int i = 0; i < types.length; i++) {
              if (i > 0) {
                  sb.append(',');
              }
              sb.append('?');
          }
          sb.append(')').append(lineSeparator());
      }

      sb.append("ORDER BY").append(lineSeparator());
      sb.append("\"DATA_TYPE\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TYPE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TYPE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"TYPE_NAME\"").append(lineSeparator());

      PreparedStatement ps = connection.prepareStatement(sb.toString());
      int paramIdx = 1;
      if (hasCatalogParam) {
          ps.setString(paramIdx++, catalog);
      }
      if (hasSchemaParam) {
          ps.setString(paramIdx++, schemaPattern);
      }
      ps.setString(paramIdx++, nullPatternToWildcard(typeNamePattern));
      if (types != null) {
          for (int type : types) {
              ps.setInt(paramIdx++, type);
          }
      }
      ps.closeOnCompletion();
      return ps.executeQuery();
  }

  @Override
  public Connection getConnection() throws SQLException {
    return connection;
  }

  protected Statement createMetaDataStatement() throws SQLException {
    return connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public long getMaxLogicalLobSize() throws SQLException {
    return 0;
  }

  @Override
  public boolean supportsRefCursors() throws SQLException {
    return true;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getRowIdLifetime()");
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    return true;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    return false;
  }

  @Override
  public ResultSet getClientInfoProperties() throws SQLException {
      throw new SQLFeatureNotSupportedException("getClientInfoProperties");
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

  @Override
  public ResultSet getFunctions(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String functionNamePattern)
      throws SQLException {
      StringBuilder sb = new StringBuilder(QUERY_SB_DEFAULT_CAPACITY);
      sb.append("SELECT").append(lineSeparator());
      sb.append("NULL as 'FUNCTION_CAT'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("function_name as 'FUNCTION_NAME'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("schema_name as 'FUNCTION_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("description as 'REMARKS'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("CASE function_type").append(lineSeparator());
      sb.append("WHEN 'table' THEN ").append(functionReturnsTable).append(lineSeparator());
      sb.append("WHEN 'table_macro' THEN ").append(functionReturnsTable).append(lineSeparator());
      sb.append("ELSE ").append(functionNoTable).append(lineSeparator());
      sb.append("END as 'FUNCTION_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("FROM duckdb_functions()").append(lineSeparator());
      sb.append("WHERE TRUE").append(lineSeparator());
      boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
      boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
      sb.append("AND function_name LIKE ? ESCAPE '\\\\'").append(lineSeparator());
      sb.append("ORDER BY").append(lineSeparator());
      sb.append("\"FUNCTION_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"FUNCTION_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
      sb.append("\"FUNCTION_NAME\"").append(lineSeparator());

      PreparedStatement ps = connection.prepareStatement(sb.toString());
      int paramIdx = 1;
      if (hasCatalogParam) {
          ps.setString(paramIdx++, catalog);
      }
      if (hasSchemaParam) {
          ps.setString(paramIdx++, schemaPattern);
      }
      ps.setString(paramIdx++, nullPatternToWildcard(functionNamePattern));
      ps.closeOnCompletion();
      return ps.executeQuery();
  }

  @Override
  public ResultSet getFunctionColumns(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String functionNamePattern, @Nullable String columnNamePattern)
      throws SQLException {
      Statement statement = connection.createStatement();
      statement.closeOnCompletion();
      return statement.executeQuery("SELECT NULL WHERE FALSE");
  }

  @Override
  public ResultSet getPseudoColumns(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String tableNamePattern, @Nullable String columnNamePattern)
      throws SQLException {
      throw new SQLFeatureNotSupportedException("getPseudoColumns");
  }

  @Override
  public boolean generatedKeyAlwaysReturned() throws SQLException {
      throw new SQLFeatureNotSupportedException("generatedKeyAlwaysReturned");
  }

  @Override
  public boolean supportsSavepoints() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsNamedParameters() throws SQLException {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() throws SQLException {
    return true;
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    // 我们**支持** generated keys：驱动会把 RETURN_GENERATED_KEYS 的语句改写成带 RETURNING 的形式
    // （见 PgConnection#prepareStatement 与 Parser 的 RETURNING 追加），服务端回的结果集就是 key。
    // 唯一不支持的是"按列下标"取（prepareStatement(sql, int[]) 会抛 NOT_IMPLEMENTED），
    // 但这不影响 JDBC 对 supportsGetGeneratedKeys() 的定义，上游 pgjdbc 也返回 true。
    //
    // 这里曾被改成 false（上游注释还留着 "the ones we do support"），与实现自相矛盾：
    // 依赖该标志的工具（DBeaver / LibreOffice 的表格浏览器等）会直接放弃取主键。
    return true;
  }

  @Override
  public ResultSet getSuperTypes(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String typeNamePattern)
      throws SQLException {
      throw new SQLFeatureNotSupportedException("getSuperTypes");
  }

  @Override
  public ResultSet getSuperTables(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String tableNamePattern)
      throws SQLException {
      throw new SQLFeatureNotSupportedException("getSuperTables");
  }

  @Override
  public ResultSet getAttributes(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String typeNamePattern, @Nullable String attributeNamePattern) throws SQLException {
      throw new SQLFeatureNotSupportedException("getAttributes");
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) throws SQLException {
      return false;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getDatabaseMajorVersion() throws SQLException {
    return connection.getServerMajorVersion();
  }

  @Override
  public int getDatabaseMinorVersion() throws SQLException {
    return connection.getServerMinorVersion();
  }

  @Override
  public int getJDBCMajorVersion() {
    return DriverInfo.JDBC_MAJOR_VERSION;
  }

  @Override
  public int getJDBCMinorVersion() {
    return DriverInfo.JDBC_MINOR_VERSION;
  }

  @Override
  public int getSQLStateType() throws SQLException {
    return sqlStateSQL;
  }

  @Override
  public boolean locatorsUpdateCopy() throws SQLException {
    /*
     * Currently LOB's aren't updateable at all, so it doesn't matter what we return. We don't throw
     * the notImplemented Exception because the 1.5 JDK's CachedRowSet calls this method regardless
     * of whether large objects are used.
     */
    return true;
  }

  @Override
  public boolean supportsStatementPooling() throws SQLException {
    return false;
  }
}
