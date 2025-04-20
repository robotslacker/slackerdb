/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core.v3.replication;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.copy.CopyDual;
import org.slackerdb.jdbc.core.PGStream;
import org.slackerdb.jdbc.core.QueryExecutor;
import org.slackerdb.jdbc.core.ReplicationProtocol;
import org.slackerdb.jdbc.replication.PGReplicationStream;
import org.slackerdb.jdbc.replication.ReplicationType;
import org.slackerdb.jdbc.replication.fluent.CommonOptions;
import org.slackerdb.jdbc.replication.fluent.logical.LogicalReplicationOptions;
import org.slackerdb.jdbc.replication.fluent.physical.PhysicalReplicationOptions;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class V3ReplicationProtocol implements ReplicationProtocol {

  private static final Logger LOGGER = Logger.getLogger(V3ReplicationProtocol.class.getName());
  private final QueryExecutor queryExecutor;
  private final PGStream pgStream;

  public V3ReplicationProtocol(QueryExecutor queryExecutor, PGStream pgStream) {
    this.queryExecutor = queryExecutor;
    this.pgStream = pgStream;
  }

  @Override
  public PGReplicationStream startLogical(LogicalReplicationOptions options)
      throws SQLException {

    String query = createStartLogicalQuery(options);
    return initializeReplication(query, options, ReplicationType.LOGICAL);
  }

  @Override
  public PGReplicationStream startPhysical(PhysicalReplicationOptions options)
      throws SQLException {

    String query = createStartPhysicalQuery(options);
    return initializeReplication(query, options, ReplicationType.PHYSICAL);
  }

  private PGReplicationStream initializeReplication(String query, CommonOptions options,
      ReplicationType replicationType)
      throws SQLException {
    LOGGER.log(Level.FINEST, " FE=> StartReplication(query: {0})", query);

    configureSocketTimeout(options);
    CopyDual copyDual = (CopyDual) queryExecutor.startCopy(query, true);

    return new V3PGReplicationStream(
        castNonNull(copyDual),
        options.getStartLSNPosition(),
        options.getStatusInterval(),
        replicationType
    );
  }

  /**
   * START_REPLICATION [SLOT slot_name] [PHYSICAL] XXX/XXX.
   */
  private String createStartPhysicalQuery(PhysicalReplicationOptions options) {
    StringBuilder builder = new StringBuilder();
    builder.append("START_REPLICATION");

    if (options.getSlotName() != null) {
      builder.append(" SLOT ").append(options.getSlotName());
    }

    builder.append(" PHYSICAL ").append(options.getStartLSNPosition().asString());

    return builder.toString();
  }

  /**
   * START_REPLICATION SLOT slot_name LOGICAL XXX/XXX [ ( option_name [option_value] [, ... ] ) ]
   */
  private String createStartLogicalQuery(LogicalReplicationOptions options) {
    StringBuilder builder = new StringBuilder();
    builder.append("START_REPLICATION SLOT ")
        .append(options.getSlotName())
        .append(" LOGICAL ")
        .append(options.getStartLSNPosition().asString());

    Properties slotOptions = options.getSlotOptions();
    if (slotOptions.isEmpty()) {
      return builder.toString();
    }

    //todo replace on java 8
    builder.append(" (");
    boolean isFirst = true;
    for (String name : slotOptions.stringPropertyNames()) {
      if (isFirst) {
        isFirst = false;
      } else {
        builder.append(", ");
      }
      builder.append('\"').append(name).append('\"').append(" ")
          .append('\'').append(slotOptions.getProperty(name)).append('\'');
    }
    builder.append(")");

    return builder.toString();
  }

  private void configureSocketTimeout(CommonOptions options) throws PSQLException {
    if (options.getStatusInterval() == 0) {
      return;
    }

    try {
      int previousTimeOut = pgStream.getSocket().getSoTimeout();

      int minimalTimeOut;
      if (previousTimeOut > 0) {
        minimalTimeOut = Math.min(previousTimeOut, options.getStatusInterval());
      } else {
        minimalTimeOut = options.getStatusInterval();
      }

      pgStream.getSocket().setSoTimeout(minimalTimeOut);
      // Use blocking 1ms reads for `available()` checks
      pgStream.setMinStreamAvailableCheckDelay(0);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("The connection attempt failed."),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT, ioe);
    }
  }
}
