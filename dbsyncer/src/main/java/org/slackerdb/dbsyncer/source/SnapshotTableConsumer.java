package org.slackerdb.dbsyncer.source;

import java.util.List;
import java.util.Map;

/**
 * resync 时在同一源事务内逐表读取快照的回调。
 *
 * <p>只暴露 {@link TableMappingRef}：数据源不需要知道目标表结构，目标映射由调用方解析。</p>
 */
public interface SnapshotTableConsumer {

    /** 开始一张表；抛异常表示该表不参与本次快照。 */
    void begin(TableMappingRef table) throws Exception;

    void rows(TableMappingRef table, List<Map<String, Object>> rows, long totalRows) throws Exception;

    void end(TableMappingRef table, long totalRows) throws Exception;
}
