/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.slackerdb.jdbc.core;

import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.PGbytea;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Collection of utilities used by the protocol-level code.
 */
public class Utils {
  /**
   * Turn a bytearray into a printable form, representing each byte in hex.
   *
   * @param data the bytearray to stringize
   * @return a hex-encoded printable representation of {@code data}
   */
  public static String toHexString(byte[] data) {
    StringBuilder sb = new StringBuilder(data.length * 2);
    PGbytea.appendHexString(sb, data, 0, data.length);
    return sb.toString();
  }

  /**
   * Escape the given literal {@code value} and append it to the string builder {@code sbuf}. If
   * {@code sbuf} is {@code null}, a new StringBuilder will be returned. The argument
   * {@code standardConformingStrings} defines whether the backend expects standard-conforming
   * string literals or allows backslash escape sequences.
   *
   * @param sbuf the string builder to append to; or {@code null}
   * @param value the string value
   * @param standardConformingStrings if standard conforming strings should be used
   * @return the sbuf argument; or a new string builder for sbuf == null
   * @throws SQLException if the string contains a {@code \0} character
   */
  public static StringBuilder escapeLiteral(@Nullable StringBuilder sbuf, String value,
      boolean standardConformingStrings) throws SQLException {
    if (sbuf == null) {
      sbuf = new StringBuilder((value.length() + 10) / 10 * 11); // Add 10% for escaping.
    }
    doAppendEscapedLiteral(sbuf, value, standardConformingStrings);
    return sbuf;
  }

  /**
   * Common part for {@link #escapeLiteral(StringBuilder, String, boolean)}.
   *
   * @param sbuf Either StringBuffer or StringBuilder as we do not expect any IOException to be
   *        thrown
   * @param value value to append
   * @param standardConformingStrings if standard conforming strings should be used
   */
  private static void doAppendEscapedLiteral(Appendable sbuf, String value,
      boolean standardConformingStrings) throws SQLException {
    try {
      if (standardConformingStrings) {
        // With standard_conforming_strings on, escape only single-quotes.
        for (int i = 0; i < value.length(); i++) {
          char ch = value.charAt(i);
          if (ch == '\0') {
            throw new PSQLException(GT.tr("Zero bytes may not occur in string parameters."),
                PSQLState.INVALID_PARAMETER_VALUE);
          }
          if (ch == '\'') {
            sbuf.append('\'');
          }
          sbuf.append(ch);
        }
      } else {
        // With standard_conforming_string off, escape backslashes and
        // single-quotes, but still escape single-quotes by doubling, to
        // avoid a security hazard if the reported value of
        // standard_conforming_strings is incorrect, or an error if
        // backslash_quote is off.
        for (int i = 0; i < value.length(); i++) {
          char ch = value.charAt(i);
          if (ch == '\0') {
            throw new PSQLException(GT.tr("Zero bytes may not occur in string parameters."),
                PSQLState.INVALID_PARAMETER_VALUE);
          }
          if (ch == '\\' || ch == '\'') {
            sbuf.append(ch);
          }
          sbuf.append(ch);
        }
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("No IOException expected from StringBuffer or StringBuilder"),
          PSQLState.UNEXPECTED_ERROR, e);
    }
  }

  /**
   * Escape the given identifier {@code value} and append it to the string builder {@code sbuf}.
   * If {@code sbuf} is {@code null}, a new StringBuilder will be returned. This method is
   * different from appendEscapedLiteral in that it includes the quoting required for the identifier
   * while {@link #escapeLiteral(StringBuilder, String, boolean)} does not.
   *
   * @param sbuf the string builder to append to; or {@code null}
   * @param value the string value
   * @return the sbuf argument; or a new string builder for sbuf == null
   * @throws SQLException if the string contains a {@code \0} character
   */
  public static StringBuilder escapeIdentifier(@Nullable StringBuilder sbuf, String value)
      throws SQLException {
    if (sbuf == null) {
      sbuf = new StringBuilder(2 + (value.length() + 10) / 10 * 11); // Add 10% for escaping.
    }
    doAppendEscapedIdentifier(sbuf, value);
    return sbuf;
  }

  /**
   * Common part for appendEscapedIdentifier.
   *
   * @param sbuf Either StringBuffer or StringBuilder as we do not expect any IOException to be
   *        thrown.
   * @param value value to append
   */
  private static void doAppendEscapedIdentifier(Appendable sbuf, String value) throws SQLException {
    try {
      sbuf.append('"');

      for (int i = 0; i < value.length(); i++) {
        char ch = value.charAt(i);
        if (ch == '\0') {
          throw new PSQLException(GT.tr("Zero bytes may not occur in identifiers."),
              PSQLState.INVALID_PARAMETER_VALUE);
        }
        if (ch == '"') {
          sbuf.append(ch);
        }
        sbuf.append(ch);
      }

      sbuf.append('"');
    } catch (IOException e) {
      throw new PSQLException(GT.tr("No IOException expected from StringBuffer or StringBuilder"),
          PSQLState.UNEXPECTED_ERROR, e);
    }
  }

  /**
   * Attempt to parse the server version string into an XXYYZZ form version number.
   *
   * <p>Returns 0 if the version could not be parsed.</p>
   *
   * <p>Returns minor version 0 if the minor version could not be determined, e.g. devel or beta
   * releases.</p>
   *
   * <p>If a single major part like 90400 is passed, it's assumed to be a pre-parsed version and
   * returned verbatim. (Anything equal to or greater than 10000 is presumed to be this form).</p>
   *
   * <p>The yy or zz version parts may be larger than 99. A NumberFormatException is thrown if a
   * version part is out of range.</p>
   *
   * @param serverVersion server version in a XXYYZZ form
   * @return server version in number form
   * @deprecated use specific {@link Version} instance
   */
  @Deprecated
  public static int parseServerVersionStr(@Nullable String serverVersion) throws NumberFormatException {
    return ServerVersion.parseServerVersionStr(serverVersion);
  }
}
