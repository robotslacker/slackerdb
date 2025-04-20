/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.hostchooser;

import org.slackerdb.jdbc.util.HostSpec;

/**
 * Candidate host to be connected.
 */
public class CandidateHost {
  public final HostSpec hostSpec;
  public final HostRequirement targetServerType;

  public CandidateHost(HostSpec hostSpec, HostRequirement targetServerType) {
    this.hostSpec = hostSpec;
    this.targetServerType = targetServerType;
  }
}
