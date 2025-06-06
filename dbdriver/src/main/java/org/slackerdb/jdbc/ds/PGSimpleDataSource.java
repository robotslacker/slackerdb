/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.ds;

import org.slackerdb.jdbc.ds.common.BaseDataSource;
import org.slackerdb.jdbc.util.DriverInfo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Simple DataSource which does not perform connection pooling. In order to use the DataSource, you
 * must set the property databaseName. The settings for serverName, portNumber, user, and password
 * are optional. Note: these properties are declared in the superclass.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PGSimpleDataSource extends BaseDataSource implements DataSource, Serializable {
  /**
   * Gets a description of this DataSource.
   */
  @Override
  public String getDescription() {
    return "Non-Pooling DataSource from " + DriverInfo.DRIVER_FULL_NAME;
  }

  private void writeObject(ObjectOutputStream out) throws IOException {
    writeBaseObject(out);
  }

  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    readBaseObject(in);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }
}
