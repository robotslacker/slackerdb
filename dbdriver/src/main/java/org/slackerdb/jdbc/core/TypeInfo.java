/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core;

import org.slackerdb.jdbc.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Iterator;

public interface TypeInfo {
  void addCoreType(String pgTypeName, Integer oid, Integer sqlType, String javaClass,
      Integer arrayOid);

  void addDataType(String type, Class<? extends PGobject> klass) throws SQLException;

  /**
   * Look up the SQL typecode for a given type oid.
   *
   * @param oid the type's OID
   * @return the SQL type code (a constant from {@link java.sql.Types}) for the type
   * @throws SQLException if an error occurs when retrieving sql type
   */
  int getSQLType(int oid) throws SQLException;

  /**
   * Look up the SQL typecode for a given postgresql type name.
   *
   * @param pgTypeName the server type name to look up
   * @return the SQL type code (a constant from {@link java.sql.Types}) for the type
   * @throws SQLException if an error occurs when retrieving sql type
   */
  int getSQLType(String pgTypeName) throws SQLException;

  int getJavaArrayType(String className) throws SQLException;

  /**
   * Look up the oid for a given postgresql type name. This is the inverse of
   * {@link #getPGType(int)}.
   *
   * @param pgTypeName the server type name to look up
   * @return the type's OID, or 0 if unknown
   * @throws SQLException if an error occurs when retrieving PG type
   */
  int getPGType(String pgTypeName) throws SQLException;

  /**
   * Look up the postgresql type name for a given oid. This is the inverse of
   * {@link #getPGType(String)}.
   *
   * @param oid the type's OID
   * @return the server type name for that OID or null if unknown
   * @throws SQLException if an error occurs when retrieving PG type
   */
  @Nullable String getPGType(int oid) throws SQLException;

  /**
   * Look up the oid of an array's base type given the array's type oid.
   *
   * @param oid the array type's OID
   * @return the base type's OID, or 0 if unknown
   * @throws SQLException if an error occurs when retrieving array element
   */
  int getPGArrayElement(int oid) throws SQLException;

  /**
   * Determine the oid of the given base postgresql type's array type.
   *
   * @param elementTypeName the base type's
   * @return the array type's OID, or 0 if unknown
   * @throws SQLException if an error occurs when retrieving array type
   */
  int getPGArrayType(String elementTypeName) throws SQLException;

  /**
   * Determine the delimiter for the elements of the given array type oid.
   *
   * @param oid the array type's OID
   * @return the base type's array type delimiter
   * @throws SQLException if an error occurs when retrieving array delimiter
   */
  char getArrayDelimiter(int oid) throws SQLException;

  Iterator<String> getPGTypeNamesWithSQLTypes();

  Iterator<Integer> getPGTypeOidsWithSQLTypes();

  @Nullable Class<? extends PGobject> getPGobject(String type);

  String getJavaClass(int oid) throws SQLException;

  @Nullable String getTypeForAlias(String alias);

  int getPrecision(int oid, int typmod);

  int getScale(int oid, int typmod);

  boolean isCaseSensitive(int oid);

  boolean isSigned(int oid);

  int getDisplaySize(int oid, int typmod);

  int getMaximumPrecision(int oid);

  boolean requiresQuoting(int oid) throws SQLException;

  /**
   * Returns true if particular sqlType requires quoting.
   * This method is used internally by the driver, so it might disappear without notice.
   *
   * @param sqlType sql type as in java.sql.Types
   * @return true if the type requires quoting
   * @throws SQLException if something goes wrong
   */
  boolean requiresQuotingSqlType(int sqlType) throws SQLException;

  /**
   * Java Integers are signed 32-bit integers, but oids are unsigned 32-bit integers.
   * We therefore read them as positive long values and then force them into signed integers
   * (wrapping around into negative values when required) or we'd be unable to correctly
   * handle the upper half of the oid space.
   *
   * <p>This function handles the mapping of uint32-values in the long to java integers, and
   * throws for values that are out of range.</p>
   *
   * @param oid the oid as a long.
   * @return the (internal) signed integer representation of the (unsigned) oid.
   * @throws SQLException if the long has a value outside of the range representable by uint32
   */
  int longOidToInt(long oid) throws SQLException;

  /**
   * Java Integers are signed 32-bit integers, but oids are unsigned 32-bit integers.
   * We must therefore first map the (internal) integer representation to a positive long
   * value before sending it to postgresql, or we would be unable to correctly handle the
   * upper half of the oid space because these negative values are disallowed as OID values.
   *
   * @param oid the (signed) integer oid to convert into a long.
   * @return the non-negative value of this oid, stored as a java long.
   */
  long intOidToLong(int oid);
}
