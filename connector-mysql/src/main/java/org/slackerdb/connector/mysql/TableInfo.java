package org.slackerdb.connector.mysql;

import org.duckdb.DuckDBAppender;

import java.util.List;

// 缓存需要同步的元数据信息
// 即使不需要同步，这里也会存在，但是target为空
public class TableInfo {
    String          sourceSchemaName;
    String          sourceTableName;
    String          targetSchemaName;
    String          targetTableName;
    String          sourceTableDDL;
    String          targetTableDDL;
    String          targetTemporaryTableDDL;
    long            sourceTableId;
    List<String>    sourceTableColumns;
    List<String>    sourceTableColumnTypes;
    // 哪个任务负责同步该表
    String          syncTaskName;
    // 表的主键字段名称
    List<String>    sourceTablePrimaryKeyColumns;
    // DuckDB批量插入
    DuckDBAppender  duckDBAppender = null;
    boolean         fullSyncStatus = false;
}
