/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.slackerdb.jdbc.core;

import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;

/**
 * Poor man's Statement &amp; ResultSet, used for initial queries while we're still initializing the
 * system.
 */
public class SetupQueryRunner {

  private static class SimpleResultHandler extends ResultHandlerBase {
    private @Nullable List<Tuple> tuples;

    @Nullable List<Tuple> getResults() {
      return tuples;
    }

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        @Nullable ResultCursor cursor) {
      this.tuples = tuples;
    }

    @Override
    public void handleWarning(SQLWarning warning) {
      // We ignore warnings. We assume we know what we're
      // doing in the setup queries.
    }
  }

  public static @Nullable Tuple run(QueryExecutor executor, String queryString,
      boolean wantResults) throws SQLException {
    Query query = executor.createSimpleQuery(queryString);
    SimpleResultHandler handler = new SimpleResultHandler();

    int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_SUPPRESS_BEGIN
        | QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
    if (!wantResults) {
      flags |= QueryExecutor.QUERY_NO_RESULTS | QueryExecutor.QUERY_NO_METADATA;
    }

    try {
      executor.execute(query, null, handler, 0, 0, flags);
    } finally {
      query.close();
    }

    if (!wantResults) {
      return null;
    }

    List<Tuple> tuples = handler.getResults();
    if (tuples == null || tuples.size() != 1) {
      throw new PSQLException(GT.tr("An unexpected result was returned by a query."),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT);
    }

    return tuples.get(0);
  }

}
