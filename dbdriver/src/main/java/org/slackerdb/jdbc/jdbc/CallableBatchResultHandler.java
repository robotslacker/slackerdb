/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.jdbc;

import org.slackerdb.jdbc.core.Field;
import org.slackerdb.jdbc.core.ParameterList;
import org.slackerdb.jdbc.core.Query;
import org.slackerdb.jdbc.core.ResultCursor;
import org.slackerdb.jdbc.core.Tuple;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

class CallableBatchResultHandler extends BatchResultHandler {
  CallableBatchResultHandler(PgStatement statement, Query[] queries,
      @Nullable ParameterList[] parameterLists) {
    super(statement, queries, parameterLists, false);
  }

  @Override
  public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
      @Nullable ResultCursor cursor) {
    /* ignore */
  }
}
