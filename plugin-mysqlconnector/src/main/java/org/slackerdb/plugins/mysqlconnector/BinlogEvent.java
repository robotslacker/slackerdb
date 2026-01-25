package org.slackerdb.plugins.mysqlconnector;

public class BinlogEvent {
    private String value; // 存放 Debezium 的原始 JSON 字符串

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
