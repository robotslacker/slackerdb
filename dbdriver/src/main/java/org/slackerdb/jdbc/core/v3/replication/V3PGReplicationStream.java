/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core.v3.replication;

import org.slackerdb.jdbc.copy.CopyDual;
import org.slackerdb.jdbc.replication.LogSequenceNumber;
import org.slackerdb.jdbc.replication.PGReplicationStream;
import org.slackerdb.jdbc.replication.ReplicationType;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class V3PGReplicationStream implements PGReplicationStream {

  private static final Logger LOGGER = Logger.getLogger(V3PGReplicationStream.class.getName());
  public static final long POSTGRES_EPOCH_2000_01_01 = 946684800000L;
  private static final long NANOS_PER_MILLISECOND = 1000000L;

  private final CopyDual copyDual;
  private final long updateInterval;
  private final ReplicationType replicationType;
  private long lastStatusUpdate;
  private boolean closeFlag;

  private LogSequenceNumber lastServerLSN = LogSequenceNumber.INVALID_LSN;
  /**
   * Last receive LSN + payload size.
   */
  private volatile LogSequenceNumber lastReceiveLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber lastAppliedLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber lastFlushedLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber startOfLastMessageLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber explicitlyFlushedLSN = LogSequenceNumber.INVALID_LSN;

  /**
   * @param copyDual         bidirectional copy protocol
   * @param startLSN         the position in the WAL that we want to initiate replication from
   *                         usually the currentLSN returned by calling pg_current_wal_lsn()for v10
   *                         above or pg_current_xlog_location() depending on the version of the
   *                         server
   * @param updateIntervalMs the number of millisecond between status packets sent back to the
   *                         server.  A value of zero disables the periodic status updates
   *                         completely, although an update will still be sent when requested by the
   *                         server, to avoid timeout disconnect.
   * @param replicationType  LOGICAL or PHYSICAL
   */
  public V3PGReplicationStream(CopyDual copyDual, LogSequenceNumber startLSN, long updateIntervalMs,
      ReplicationType replicationType
  ) {
    this.copyDual = copyDual;
    this.updateInterval = updateIntervalMs * NANOS_PER_MILLISECOND;
    this.lastStatusUpdate = System.nanoTime() - (updateIntervalMs * NANOS_PER_MILLISECOND);
    this.lastReceiveLSN = startLSN;
    this.replicationType = replicationType;
  }

  @Override
  public @Nullable ByteBuffer read() throws SQLException {
    checkClose();

    ByteBuffer payload = null;
    while (payload == null && copyDual.isActive()) {
      payload = readInternal(true);
    }

    return payload;
  }

  @Override
  public @Nullable ByteBuffer readPending() throws SQLException {
    checkClose();
    return readInternal(false);
  }

  @Override
  public LogSequenceNumber getLastReceiveLSN() {
    return lastReceiveLSN;
  }

  @Override
  public LogSequenceNumber getLastFlushedLSN() {
    return lastFlushedLSN;
  }

  @Override
  public LogSequenceNumber getLastAppliedLSN() {
    return lastAppliedLSN;
  }

  @Override
  public void setFlushedLSN(LogSequenceNumber flushed) {
    this.lastFlushedLSN = flushed;
  }

  @Override
  public void setAppliedLSN(LogSequenceNumber applied) {
    this.lastAppliedLSN = applied;
  }

  @Override
  public void forceUpdateStatus() throws SQLException {
    checkClose();
    updateStatusInternal(lastReceiveLSN, lastFlushedLSN, lastAppliedLSN, true);
  }

  @Override
  public boolean isClosed() {
    return closeFlag || !copyDual.isActive();
  }

  private @Nullable ByteBuffer readInternal(boolean block) throws SQLException {
    boolean updateStatusRequired = false;
    while (copyDual.isActive()) {

      ByteBuffer buffer = receiveNextData(block);

      if (updateStatusRequired || isTimeUpdate()) {
        timeUpdateStatus();
      }

      if (buffer == null) {
        return null;
      }

      int code = buffer.get();

      switch (code) {

        case 'k': //KeepAlive message
          updateStatusRequired = processKeepAliveMessage(buffer);
          updateStatusRequired |= updateInterval == 0;
          break;

        case 'w': //XLogData
          return processXLogData(buffer);

        default:
          throw new PSQLException(
              GT.tr("Unexpected packet type during replication: {0}", Integer.toString(code)),
              PSQLState.PROTOCOL_VIOLATION
          );
      }
    }

    return null;
  }

  private @Nullable ByteBuffer receiveNextData(boolean block) throws SQLException {
    try {
      byte[] message = copyDual.readFromCopy(block);
      if (message != null) {
        return ByteBuffer.wrap(message);
      } else {
        return null;
      }
    } catch (PSQLException e) { //todo maybe replace on thread sleep?
      if (e.getCause() instanceof SocketTimeoutException) {
        //signal for keep alive
        return null;
      }

      throw e;
    }
  }

  private boolean isTimeUpdate() {
    /* a value of 0 disables automatic updates */
    if ( updateInterval == 0 ) {
      return false;
    }
    long diff = System.nanoTime() - lastStatusUpdate;
    return diff >= updateInterval;
  }

  private void timeUpdateStatus() throws SQLException {
    updateStatusInternal(lastReceiveLSN, lastFlushedLSN, lastAppliedLSN, false);
  }

  private void updateStatusInternal(
      LogSequenceNumber received, LogSequenceNumber flushed, LogSequenceNumber applied,
      boolean replyRequired)
      throws SQLException {
    byte[] reply = prepareUpdateStatus(received, flushed, applied, replyRequired);
    copyDual.writeToCopy(reply, 0, reply.length);
    copyDual.flushCopy();

    explicitlyFlushedLSN = flushed;
    lastStatusUpdate = System.nanoTime();
  }

  private byte[] prepareUpdateStatus(LogSequenceNumber received, LogSequenceNumber flushed,
      LogSequenceNumber applied, boolean replyRequired) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(1 + 8 + 8 + 8 + 8 + 1);

    long now = System.nanoTime() / NANOS_PER_MILLISECOND;
    long systemClock = TimeUnit.MICROSECONDS.convert((now - POSTGRES_EPOCH_2000_01_01),
        TimeUnit.MICROSECONDS);

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " FE=> StandbyStatusUpdate(received: {0}, flushed: {1}, applied: {2}, clock: {3})",
          new Object[]{received.asString(), flushed.asString(), applied.asString(), new Date(now)});
    }

    byteBuffer.put((byte) 'r');
    byteBuffer.putLong(received.asLong());
    byteBuffer.putLong(flushed.asLong());
    byteBuffer.putLong(applied.asLong());
    byteBuffer.putLong(systemClock);
    if (replyRequired) {
      byteBuffer.put((byte) 1);
    } else {
      byteBuffer.put(received == LogSequenceNumber.INVALID_LSN ? (byte) 1 : (byte) 0);
    }

    lastStatusUpdate = now;
    return byteBuffer.array();
  }

  private boolean processKeepAliveMessage(ByteBuffer buffer) {
    lastServerLSN = LogSequenceNumber.valueOf(buffer.getLong());
    if (lastServerLSN.asLong() > lastReceiveLSN.asLong()) {
      lastReceiveLSN = lastServerLSN;
    }
    // if the client has confirmed flush of last XLogData msg and KeepAlive shows ServerLSN is still
    // advancing, we can safely advance FlushLSN to ServerLSN
    if (explicitlyFlushedLSN.asLong() >= startOfLastMessageLSN.asLong()
        && lastServerLSN.asLong() > explicitlyFlushedLSN.asLong()
        && lastServerLSN.asLong() > lastFlushedLSN.asLong()) {
      lastFlushedLSN = lastServerLSN;
    }

    long lastServerClock = buffer.getLong();

    boolean replyRequired = buffer.get() != 0;

    if (LOGGER.isLoggable(Level.FINEST)) {
      Date clockTime = new Date(
          TimeUnit.MILLISECONDS.convert(lastServerClock, TimeUnit.MICROSECONDS)
          + POSTGRES_EPOCH_2000_01_01);
      LOGGER.log(Level.FINEST, "  <=BE Keepalive(lastServerWal: {0}, clock: {1} needReply: {2})",
          new Object[]{lastServerLSN.asString(), clockTime, replyRequired});
    }

    return replyRequired;
  }

  private ByteBuffer processXLogData(ByteBuffer buffer) {
    long startLsn = buffer.getLong();
    startOfLastMessageLSN = LogSequenceNumber.valueOf(startLsn);
    lastServerLSN = LogSequenceNumber.valueOf(buffer.getLong());
    long systemClock = buffer.getLong();

    if (replicationType == ReplicationType.LOGICAL) {
      lastReceiveLSN = LogSequenceNumber.valueOf(startLsn);
    } else if (replicationType == ReplicationType.PHYSICAL) {
      int payloadSize = buffer.limit() - buffer.position();
      lastReceiveLSN = LogSequenceNumber.valueOf(startLsn + payloadSize);
    }

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, "  <=BE XLogData(currWal: {0}, lastServerWal: {1}, clock: {2})",
          new Object[]{lastReceiveLSN.asString(), lastServerLSN.asString(), systemClock});
    }

    return buffer.slice();
  }

  private void checkClose() throws PSQLException {
    if (isClosed()) {
      throw new PSQLException(GT.tr("This replication stream has been closed."),
          PSQLState.CONNECTION_DOES_NOT_EXIST);
    }
  }

  @Override
  public void close() throws SQLException {
    if (isClosed()) {
      return;
    }

    LOGGER.log(Level.FINEST, " FE=> StopReplication");

    copyDual.endCopy();

    closeFlag = true;
  }
}
