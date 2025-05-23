/*
 * Copyright (c) 2012, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.util;

import static java.util.regex.Pattern.compile;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple container for host and port.
 */
public class HostSpec {
  public static final String DEFAULT_NON_PROXY_HOSTS = "localhost|127.*|[::1]|0.0.0.0|[::0]";

  protected final @Nullable String localSocketAddress;
  protected final String host;
  protected final int port;

  public HostSpec(String host, int port) {
    this(host, port, null);
  }

  public HostSpec(String host, int port, @Nullable String localSocketAddress) {
    this.host = host;
    this.port = port;
    this.localSocketAddress = localSocketAddress;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  @Override
  public String toString() {
    return host + ":" + port;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return obj instanceof HostSpec && port == ((HostSpec) obj).port
        && host.equals(((HostSpec) obj).host) && Objects.equals(localSocketAddress, ((HostSpec) obj).localSocketAddress);
  }

  @Override
  public int hashCode() {
    return Objects.hash(localSocketAddress, host, port);
  }

  public @Nullable String getLocalSocketAddress() {
    return localSocketAddress;
  }

  public Boolean shouldResolve() {
    String socksProxy = System.getProperty("socksProxyHost");
    if (socksProxy == null || socksProxy.trim().isEmpty()) {
      return true;
    }
    return matchesNonProxyHosts();
  }

  private Boolean matchesNonProxyHosts() {
    String nonProxyHosts = System.getProperty("socksNonProxyHosts", DEFAULT_NON_PROXY_HOSTS);
    if (nonProxyHosts == null || this.host.isEmpty()) {
      return false;
    }

    Pattern pattern = toPattern(nonProxyHosts);
    Matcher matcher = pattern == null ? null : pattern.matcher(this.host);
    return matcher != null && matcher.matches();
  }

  @SuppressWarnings("regex")
  private @Nullable Pattern toPattern(String mask) {
    StringBuilder joiner = new StringBuilder();
    String separator = "";
    for (String disjunct : mask.split("\\|")) {
      if (!disjunct.isEmpty()) {
        String regex = disjunctToRegex(disjunct.toLowerCase(Locale.ROOT));
        joiner.append(separator).append(regex);
        separator = "|";
      }
    }

    return joiner.length() == 0 ? null : compile(joiner.toString());
  }

  private String disjunctToRegex(String disjunct) {
    String regex;

    if (disjunct.startsWith("*")) {
      regex = ".*" + Pattern.quote(disjunct.substring(1));
    } else if (disjunct.endsWith("*")) {
      regex = Pattern.quote(disjunct.substring(0, disjunct.length() - 1)) + ".*";
    } else {
      regex = Pattern.quote(disjunct);
    }

    return regex;
  }
}
