/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent;

import org.slackerdb.jdbc.replication.LogSequenceNumber;

import java.util.concurrent.TimeUnit;

/**
 * Fluent interface for specify common parameters for Logical and Physical replication.
 */
public interface ChainedCommonStreamBuilder<T extends ChainedCommonStreamBuilder<T>> {

  /**
   * Replication slots provide an automated way to ensure that the master does not remove WAL
   * segments until they have been received by all standbys, and that the master does not remove
   * rows which could cause a recovery conflict even when the standby is disconnected.
   *
   * @param slotName not null replication slot already exists on server.
   * @return this instance as a fluent interface
   */
  T withSlotName(String slotName);

  /**
   * Specifies the number of time between status packets sent back to the server. This allows for
   * easier monitoring of the progress from server. A value of zero disables the periodic status
   * updates completely, although an update will still be sent when requested by the server, to
   * avoid timeout disconnect. The default value is 10 seconds.
   *
   * @param time   positive time
   * @param format format for specified time
   * @return not null fluent
   */
  T withStatusInterval(int time, TimeUnit format);

  /**
   * Specify start position from which backend will start stream changes. If parameter will not
   * specify, streaming starts from restart_lsn. For more details see pg_replication_slots
   * description.
   *
   * @param lsn not null position from which need start replicate changes
   * @return not null fluent
   */
  T withStartPosition(LogSequenceNumber lsn);
}
