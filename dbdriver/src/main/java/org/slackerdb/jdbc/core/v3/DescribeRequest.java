/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core.v3;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Information for "pending describe queue".
 *
 * @see QueryExecutorImpl#pendingDescribeStatementQueue
 */
class DescribeRequest {
  public final SimpleQuery query;
  public final SimpleParameterList parameterList;
  public final boolean describeOnly;
  public final @Nullable String statementName;

  DescribeRequest(SimpleQuery query, SimpleParameterList parameterList,
      boolean describeOnly, @Nullable String statementName) {
    this.query = query;
    this.parameterList = parameterList;
    this.describeOnly = describeOnly;
    this.statementName = statementName;
  }
}
