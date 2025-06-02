package org.slackerdb.dbservice.controller;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
public class DynamicApiRegistry {
    private final Map<String, Function<JSONObject, JSONObject>> routeMap = new ConcurrentHashMap<>();

    public void register(String path, Function<JSONObject, JSONObject> handler) {
        routeMap.put(path, handler);
    }

    public Function<JSONObject, JSONObject> getHandler(String path) {
        return routeMap.get(path);
    }

    public Set<String> getAllRoutes() {
        return routeMap.keySet();
    }
}

