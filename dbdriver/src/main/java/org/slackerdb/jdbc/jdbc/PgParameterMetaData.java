/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.jdbc;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.core.BaseConnection;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.index.qual.Positive;

import java.sql.ParameterMetaData;
import java.sql.SQLException;

public class PgParameterMetaData implements ParameterMetaData {

  private final BaseConnection connection;
  private final int[] oids;

  public PgParameterMetaData(BaseConnection connection, int[] oids) {
    this.connection = connection;
    this.oids = oids;
  }

  @Override
  public String getParameterClassName(@Positive int param) throws SQLException {
    checkParamIndex(param);
    return connection.getTypeInfo().getJavaClass(oids[param - 1]);
  }

  @Override
  public int getParameterCount() {
    return oids.length;
  }

  /**
   * {@inheritDoc} For now report all parameters as inputs. CallableStatements may have one output,
   * but ignore that for now.
   */
  @Override
  public int getParameterMode(int param) throws SQLException {
    checkParamIndex(param);
    return ParameterMetaData.parameterModeIn;
  }

  @Override
  public int getParameterType(int param) throws SQLException {
    checkParamIndex(param);
    return connection.getTypeInfo().getSQLType(oids[param - 1]);
  }

  @Override
  public String getParameterTypeName(int param) throws SQLException {
    checkParamIndex(param);
    return castNonNull(connection.getTypeInfo().getPGType(oids[param - 1]));
  }

  // we don't know this
  @Override
  public int getPrecision(int param) throws SQLException {
    checkParamIndex(param);
    return 0;
  }

  // we don't know this
  @Override
  public int getScale(int param) throws SQLException {
    checkParamIndex(param);
    return 0;
  }

  // we can't tell anything about nullability
  @Override
  public int isNullable(int param) throws SQLException {
    checkParamIndex(param);
    return ParameterMetaData.parameterNullableUnknown;
  }

  /**
   * {@inheritDoc} PostgreSQL doesn't have unsigned numbers
   */
  @Override
  public boolean isSigned(int param) throws SQLException {
    checkParamIndex(param);
    return connection.getTypeInfo().isSigned(oids[param - 1]);
  }

  private void checkParamIndex(int param) throws PSQLException {
    if (param < 1 || param > oids.length) {
      throw new PSQLException(
          GT.tr("The parameter index is out of range: {0}, number of parameters: {1}.",
              param, oids.length),
          PSQLState.INVALID_PARAMETER_VALUE);
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
