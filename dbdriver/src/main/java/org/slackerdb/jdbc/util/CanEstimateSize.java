/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.util;

public interface CanEstimateSize {
  long getSize();
}
