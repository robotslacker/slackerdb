package org.slackerdb.connector.mysql;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class RowEventData {
    // 对于DDL事件，其tableId可能为0
    long            tableId;

    String          eventType;
    String          eventSql;
    String          sourceSchemaName;
    String          sourceTableName;
    String          targetSchemaName;
    String          targetTableName;

    // 计划被删除的内容，或者被插入的内容
    List<Serializable[]> rows;
    // 被更新的内容，包括原值和目标值
    List<Map.Entry<Serializable[], Serializable[]>> updateRows;

    // 记录当前事件的binlog位置
    String          binlogFileName;
    long            binLogPosition;
    long            binlogTimestamp;
}
