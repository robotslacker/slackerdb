/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent.logical;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.core.BaseConnection;
import org.slackerdb.jdbc.replication.LogSequenceNumber;
import org.slackerdb.jdbc.replication.ReplicationSlotInfo;
import org.slackerdb.jdbc.replication.ReplicationType;
import org.slackerdb.jdbc.replication.fluent.AbstractCreateSlotBuilder;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class LogicalCreateSlotBuilder
    extends AbstractCreateSlotBuilder<ChainedLogicalCreateSlotBuilder>
    implements ChainedLogicalCreateSlotBuilder {

  private @Nullable String outputPlugin;

  public LogicalCreateSlotBuilder(BaseConnection connection) {
    super(connection);
  }

  @Override
  protected ChainedLogicalCreateSlotBuilder self() {
    return this;
  }

  @Override
  public ChainedLogicalCreateSlotBuilder withOutputPlugin(String outputPlugin) {
    this.outputPlugin = outputPlugin;
    return self();
  }

  @Override
  public ReplicationSlotInfo make() throws SQLException {
    String outputPlugin = this.outputPlugin;
    if (outputPlugin == null || outputPlugin.isEmpty()) {
      throw new IllegalArgumentException(
          "OutputPlugin required parameter for logical replication slot");
    }

    if (slotName == null || slotName.isEmpty()) {
      throw new IllegalArgumentException("Replication slotName can't be null");
    }

    Statement statement = connection.createStatement();
    ResultSet result = null;
    ReplicationSlotInfo slotInfo = null;
    try {
      String sql = String.format(
          "CREATE_REPLICATION_SLOT %s %s LOGICAL %s",
          slotName,
          temporaryOption ? "TEMPORARY" : "",
          outputPlugin
      );
      statement.execute(sql);
      result = statement.getResultSet();
      if (result != null && result.next()) {
        slotInfo = new ReplicationSlotInfo(
            castNonNull(result.getString("slot_name")),
            ReplicationType.LOGICAL,
            LogSequenceNumber.valueOf(castNonNull(result.getString("consistent_point"))),
            result.getString("snapshot_name"),
            result.getString("output_plugin"));
      } else {
        throw new PSQLException(
            GT.tr("{0} returned no results"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }
    } finally {
      if (result != null) {
        result.close();
      }
      statement.close();
    }
    return slotInfo;
  }
}
