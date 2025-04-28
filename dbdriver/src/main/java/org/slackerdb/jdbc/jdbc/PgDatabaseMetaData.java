/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.jdbc;

import static java.lang.System.lineSeparator;
import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.Driver;
import org.slackerdb.jdbc.core.BaseStatement;
import org.slackerdb.jdbc.core.Field;
import org.slackerdb.jdbc.core.Oid;
import org.slackerdb.jdbc.core.ServerVersion;
import org.slackerdb.jdbc.core.Tuple;
import org.slackerdb.jdbc.core.TypeInfo;
import org.slackerdb.jdbc.util.ByteConverter;
import org.slackerdb.jdbc.util.DriverInfo;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.JdbcBlackHole;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.KeyFor;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
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
  UNION
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
          case SMALLINT -> Types.SMALLINT;
          case INTEGER -> Types.INTEGER;
          case BIGINT -> Types.BIGINT;
          case LIST, ARRAY -> Types.ARRAY;
          case FLOAT -> Types.FLOAT;
          case DOUBLE -> Types.DOUBLE;
          case DECIMAL -> Types.DECIMAL;
          case VARCHAR -> Types.VARCHAR;
          case TIME -> Types.TIME;
          case DATE -> Types.DATE;
          case TIMESTAMP_S, TIMESTAMP_MS, TIMESTAMP, TIMESTAMP_NS -> Types.TIMESTAMP;
          case TIMESTAMP_WITH_TIME_ZONE -> Types.TIMESTAMP_WITH_TIMEZONE;
          case TIME_WITH_TIME_ZONE -> Types.TIME_WITH_TIMEZONE;
          case STRUCT -> Types.STRUCT;
          case BIT -> Types.BIT;
          case BLOB -> Types.BLOB;
          default -> Types.JAVA_OBJECT;
      };
    }

    static String dataMap;
    static {
        dataMap = makeCase(
            Arrays.stream(DuckDBColumnType.values())
                    .collect(Collectors.toMap(ty -> ty.name().replace("_", " "),
                            PgDatabaseMetaData::type_to_int)));
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
        return String.format("CASE %s %s ELSE %d END as %s", srcColumnName, dataMap, Types.JAVA_OBJECT, destColumnName);
    }

  public PgDatabaseMetaData(PgConnection conn) {
    this.connection = conn;
  }


  private @Nullable String keywords;

  protected final PgConnection connection; // The connection association

  private int nameDataLength; // length for name datatype
  private int indexMaxKeys; // maximum number of keys in an index.

  protected int getMaxIndexKeys() throws SQLException {
    if (indexMaxKeys == 0) {
      String sql;
      sql = "SELECT setting FROM pg_catalog.pg_settings WHERE name='max_index_keys'";

      Statement stmt = connection.createStatement();
      ResultSet rs = null;
      try {
        rs = stmt.executeQuery(sql);
        if (!rs.next()) {
          stmt.close();
          throw new PSQLException(
              GT.tr(
                  "Unable to determine a value for MaxIndexKeys due to missing system catalog data."),
              PSQLState.UNEXPECTED_ERROR);
        }
        indexMaxKeys = rs.getInt(1);
      } finally {
        JdbcBlackHole.close(rs);
        JdbcBlackHole.close(stmt);
      }
    }
    return indexMaxKeys;
  }

  protected int getMaxNameLength() throws SQLException {
    if (nameDataLength == 0) {
      String sql;
      sql = "SELECT t.typlen FROM pg_catalog.pg_type t, pg_catalog.pg_namespace n "
            + "WHERE t.typnamespace=n.oid AND t.typname='name' AND n.nspname='pg_catalog'";

      Statement stmt = connection.createStatement();
      ResultSet rs = null;
      try {
        rs = stmt.executeQuery(sql);
        if (!rs.next()) {
          throw new PSQLException(GT.tr("Unable to find name datatype in the system catalogs."),
              PSQLState.UNEXPECTED_ERROR);
        }
        nameDataLength = rs.getInt("typlen");
      } finally {
        JdbcBlackHole.close(rs);
        JdbcBlackHole.close(stmt);
      }
    }
    return nameDataLength - 1;
  }

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

  @Override
  public boolean nullsAreSortedHigh() throws SQLException {
    return true;
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
    return false;
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
    return false;
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
    return false;
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
  @SuppressWarnings("deprecation")
  public String getNumericFunctions() throws SQLException {
    return EscapedFunctions.ABS + ',' + EscapedFunctions.ACOS + ',' + EscapedFunctions.ASIN + ','
        + EscapedFunctions.ATAN + ',' + EscapedFunctions.ATAN2 + ',' + EscapedFunctions.CEILING
        + ',' + EscapedFunctions.COS + ',' + EscapedFunctions.COT + ',' + EscapedFunctions.DEGREES
        + ',' + EscapedFunctions.EXP + ',' + EscapedFunctions.FLOOR + ',' + EscapedFunctions.LOG
        + ',' + EscapedFunctions.LOG10 + ',' + EscapedFunctions.MOD + ',' + EscapedFunctions.PI
        + ',' + EscapedFunctions.POWER + ',' + EscapedFunctions.RADIANS + ','
        + EscapedFunctions.ROUND + ',' + EscapedFunctions.SIGN + ',' + EscapedFunctions.SIN + ','
        + EscapedFunctions.SQRT + ',' + EscapedFunctions.TAN + ',' + EscapedFunctions.TRUNCATE;

  }

  @Override
  @SuppressWarnings("deprecation")
  public String getStringFunctions() throws SQLException {
    String funcs = EscapedFunctions.ASCII + ',' + EscapedFunctions.CHAR + ','
        + EscapedFunctions.CONCAT + ',' + EscapedFunctions.LCASE + ',' + EscapedFunctions.LEFT + ','
        + EscapedFunctions.LENGTH + ',' + EscapedFunctions.LTRIM + ',' + EscapedFunctions.REPEAT
        + ',' + EscapedFunctions.RTRIM + ',' + EscapedFunctions.SPACE + ','
        + EscapedFunctions.SUBSTRING + ',' + EscapedFunctions.UCASE;

    // Currently these don't work correctly with parameterized
    // arguments, so leave them out. They reorder the arguments
    // when rewriting the query, but no translation layer is provided,
    // so a setObject(N, obj) will not go to the correct parameter.
    // ','+EscapedFunctions.INSERT+','+EscapedFunctions.LOCATE+
    // ','+EscapedFunctions.RIGHT+

    funcs += ',' + EscapedFunctions.REPLACE;

    return funcs;
  }

  @Override
  @SuppressWarnings("deprecation")
  public String getSystemFunctions() throws SQLException {
    return EscapedFunctions.DATABASE + ',' + EscapedFunctions.IFNULL + ',' + EscapedFunctions.USER;
  }

  @Override
  @SuppressWarnings("deprecation")
  public String getTimeDateFunctions() throws SQLException {
    String timeDateFuncs = EscapedFunctions.CURDATE + ',' + EscapedFunctions.CURTIME + ','
        + EscapedFunctions.DAYNAME + ',' + EscapedFunctions.DAYOFMONTH + ','
        + EscapedFunctions.DAYOFWEEK + ',' + EscapedFunctions.DAYOFYEAR + ','
        + EscapedFunctions.HOUR + ',' + EscapedFunctions.MINUTE + ',' + EscapedFunctions.MONTH + ','
        + EscapedFunctions.MONTHNAME + ',' + EscapedFunctions.NOW + ',' + EscapedFunctions.QUARTER
        + ',' + EscapedFunctions.SECOND + ',' + EscapedFunctions.WEEK + ',' + EscapedFunctions.YEAR;

    timeDateFuncs += ',' + EscapedFunctions.TIMESTAMPADD;

    // +','+EscapedFunctions.TIMESTAMPDIFF;

    return timeDateFuncs;
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
    return true;
  }

  @Override
  public boolean supportsStoredProcedures() throws SQLException {
    return true;
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
    return getMaxNameLength();
  }

  @Override
  public int getMaxColumnsInGroupBy() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    return getMaxIndexKeys();
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
    return 1600;
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
    return 8192;
  }

  @Override
  public int getMaxCursorNameLength() throws SQLException {
    return getMaxNameLength();
  }

  @Override
  public int getMaxIndexLength() throws SQLException {
    return 0; // no limit (larger than an int anyway)
  }

  @Override
  public int getMaxSchemaNameLength() throws SQLException {
    return getMaxNameLength();
  }

  @Override
  public int getMaxProcedureNameLength() throws SQLException {
    return getMaxNameLength();
  }

  @Override
  public int getMaxCatalogNameLength() throws SQLException {
    return getMaxNameLength();
  }

  @Override
  public int getMaxRowSize() throws SQLException {
    return 1073741824; // 1 GB
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
    return getMaxNameLength();
  }

  @Override
  public int getMaxTablesInSelect() throws SQLException {
    return 0; // no limit
  }

  @Override
  public int getMaxUserNameLength() throws SQLException {
    return getMaxNameLength();
  }

  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
    String sql =
        "SELECT setting FROM pg_catalog.pg_settings WHERE name='default_transaction_isolation'";

    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {
      String level = null;
      if (rs.next()) {
        level = rs.getString(1);
      }
      if (level == null) {
        throw new PSQLException(
            GT.tr(
                "Unable to determine a value for DefaultTransactionIsolation due to missing "
                    + " entry in pg_catalog.pg_settings WHERE name='default_transaction_isolation'."),
            PSQLState.UNEXPECTED_ERROR);
      }
      // PostgreSQL returns the value in lower case, so using "toLowerCase" here would be
      // slightly more efficient.
      switch (level.toLowerCase(Locale.ROOT)) {
        case "read uncommitted":
          return Connection.TRANSACTION_READ_UNCOMMITTED;
        case "repeatable read":
          return Connection.TRANSACTION_REPEATABLE_READ;
        case "serializable":
          return Connection.TRANSACTION_SERIALIZABLE;
        case "read committed":
        default: // Best guess.
          return Connection.TRANSACTION_READ_COMMITTED;
      }
    }
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
    switch (level) {
      case Connection.TRANSACTION_READ_UNCOMMITTED:
      case Connection.TRANSACTION_READ_COMMITTED:
      case Connection.TRANSACTION_REPEATABLE_READ:
      case Connection.TRANSACTION_SERIALIZABLE:
        return true;
      default:
        return false;
    }
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
        sb.append("FROM information_schema.tables").append(lineSeparator());
        sb.append("WHERE table_name LIKE ? ").append(lineSeparator());
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

  private static final Map<String, Map<String, String>> tableTypeClauses;

  static {
    tableTypeClauses = new HashMap<>();
    Map<String, String> ht = new HashMap<>();
    tableTypeClauses.put("TABLE", ht);
    ht.put("SCHEMAS", "c.relkind = 'r' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
    ht.put("NOSCHEMAS", "c.relkind = 'r' AND c.relname !~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("PARTITIONED TABLE", ht);
    ht.put("SCHEMAS", "c.relkind = 'p' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
    ht.put("NOSCHEMAS", "c.relkind = 'p' AND c.relname !~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("VIEW", ht);
    ht.put("SCHEMAS",
        "c.relkind = 'v' AND n.nspname <> 'pg_catalog' AND n.nspname <> 'information_schema'");
    ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname !~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("INDEX", ht);
    ht.put("SCHEMAS",
        "c.relkind = 'i' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
    ht.put("NOSCHEMAS", "c.relkind = 'i' AND c.relname !~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("PARTITIONED INDEX", ht);
    ht.put("SCHEMAS", "c.relkind = 'I' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
    ht.put("NOSCHEMAS", "c.relkind = 'I' AND c.relname !~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("SEQUENCE", ht);
    ht.put("SCHEMAS", "c.relkind = 'S'");
    ht.put("NOSCHEMAS", "c.relkind = 'S'");
    ht = new HashMap<>();
    tableTypeClauses.put("TYPE", ht);
    ht.put("SCHEMAS",
        "c.relkind = 'c' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
    ht.put("NOSCHEMAS", "c.relkind = 'c' AND c.relname !~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("SYSTEM TABLE", ht);
    ht.put("SCHEMAS",
        "c.relkind = 'r' AND (n.nspname = 'pg_catalog' OR n.nspname = 'information_schema')");
    ht.put("NOSCHEMAS",
        "c.relkind = 'r' AND c.relname ~ '^pg_' AND c.relname !~ '^pg_toast_' AND c.relname !~ '^pg_temp_'");
    ht = new HashMap<>();
    tableTypeClauses.put("SYSTEM TOAST TABLE", ht);
    ht.put("SCHEMAS", "c.relkind = 'r' AND n.nspname = 'pg_toast'");
    ht.put("NOSCHEMAS", "c.relkind = 'r' AND c.relname ~ '^pg_toast_'");
    ht = new HashMap<>();
    tableTypeClauses.put("SYSTEM TOAST INDEX", ht);
    ht.put("SCHEMAS", "c.relkind = 'i' AND n.nspname = 'pg_toast'");
    ht.put("NOSCHEMAS", "c.relkind = 'i' AND c.relname ~ '^pg_toast_'");
    ht = new HashMap<>();
    tableTypeClauses.put("SYSTEM VIEW", ht);
    ht.put("SCHEMAS",
        "c.relkind = 'v' AND (n.nspname = 'pg_catalog' OR n.nspname = 'information_schema') ");
    ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname ~ '^pg_'");
    ht = new HashMap<>();
    tableTypeClauses.put("SYSTEM INDEX", ht);
    ht.put("SCHEMAS",
        "c.relkind = 'i' AND (n.nspname = 'pg_catalog' OR n.nspname = 'information_schema') ");
    ht.put("NOSCHEMAS",
        "c.relkind = 'v' AND c.relname ~ '^pg_' AND c.relname !~ '^pg_toast_' AND c.relname !~ '^pg_temp_'");
    ht = new HashMap<>();
    tableTypeClauses.put("TEMPORARY TABLE", ht);
    ht.put("SCHEMAS", "c.relkind IN ('r','p') AND n.nspname ~ '^pg_temp_' ");
    ht.put("NOSCHEMAS", "c.relkind IN ('r','p') AND c.relname ~ '^pg_temp_' ");
    ht = new HashMap<>();
    tableTypeClauses.put("TEMPORARY INDEX", ht);
    ht.put("SCHEMAS", "c.relkind = 'i' AND n.nspname ~ '^pg_temp_' ");
    ht.put("NOSCHEMAS", "c.relkind = 'i' AND c.relname ~ '^pg_temp_' ");
    ht = new HashMap<>();
    tableTypeClauses.put("TEMPORARY VIEW", ht);
    ht.put("SCHEMAS", "c.relkind = 'v' AND n.nspname ~ '^pg_temp_' ");
    ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname ~ '^pg_temp_' ");
    ht = new HashMap<>();
    tableTypeClauses.put("TEMPORARY SEQUENCE", ht);
    ht.put("SCHEMAS", "c.relkind = 'S' AND n.nspname ~ '^pg_temp_' ");
    ht.put("NOSCHEMAS", "c.relkind = 'S' AND c.relname ~ '^pg_temp_' ");
    ht = new HashMap<>();
    tableTypeClauses.put("FOREIGN TABLE", ht);
    ht.put("SCHEMAS", "c.relkind = 'f'");
    ht.put("NOSCHEMAS", "c.relkind = 'f'");
    ht = new HashMap<>();
    tableTypeClauses.put("MATERIALIZED VIEW", ht);
    ht.put("SCHEMAS", "c.relkind = 'm'");
    ht.put("NOSCHEMAS", "c.relkind = 'm'");
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
        String[] tableTypesArray = new String[] {"BASE TABLE", "LOCAL TEMPORARY", "VIEW"};
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
    sb.append("table_catalog AS 'TABLE_CAT'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("table_schema AS 'TABLE_SCHEM'").append(TRAILING_COMMA).append(lineSeparator());
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
    sb.append("CASE WHEN is_nullable = 'YES' THEN 1 else 0 END AS 'NULLABLE'")
            .append(TRAILING_COMMA)
            .append(lineSeparator());
    sb.append("COLUMN_COMMENT as 'REMARKS'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("column_default AS 'COLUMN_DEF'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SQL_DATA_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SQL_DATETIME_SUB'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'CHAR_OCTET_LENGTH'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("ordinal_position AS 'ORDINAL_POSITION'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("is_nullable AS 'IS_NULLABLE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SCOPE_CATALOG'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SCOPE_SCHEMA'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SCOPE_TABLE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("NULL AS 'SOURCE_DATA_TYPE'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("'' AS 'IS_AUTOINCREMENT'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("'' AS 'IS_GENERATEDCOLUMN'").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("FROM information_schema.columns c").append(lineSeparator());
    sb.append("WHERE TRUE").append(lineSeparator());
    boolean hasCatalogParam = appendEqualsQual(sb, "table_catalog", catalog);
    boolean hasSchemaParam = appendLikeQual(sb, "table_schema", schemaPattern);
    sb.append("AND table_name LIKE ? ").append(lineSeparator());
    sb.append("AND column_name LIKE ? ").append(lineSeparator());
    sb.append("ORDER BY").append(lineSeparator());
    sb.append("\"TABLE_CAT\"").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("\"TABLE_SCHEM\"").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("\"TABLE_NAME\"").append(TRAILING_COMMA).append(lineSeparator());
    sb.append("\"ORDINAL_POSITION\"").append(lineSeparator());

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

  /*
  This is for internal use only to see if a resultset is updateable.
  Unique keys can also be used so we add them to the query.
   */
  protected ResultSet getPrimaryUniqueKeys(@Nullable String catalog, @Nullable String schema, String table)
      throws SQLException {
    String sql;
    sql = "SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, "
        + "  ct.relname AS TABLE_NAME, a.attname AS COLUMN_NAME, "
        + "  (information_schema._pg_expandarray(i.indkey)).n AS KEY_SEQ, ci.relname AS PK_NAME, "
        + "  information_schema._pg_expandarray(i.indkey) AS KEYS, a.attnum AS A_ATTNUM, "
        + "  a.attnotnull AS IS_NOT_NULL "
        + "FROM pg_catalog.pg_class ct "
        + "  JOIN pg_catalog.pg_attribute a ON (ct.oid = a.attrelid) "
        + "  JOIN pg_catalog.pg_namespace n ON (ct.relnamespace = n.oid) "
        + "  JOIN pg_catalog.pg_index i ON ( a.attrelid = i.indrelid) "
        + "  JOIN pg_catalog.pg_class ci ON (ci.oid = i.indexrelid) "
        // primary as well as unique keys can be used to uniquely identify a row to update
        + "WHERE (i.indisprimary OR ( "
        + "    i.indisunique "
        + "    AND i.indisvalid "
        // partial indexes are not allowed - indpred will not be null if this is a partial index
        + "    AND i.indpred IS NULL "
        // indexes with expressions are not allowed
        + "    AND i.indexprs IS NULL "
        + "  )) ";

    if (schema != null && !schema.isEmpty()) {
      sql += " AND n.nspname = " + escapeQuotes(schema);
    }

    if (table != null && !table.isEmpty()) {
      sql += " AND ct.relname = " + escapeQuotes(table);
    }

    sql = "SELECT "
        + "       result.TABLE_CAT AS \"TABLE_CAT\", "
        + "       result.TABLE_SCHEM AS \"TABLE_SCHEM\", "
        + "       result.TABLE_NAME AS \"TABLE_NAME\", "
        + "       result.COLUMN_NAME AS \"COLUMN_NAME\", "
        + "       result.KEY_SEQ AS \"KEY_SEQ\", "
        + "       result.PK_NAME AS \"PK_NAME\", "
        + "       result.IS_NOT_NULL AS \"IS_NOT_NULL\""
        + "FROM "
        + "     (" + sql + " ) result"
        + " where "
        + " result.A_ATTNUM = (result.KEYS).x ";
    sql += " ORDER BY result.table_name, result.pk_name, result.key_seq";

    return createMetaDataStatement().executeQuery(sql);
  }

    @Override
    public ResultSet getImportedKeys(@Nullable String catalog, @Nullable String schema, String table)
        throws SQLException {
        throw new SQLFeatureNotSupportedException("getImportedKeys");
    }

    @Override
    public ResultSet getExportedKeys(@Nullable String catalog, @Nullable String schema, String table)
      throws SQLException {
        throw new SQLFeatureNotSupportedException("getExportedKeys");
    }

    @Override
    public ResultSet getCrossReference(
      @Nullable String primaryCatalog, @Nullable String primarySchema, String primaryTable,
      @Nullable String foreignCatalog, @Nullable String foreignSchema, String foreignTable)
      throws SQLException {
      throw new SQLFeatureNotSupportedException("getCrossReference");
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
      throw new SQLFeatureNotSupportedException("getTypeInfo");
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

  @Override
  public boolean supportsResultSetType(int type) throws SQLException {
      return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
      return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
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

  @Override
  public ResultSet getUDTs(@Nullable String catalog, @Nullable String schemaPattern,
      @Nullable String typeNamePattern, int @Nullable [] types) throws SQLException {
      throw new SQLFeatureNotSupportedException("getUDTs");
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
      sb.append("AND function_name LIKE ? ESCAPE '\\'").append(lineSeparator());
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
    return true;
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
    // We don't support returning generated keys by column index,
    // but that should be a rarer case than the ones we do support.
    //
    return false;
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
