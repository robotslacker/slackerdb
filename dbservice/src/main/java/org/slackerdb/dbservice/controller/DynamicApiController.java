package org.slackerdb.dbservice.controller;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Function;

@RestController
public class DynamicApiController {

    @Autowired
    private DynamicApiRegistry registry;

    @PostMapping("/register")
    public String registerApi(@RequestBody JSONObject req) {
        String path = req.getString("path");
        String sql = req.getString("sql");  // 模拟用途

        registry.register(path, input -> {
            // 实际中可执行 SQL 逻辑，这里模拟返回
            JSONObject result = new JSONObject();
            result.put("executedSql", sql);
            result.put("input", input);
            return result;
        });

        return "Registered API: " + path;
    }

    @PostMapping("/api/**")
    public ResponseEntity<String> handleDynamicApi(HttpServletRequest request,
                                                   @RequestBody(required = false) String body) {
        String path = request.getRequestURI().substring("/api".length());  // like //hello
        Function<JSONObject, JSONObject> handler = registry.getHandler(path);
        if (handler == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("API not registered");
        }

        JSONObject input = (body != null && !body.isEmpty()) ? new JSONObject(Integer.parseInt(body)) : new JSONObject();
        JSONObject output = handler.apply(input);
        return ResponseEntity.ok(output.toString());
    }
}
