package org.slackerdb.common.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU缓存实现，用于缓存SQL替换结果
 * 使用LinkedHashMap实现LRU淘汰策略，直接使用SQL字符串作为键避免哈希冲突
 */
public class LRUCache {
    static class LRULinkedHashMap<K, V> extends LinkedHashMap<K, V> {
        private final int capacity;

        public LRULinkedHashMap(int capacity) {
            super(capacity, 0.75f, true);  // 'true' means order by access order
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;  // Remove eldest when size exceeds capacity
        }
    }

    private final LRULinkedHashMap<String, String> lruLinkedHashMap = new LRULinkedHashMap<>(1000);

    /**
     * 根据原始SQL获取替换后的SQL
     * @param sourceSql 原始SQL
     * @return 替换后的SQL，如果缓存中不存在则返回null
     */
    public String getReplacedSql(String sourceSql)
    {
        return lruLinkedHashMap.getOrDefault(sourceSql, null);
    }

    /**
     * 将原始SQL和替换后的SQL存入缓存
     * @param sourceSQL 原始SQL
     * @param replacedSql 替换后的SQL
     */
    public void put(String sourceSQL, String replacedSql)
    {
        lruLinkedHashMap.put(sourceSQL, replacedSql);
    }

}

