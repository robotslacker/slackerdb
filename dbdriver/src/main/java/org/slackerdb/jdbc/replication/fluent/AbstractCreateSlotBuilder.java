/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.replication.fluent;

import org.slackerdb.jdbc.core.BaseConnection;
import org.slackerdb.jdbc.core.ServerVersion;
import org.slackerdb.jdbc.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLFeatureNotSupportedException;

public abstract class AbstractCreateSlotBuilder<T extends ChainedCommonCreateSlotBuilder<T>>
    implements ChainedCommonCreateSlotBuilder<T> {

  protected @Nullable String slotName;
  protected boolean temporaryOption;
  protected BaseConnection connection;

  protected AbstractCreateSlotBuilder(BaseConnection connection) {
    this.connection = connection;
  }

  protected abstract T self();

  @Override
  public T withSlotName(String slotName) {
    this.slotName = slotName;
    return self();
  }

  @Override
  public T withTemporaryOption() throws SQLFeatureNotSupportedException {

    if (!connection.haveMinimumServerVersion(ServerVersion.v10)) {
      throw new SQLFeatureNotSupportedException(
          GT.tr("Server does not support temporary replication slots")
      );
    }

    this.temporaryOption = true;
    return self();
  }
}
