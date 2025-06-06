/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent.logical;

import org.slackerdb.jdbc.replication.fluent.ChainedCommonCreateSlotBuilder;

/**
 * Logical replication slot specific parameters.
 */
public interface ChainedLogicalCreateSlotBuilder
    extends ChainedCommonCreateSlotBuilder<ChainedLogicalCreateSlotBuilder> {

  /**
   * Output plugin that should be use for decode physical represent WAL to some logical form.
   * Output plugin should be installed on server(exists in shared_preload_libraries).
   *
   * <p>Package postgresql-contrib provides sample output plugin <b>test_decoding</b> that can be
   * use for test logical replication api</p>
   *
   * @param outputPlugin not null name of the output plugin used for logical decoding
   * @return the logical slot builder
   */
  ChainedLogicalCreateSlotBuilder withOutputPlugin(String outputPlugin);
}
