/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.slackerdb.jdbc.core.v3;

import org.slackerdb.jdbc.core.Oid;
import org.slackerdb.jdbc.core.ParameterList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Common interface for all V3 parameter list implementations.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
interface V3ParameterList extends ParameterList {
  /**
   * Ensure that all parameters in this list have been assigned values. Return silently if all is
   * well, otherwise throw an appropriate exception.
   *
   * @throws SQLException if not all parameters are set.
   */
  void checkAllParametersSet() throws SQLException;

  /**
   * Convert any function output parameters to the correct type (void) and set an ignorable value
   * for it.
   */
  void convertFunctionOutParameters();

  /**
   * Return a list of the SimpleParameterList objects that make up this parameter list. If this
   * object is already a SimpleParameterList, returns null (avoids an extra array construction in
   * the common case).
   *
   * @return an array of single-statement parameter lists, or <code>null</code> if this object is
   *         already a single-statement parameter list.
   */
  SimpleParameterList @Nullable [] getSubparams();

  /**
   * Return the parameter type information.
   * @return an array of {@link Oid} type information
   */
  int @Nullable [] getParamTypes();

  /**
   * Return the flags for each parameter.
   * @return an array of bytes used to store flags.
   */
  byte @Nullable [] getFlags();

  /**
   * Return the encoding for each parameter.
   * @return nested byte array of bytes with encoding information.
   */
  byte @Nullable [] @Nullable [] getEncoding();
}
