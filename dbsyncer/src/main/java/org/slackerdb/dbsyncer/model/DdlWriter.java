package org.slackerdb.dbsyncer.model;

import java.sql.SQLException;
import java.util.List;

/**
 * 目标侧 DDL 操作的最小接口。
 *
 * <p>{@code DuckDbWriter} 实现它；MySQL DDL 翻译器（{@code source.mysql.MySqlDdlApplier}）
 * 只依赖这个接口，因此 {@code source} 包不需要依赖 {@code target} 包。</p>
 */
public interface DdlWriter {

    void addColumn(TableMapping mapping, TargetTable.TargetColumn column) throws SQLException;

    void dropColumn(TableMapping mapping, String column) throws SQLException;

    void renameColumn(TableMapping mapping, String oldName, String newName) throws SQLException;

    void alterColumnType(TableMapping mapping, String column, String duckType) throws SQLException;

    void truncateTable(TableMapping mapping) throws SQLException;

    void dropTable(TableMapping mapping) throws SQLException;

    void renameTable(TableMapping mapping, TargetTable newTarget) throws SQLException;

    /** DDL 与检查点在同一目标事务内提交。 */
    void applyDdlTransaction(String jobName, TxPosition position, DdlAction action) throws SQLException;

    void prepare(List<TableMapping> mappings) throws SQLException;
}
