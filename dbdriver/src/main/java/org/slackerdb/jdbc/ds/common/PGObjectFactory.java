/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.ds.common;

import org.slackerdb.jdbc.ds.PGConnectionPoolDataSource;
import org.slackerdb.jdbc.ds.PGPoolingDataSource;
import org.slackerdb.jdbc.ds.PGSimpleDataSource;
import org.slackerdb.jdbc.util.internal.Nullness;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;

/**
 * Returns a DataSource-ish thing based on a JNDI reference. In the case of a SimpleDataSource or
 * ConnectionPool, a new instance is created each time, as there is no connection state to maintain.
 * In the case of a PoolingDataSource, the same DataSource will be returned for every invocation
 * within the same VM/ClassLoader, so that the state of the connections in the pool will be
 * consistent.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PGObjectFactory implements ObjectFactory {
  /**
   * Dereferences a PostgreSQL DataSource. Other types of references are ignored.
   */
  @Override
  public @Nullable Object getObjectInstance(Object obj, Name name, Context nameCtx,
      Hashtable<?, ?> environment) throws Exception {
    Reference ref = (Reference) obj;
    String className = ref.getClassName();
    // Old names are here for those who still use them
    if ("org.postgresql.ds.PGSimpleDataSource".equals(className)
        || "org.postgresql.jdbc2.optional.SimpleDataSource".equals(className)
        || "org.postgresql.jdbc3.Jdbc3SimpleDataSource".equals(className)) {
      return loadSimpleDataSource(ref);
    } else if ("org.postgresql.ds.PGConnectionPoolDataSource".equals(className)
        || "org.postgresql.jdbc2.optional.ConnectionPool".equals(className)
        || "org.postgresql.jdbc3.Jdbc3ConnectionPool".equals(className)) {
      return loadConnectionPool(ref);
    } else if ("org.postgresql.ds.PGPoolingDataSource".equals(className)
        || "org.postgresql.jdbc2.optional.PoolingDataSource".equals(className)
        || "org.postgresql.jdbc3.Jdbc3PoolingDataSource".equals(className)) {
      return loadPoolingDataSource(ref);
    } else {
      return null;
    }
  }

  private Object loadPoolingDataSource(Reference ref) {
    // If DataSource exists, return it
    String name = Nullness.castNonNull(getProperty(ref, "dataSourceName"));
    PGPoolingDataSource pds = PGPoolingDataSource.getDataSource(name);
    if (pds != null) {
      return pds;
    }
    // Otherwise, create a new one
    pds = new PGPoolingDataSource();
    pds.setDataSourceName(name);
    loadBaseDataSource(pds, ref);
    String min = getProperty(ref, "initialConnections");
    if (min != null) {
      pds.setInitialConnections(Integer.parseInt(min));
    }
    String max = getProperty(ref, "maxConnections");
    if (max != null) {
      pds.setMaxConnections(Integer.parseInt(max));
    }
    return pds;
  }

  private Object loadSimpleDataSource(Reference ref) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    return loadBaseDataSource(ds, ref);
  }

  private Object loadConnectionPool(Reference ref) {
    PGConnectionPoolDataSource cp = new PGConnectionPoolDataSource();
    return loadBaseDataSource(cp, ref);
  }

  protected Object loadBaseDataSource(BaseDataSource ds, Reference ref) {
    ds.setFromReference(ref);

    return ds;
  }

  protected @Nullable String getProperty(Reference ref, String s) {
    RefAddr addr = ref.get(s);
    if (addr == null) {
      return null;
    }
    return (String) addr.getContent();
  }

}
