/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.fastpath;

import org.slackerdb.jdbc.core.ParameterList;
import org.slackerdb.jdbc.util.ByteStreamWriter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

// Not a very clean mapping to the new QueryExecutor/ParameterList
// stuff, but it seems hard to support both v2 and v3 cleanly with
// the same model while retaining API compatibility. So I've just
// done it the ugly way..

/**
 * Each fastpath call requires an array of arguments, the number and type dependent on the function
 * being called.
 *
 * @deprecated This API is somewhat obsolete, as one may achieve similar performance
 *         and greater functionality by setting up a prepared statement to define
 *         the function call. Then, executing the statement with binary transmission of parameters
 *         and results substitutes for a fast-path function call.
 */
@Deprecated
public class FastpathArg {
  /**
   * Encoded byte value of argument.
   */
  private final byte @Nullable [] bytes;
  private final int bytesStart;
  private final int bytesLength;

  static class ByteStreamWriterFastpathArg extends FastpathArg {
    private final ByteStreamWriter writer;

    ByteStreamWriterFastpathArg(ByteStreamWriter writer) {
      super(null, 0, 0);
      this.writer = writer;
    }

    @Override
    void populateParameter(ParameterList params, int index) throws SQLException {
      params.setBytea(index, writer);
    }
  }

  /**
   * Constructs an argument that consists of an integer value.
   *
   * @param value int value to set
   */
  public FastpathArg(int value) {
    bytes = new byte[4];
    bytes[3] = (byte) (value);
    bytes[2] = (byte) (value >> 8);
    bytes[1] = (byte) (value >> 16);
    bytes[0] = (byte) (value >> 24);
    bytesStart = 0;
    bytesLength = 4;
  }

  /**
   * Constructs an argument that consists of an integer value.
   *
   * @param value int value to set
   */
  public FastpathArg(long value) {
    bytes = new byte[8];
    bytes[7] = (byte) (value);
    bytes[6] = (byte) (value >> 8);
    bytes[5] = (byte) (value >> 16);
    bytes[4] = (byte) (value >> 24);
    bytes[3] = (byte) (value >> 32);
    bytes[2] = (byte) (value >> 40);
    bytes[1] = (byte) (value >> 48);
    bytes[0] = (byte) (value >> 56);
    bytesStart = 0;
    bytesLength = 8;
  }

  /**
   * Constructs an argument that consists of an array of bytes.
   *
   * @param bytes array to store
   */
  public FastpathArg(byte[] bytes) {
    this(bytes, 0, bytes.length);
  }

  /**
   * Constructs an argument that consists of part of a byte array.
   *
   * @param buf source array
   * @param off offset within array
   * @param len length of data to include
   */
  public FastpathArg(byte @Nullable [] buf, int off, int len) {
    this.bytes = buf;
    this.bytesStart = off;
    this.bytesLength = len;
  }

  /**
   * Constructs an argument that consists of a String.
   *
   * @param s String to store
   */
  public FastpathArg(String s) {
    this(s.getBytes());
  }

  public static FastpathArg of(ByteStreamWriter writer) {
    return new ByteStreamWriterFastpathArg(writer);
  }

  void populateParameter(ParameterList params, int index) throws SQLException {
    if (bytes == null) {
      params.setNull(index, 0);
    } else {
      params.setBytea(index, bytes, bytesStart, bytesLength);
    }
  }
}
