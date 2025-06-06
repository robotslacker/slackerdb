/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core.v3;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Information for "pending execute queue".
 *
 * @see QueryExecutorImpl#pendingExecuteQueue
 */
class ExecuteRequest {
  public final SimpleQuery query;
  public final @Nullable Portal portal;
  public final boolean asSimple;

  ExecuteRequest(SimpleQuery query, @Nullable Portal portal, boolean asSimple) {
    this.query = query;
    this.portal = portal;
    this.asSimple = asSimple;
  }
}
