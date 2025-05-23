/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.util;

import java.io.File;
import java.util.Locale;

/**
 * Operating system specifics
 */
public class OSUtil {

  /**
   *
   * @return true if OS is windows
   */
  public static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  /**
   *
   * @return OS specific root directory for user specific configurations
   */
  public static String getUserConfigRootDirectory() {
    if (isWindows()) {
      return System.getenv("APPDATA") + File.separator + "postgresql";
    } else {
      return System.getProperty("user.home");
    }
  }

}
