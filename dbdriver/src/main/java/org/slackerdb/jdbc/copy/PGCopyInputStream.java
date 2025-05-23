/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.copy;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.slackerdb.jdbc.PGConnection;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * InputStream for reading from a PostgreSQL COPY TO STDOUT operation.
 */
public class PGCopyInputStream extends InputStream implements CopyOut {
  private @Nullable CopyOut op;
  private byte @Nullable [] buf;
  private int at;
  private int len;

  /**
   * Uses given connection for specified COPY TO STDOUT operation.
   *
   * @param connection database connection to use for copying (protocol version 3 required)
   * @param sql COPY TO STDOUT statement
   * @throws SQLException if initializing the operation fails
   */
  public PGCopyInputStream(PGConnection connection, String sql) throws SQLException {
    this(connection.getCopyAPI().copyOut(sql));
  }

  /**
   * Use given CopyOut operation for reading.
   *
   * @param op COPY TO STDOUT operation
   */
  public PGCopyInputStream(CopyOut op) {
    this.op = op;
  }

  private CopyOut getOp() {
    return castNonNull(op);
  }

  private byte @Nullable [] fillBuffer() throws IOException {
    if (at >= len) {
      try {
        buf = getOp().readFromCopy();
      } catch (SQLException sqle) {
        throw new IOException(GT.tr("Copying from database failed: {0}", sqle.getMessage()), sqle);
      }
      if (buf == null) {
        at = -1;
      } else {
        at = 0;
        len = buf.length;
      }
    }
    return buf;
  }

  private void checkClosed() throws IOException {
    if (op == null) {
      throw new IOException(GT.tr("This copy stream is closed."));
    }
  }

  @Override
  public int available() throws IOException {
    checkClosed();
    return buf != null ? len - at : 0;
  }

  @Override
  public int read() throws IOException {
    checkClosed();
    byte[] buf = fillBuffer();
    return buf != null ? (buf[at++] & 0xFF)  : -1;
  }

  @Override
  public int read(byte[] buf) throws IOException {
    return read(buf, 0, buf.length);
  }

  @Override
  public int read(byte[] buf, int off, int siz) throws IOException {
    checkClosed();
    int got = 0;
    byte[] data = fillBuffer();
    for (; got < siz && data != null; data = fillBuffer()) {
      int length = Math.min(siz - got, len - at);
      System.arraycopy(data, at, buf, off + got, length);
      at += length;
      got += length;
    }
    return got == 0 && data == null ? -1 : got;
  }

  @Override
  public byte @Nullable [] readFromCopy() throws SQLException {
    byte[] result = null;
    try {
      byte[] buf = fillBuffer();
      if (buf != null) {
        if (at > 0 || len < buf.length) {
          result = Arrays.copyOfRange(buf, at, len);
        } else {
          result = buf;
        }
        // Mark the buffer as fully read
        at = len;
      }
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Read from copy failed."), PSQLState.CONNECTION_FAILURE, ioe);
    }
    return result;
  }

  @Override
  public byte @Nullable [] readFromCopy(boolean block) throws SQLException {
    return readFromCopy();
  }

  @Override
  public void close() throws IOException {
    // Don't complain about a double close.
    CopyOut op = this.op;
    if (op == null) {
      return;
    }

    if (op.isActive()) {
      try {
        op.cancelCopy();
      } catch (SQLException se) {
        throw new IOException("Failed to close copy reader.", se);
      }
    }
    this.op = null;
  }

  @Override
  public void cancelCopy() throws SQLException {
    getOp().cancelCopy();
  }

  @Override
  public int getFormat() {
    return getOp().getFormat();
  }

  @Override
  public int getFieldFormat(int field) {
    return getOp().getFieldFormat(field);
  }

  @Override
  public int getFieldCount() {
    return getOp().getFieldCount();
  }

  @Override
  public boolean isActive() {
    return op != null && op.isActive();
  }

  @Override
  public long getHandledRowCount() {
    return getOp().getHandledRowCount();
  }
}
