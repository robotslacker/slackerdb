/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A class that can be used to set a byte array parameter by writing to an OutputStream.
 *
 * <p>The intended use case is wanting to write data to a byte array parameter that is stored off
 * heap in a direct memory pool or in some other form that is inconvenient to assemble into a single
 * heap-allocated buffer.</p>
 *
 * <p>Users should write their own implementation depending on the
 * original data source. The driver provides a built-in implementation supporting the {@link
 * java.nio.ByteBuffer} class, see {@link ByteBufferByteStreamWriter}.</p>
 *
 * <p>Intended usage is to simply pass in an instance using
 * {@link java.sql.PreparedStatement#setObject(int, Object)}:</p>
 * <pre>
 *     int bufLength = someBufferObject.length();
 *     preparedStatement.setObject(1, new MyByteStreamWriter(bufLength, someBufferObject));
 * </pre>
 *
 * <p>The length must be known ahead of the stream being written to. </p>
 *
 * <p>This provides the application more control over memory management than calling
 * {@link java.sql.PreparedStatement#setBinaryStream(int, InputStream)} as with the latter the
 * caller has no control over the buffering strategy. </p>
 */
public interface ByteStreamWriter {

  /**
   * Returns the length of the stream.
   *
   * <p>This must be known ahead of calling {@link #writeTo(ByteStreamTarget)}. </p>
   *
   * @return the number of bytes in the stream.
   */
  int getLength();

  /**
   * Write the data to the provided {@link OutputStream}.
   *
   * <p>Should not write more than {@link #getLength()} bytes. If attempted, the provided stream
   * will throw an {@link java.io.IOException}. </p>
   *
   * @param target the stream to write the data to
   * @throws IOException if the underlying stream throws or there is some other error.
   */
  void writeTo(ByteStreamTarget target) throws IOException;

  static ByteStreamWriter of(ByteBuffer... buf) {
    return buf.length == 1
        ? new ByteBufferByteStreamWriter(buf[0])
        : new ByteBuffersByteStreamWriter(buf);
  }

  /**
   * Provides a target to write bytes to.
   */
  interface ByteStreamTarget {

    /**
     * Provides an output stream to write bytes to.
     *
     * @return an output stream
     */
    OutputStream getOutputStream();
  }
}
