/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent;

import org.slackerdb.jdbc.core.BaseConnection;
import org.slackerdb.jdbc.core.ReplicationProtocol;
import org.slackerdb.jdbc.replication.PGReplicationStream;
import org.slackerdb.jdbc.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.slackerdb.jdbc.replication.fluent.logical.LogicalReplicationOptions;
import org.slackerdb.jdbc.replication.fluent.logical.LogicalStreamBuilder;
import org.slackerdb.jdbc.replication.fluent.logical.StartLogicalReplicationCallback;
import org.slackerdb.jdbc.replication.fluent.physical.ChainedPhysicalStreamBuilder;
import org.slackerdb.jdbc.replication.fluent.physical.PhysicalReplicationOptions;
import org.slackerdb.jdbc.replication.fluent.physical.PhysicalStreamBuilder;
import org.slackerdb.jdbc.replication.fluent.physical.StartPhysicalReplicationCallback;

import java.sql.SQLException;

public class ReplicationStreamBuilder implements ChainedStreamBuilder {
  private final BaseConnection baseConnection;

  /**
   * @param connection not null connection with that will be associate replication
   */
  public ReplicationStreamBuilder(final BaseConnection connection) {
    this.baseConnection = connection;
  }

  @Override
  public ChainedLogicalStreamBuilder logical() {
    return new LogicalStreamBuilder(new StartLogicalReplicationCallback() {
      @Override
      public PGReplicationStream start(LogicalReplicationOptions options) throws SQLException {
        ReplicationProtocol protocol = baseConnection.getReplicationProtocol();
        return protocol.startLogical(options);
      }
    });
  }

  @Override
  public ChainedPhysicalStreamBuilder physical() {
    return new PhysicalStreamBuilder(new StartPhysicalReplicationCallback() {
      @Override
      public PGReplicationStream start(PhysicalReplicationOptions options) throws SQLException {
        ReplicationProtocol protocol = baseConnection.getReplicationProtocol();
        return protocol.startPhysical(options);
      }
    });
  }
}
