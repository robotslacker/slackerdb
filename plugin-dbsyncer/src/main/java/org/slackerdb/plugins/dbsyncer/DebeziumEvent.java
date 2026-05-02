package org.slackerdb.plugins.dbsyncer;

public class DebeziumEvent {
    private String value; // 存放 Debezium 的原始 JSON 字符串
    private String syncerName; // 同步器名称

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getSyncerName() { return syncerName; }
    public void setSyncerName(String syncerName) { this.syncerName = syncerName; }
}
