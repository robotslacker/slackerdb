/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent.logical;

import org.slackerdb.jdbc.replication.fluent.CommonOptions;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Properties;

public interface LogicalReplicationOptions extends CommonOptions {
  /**
   * Required parameter for logical replication.
   *
   * @return not null logical replication slot name that already exists on server and free.
   */
  @Override
  @Nullable String getSlotName();

  /**
   * Parameters for output plugin. Parameters will be set to output plugin that register for
   * specified replication slot name.
   *
   * @return list options that will be pass to output_plugin for that was create replication slot
   */
  Properties getSlotOptions();
}
