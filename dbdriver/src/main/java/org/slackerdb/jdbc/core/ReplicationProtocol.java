/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core;

import org.slackerdb.jdbc.replication.PGReplicationStream;
import org.slackerdb.jdbc.replication.fluent.logical.LogicalReplicationOptions;
import org.slackerdb.jdbc.replication.fluent.physical.PhysicalReplicationOptions;

import java.sql.SQLException;

/**
 * Abstracts the protocol-specific details of physic and logic replication.
 *
 * <p>With each connection open with replication options associate own instance ReplicationProtocol.</p>
 */
public interface ReplicationProtocol {
  /**
   * @param options not null options for logical replication stream
   * @return not null stream instance from which available fetch wal logs that was decode by output
   *     plugin
   * @throws SQLException on error
   */
  PGReplicationStream startLogical(LogicalReplicationOptions options) throws SQLException;

  /**
   * @param options not null options for physical replication stream
   * @return not null stream instance from which available fetch wal logs
   * @throws SQLException on error
   */
  PGReplicationStream startPhysical(PhysicalReplicationOptions options) throws SQLException;
}
