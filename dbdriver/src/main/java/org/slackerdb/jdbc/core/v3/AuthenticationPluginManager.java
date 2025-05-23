/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.core.v3;

import org.slackerdb.jdbc.PGProperty;
import org.slackerdb.jdbc.plugin.AuthenticationPlugin;
import org.slackerdb.jdbc.plugin.AuthenticationRequestType;
import org.slackerdb.jdbc.util.GT;
import org.slackerdb.jdbc.util.ObjectFactory;
import org.slackerdb.jdbc.util.PSQLException;
import org.slackerdb.jdbc.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

class AuthenticationPluginManager {
  private static final Logger LOGGER = Logger.getLogger(AuthenticationPluginManager.class.getName());

  @FunctionalInterface
  public interface PasswordAction<T, R> {
    R apply(T password) throws PSQLException, IOException;
  }

  private AuthenticationPluginManager() {
  }

  /**
   * If a password is requested by the server during connection initiation, this
   * method will be invoked to supply the password. This method will only be
   * invoked if the server actually requests a password, e.g. trust authentication
   * will skip it entirely.
   *
   * <p>The caller provides a action method that will be invoked with the {@code char[]}
   * password. After completion, for security reasons the {@code char[]} array will be
   * wiped by filling it with zeroes. Callers must not rely on being able to read
   * the password {@code char[]} after the action has completed.</p>
   *
   * @param type The authentication type that is being requested
   * @param info The connection properties for the connection
   * @param action The action to invoke with the password
   * @throws PSQLException Throws a PSQLException if the plugin class cannot be instantiated
   * @throws IOException Bubbles up any thrown IOException from the provided action
   */
  public static <T> T withPassword(AuthenticationRequestType type, Properties info,
      PasswordAction<char @Nullable [], T> action) throws PSQLException, IOException {
    char[] password = null;

    String authPluginClassName = PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getOrDefault(info);

    if (authPluginClassName == null || "".equals(authPluginClassName)) {
      // Default auth plugin simply pulls password directly from connection properties
      String passwordText = PGProperty.PASSWORD.getOrDefault(info);
      if (passwordText != null) {
        password = passwordText.toCharArray();
      }
    } else {
      AuthenticationPlugin authPlugin;
      try {
        authPlugin = ObjectFactory.instantiate(AuthenticationPlugin.class, authPluginClassName, info,
            false, null);
      } catch (Exception ex) {
        String msg = GT.tr("Unable to load Authentication Plugin {0}", authPluginClassName);
        LOGGER.log(Level.FINE, msg, ex);
        throw new PSQLException(msg, PSQLState.INVALID_PARAMETER_VALUE, ex);
      }

      password = authPlugin.getPassword(type);
    }

    try {
      return action.apply(password);
    } finally {
      if (password != null) {
        Arrays.fill(password, (char) 0);
      }
    }
  }

  /**
   * Helper that wraps {@link #withPassword(AuthenticationRequestType, Properties, PasswordAction)}, checks that it is not-null, and encodes
   * it as a byte array. Used by internal code paths that require an encoded password
   * that may be an empty string, but not null.
   *
   * <p>The caller provides a callback method that will be invoked with the {@code byte[]}
   * encoded password. After completion, for security reasons the {@code byte[]} array will be
   * wiped by filling it with zeroes. Callers must not rely on being able to read
   * the password {@code byte[]} after the callback has completed.</p>

   * @param type The authentication type that is being requested
   * @param info The connection properties for the connection
   * @param action The action to invoke with the encoded password
   * @throws PSQLException Throws a PSQLException if the plugin class cannot be instantiated or if the retrieved password is null.
   * @throws IOException Bubbles up any thrown IOException from the provided callback
   */
  public static <T> T withEncodedPassword(AuthenticationRequestType type, Properties info,
      PasswordAction<byte[], T> action) throws PSQLException, IOException {
    // Checkerframework infers `nullable byte[]` for the return type for unknown reason
    @SuppressWarnings("RedundantTypeArguments")
    byte [] encodedPassword = AuthenticationPluginManager.<byte[]>withPassword(type, info, password -> {
      if (password == null) {
        throw new PSQLException(
            GT.tr("The server requested password-based authentication, but no password was provided by plugin {0}",
                PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getOrDefault(info)),
            PSQLState.CONNECTION_REJECTED);
      }
      ByteBuffer buf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
      byte[] bytes = new byte[buf.limit()];
      buf.get(bytes);
      return bytes;
    });

    try {
      return action.apply(encodedPassword);
    } finally {
      Arrays.fill(encodedPassword, (byte) 0);
    }
  }
}
