package org.slackerdb.plugins.dbsyncer;

import java.util.List;
import java.util.ArrayList;

public class StartEngineRequest
{
    String syncer_type;
    String syncer_name;
    String rule_name;
    String rule_source;
    String source_database;
    String target_database;
    String source_table; // 原rule_pattern，实际上是source_pattern
    String target_table;
    boolean auto;
    String syncer_offset;
    String snapshot_mode;
    String status;

    // 存储同一个syncer的所有规则
    List<SyncerRuleInfo> rules = new ArrayList<>();
};
