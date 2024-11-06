package org.slackerdb.utils;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private long fnv1aHash(String input) {
        final long FNV_PRIME = 0x100000001b3L;
        long hash = 0xcbf29ce484222325L;

        for (char c : input.toCharArray()) {
            hash ^= c;
            hash *= FNV_PRIME;
        }
        return hash;
    }


    private final LRULinkedHashMap<Long, String> lruLinkedHashMap = new LRULinkedHashMap<>(1000);

    public String getReplacedSql(String sourceSql)
    {
        return lruLinkedHashMap.getOrDefault(fnv1aHash(sourceSql), null);
    }

    public void put(String sourceSQL, String replacedSql)
    {
        lruLinkedHashMap.put(fnv1aHash(sourceSQL), replacedSql);
    }

}

