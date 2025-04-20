/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent.physical;

import org.slackerdb.jdbc.replication.LogSequenceNumber;
import org.slackerdb.jdbc.replication.PGReplicationStream;
import org.slackerdb.jdbc.replication.fluent.AbstractStreamBuilder;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

public class PhysicalStreamBuilder extends AbstractStreamBuilder<ChainedPhysicalStreamBuilder>
    implements ChainedPhysicalStreamBuilder, PhysicalReplicationOptions {

  private final StartPhysicalReplicationCallback startCallback;

  /**
   * @param startCallback not null callback that should be execute after build parameters for start
   *                      replication
   */
  public PhysicalStreamBuilder(StartPhysicalReplicationCallback startCallback) {
    this.startCallback = startCallback;
  }

  @Override
  protected ChainedPhysicalStreamBuilder self() {
    return this;
  }

  @Override
  public PGReplicationStream start() throws SQLException {
    return this.startCallback.start(this);
  }

  @Override
  public @Nullable String getSlotName() {
    return slotName;
  }

  @Override
  public LogSequenceNumber getStartLSNPosition() {
    return startPosition;
  }

  @Override
  public int getStatusInterval() {
    return statusIntervalMs;
  }
}
