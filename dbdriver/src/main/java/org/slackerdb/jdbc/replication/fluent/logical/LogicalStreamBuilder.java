/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent.logical;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.replication.LogSequenceNumber;
import org.slackerdb.jdbc.replication.PGReplicationStream;
import org.slackerdb.jdbc.replication.fluent.AbstractStreamBuilder;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Properties;

public class LogicalStreamBuilder extends AbstractStreamBuilder<ChainedLogicalStreamBuilder>
    implements ChainedLogicalStreamBuilder, LogicalReplicationOptions {
  private final Properties slotOptions;

  private final StartLogicalReplicationCallback startCallback;

  /**
   * @param startCallback not null callback that should be execute after build parameters for start
   *                      replication
   */
  public LogicalStreamBuilder(StartLogicalReplicationCallback startCallback) {
    this.startCallback = startCallback;
    this.slotOptions = new Properties();
  }

  @Override
  protected ChainedLogicalStreamBuilder self() {
    return this;
  }

  @Override
  public PGReplicationStream start() throws SQLException {
    return startCallback.start(this);
  }

  @Override
  public @Nullable String getSlotName() {
    return slotName;
  }

  @Override
  public ChainedLogicalStreamBuilder withStartPosition(LogSequenceNumber lsn) {
    startPosition = lsn;
    return this;
  }

  @Override
  public ChainedLogicalStreamBuilder withSlotOption(String optionName, boolean optionValue) {
    slotOptions.setProperty(optionName, String.valueOf(optionValue));
    return this;
  }

  @Override
  public ChainedLogicalStreamBuilder withSlotOption(String optionName, int optionValue) {
    slotOptions.setProperty(optionName, String.valueOf(optionValue));
    return this;
  }

  @Override
  public ChainedLogicalStreamBuilder withSlotOption(String optionName, String optionValue) {
    slotOptions.setProperty(optionName, optionValue);
    return this;
  }

  @Override
  public ChainedLogicalStreamBuilder withSlotOptions(Properties options) {
    for (String propertyName : options.stringPropertyNames()) {
      slotOptions.setProperty(propertyName, castNonNull(options.getProperty(propertyName)));
    }
    return this;
  }

  @Override
  public LogSequenceNumber getStartLSNPosition() {
    return startPosition;
  }

  @Override
  public Properties getSlotOptions() {
    return slotOptions;
  }

  @Override
  public int getStatusInterval() {
    return statusIntervalMs;
  }
}
