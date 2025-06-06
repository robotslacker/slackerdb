/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core.v3;

import org.slackerdb.jdbc.copy.CopyOut;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Anticipated flow of a COPY TO STDOUT operation:
 *
 * <p>CopyManager.copyOut() -&gt;QueryExecutor.startCopy() - sends given query to server
 * -&gt;processCopyResults(): - receives CopyOutResponse from Server - creates new CopyOutImpl
 * -&gt;initCopy(): - receives copy metadata from server -&gt;CopyOutImpl.init() -&gt;lock()
 * connection for this operation - if query fails an exception is thrown - if query returns wrong
 * CopyOperation, copyOut() cancels it before throwing exception &lt;-returned: new CopyOutImpl
 * holding lock on connection repeat CopyOut.readFromCopy() until null
 * -&gt;CopyOutImpl.readFromCopy() -&gt;QueryExecutorImpl.readFromCopy() -&gt;processCopyResults() -
 * on copydata row from server -&gt;CopyOutImpl.handleCopydata() stores reference to byte array - on
 * CopyDone, CommandComplete, ReadyForQuery -&gt;unlock() connection for use by other operations
 * &lt;-returned: byte array of data received from server or null at end.</p>
 */
public class CopyOutImpl extends CopyOperationImpl implements CopyOut {
  private byte @Nullable [] currentDataRow;

  @Override
  public byte @Nullable [] readFromCopy() throws SQLException {
    return readFromCopy(true);
  }

  @Override
  public byte @Nullable [] readFromCopy(boolean block) throws SQLException {
    currentDataRow = null;
    getQueryExecutor().readFromCopy(this, block);
    return currentDataRow;
  }

  @Override
  protected void handleCopydata(byte[] data) {
    currentDataRow = data;
  }
}
