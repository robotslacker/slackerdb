package org.slackerdb.dbsyncer.target;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 宿主提供的 DuckDB 入口：dbserver 用 backendSysConnection.duplicate() 实现。
 */
public interface DuckDbHost {

    Connection duplicate() throws SQLException;

    /** 实例库 catalog 名（没有 --data 时是 memory）。 */
    String catalog();
}
