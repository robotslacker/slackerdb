package org.slackerdb.dbsyncer.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 作业级配置指纹：用于热加载 diff 与"配置变了但作业还在跑"的提示。
 *
 * <p>指纹包含作业本身及其引用的 connection/target，避免"只改连接串却认为没变化"。</p>
 */
public final class ConfigFingerprint {

    private ConfigFingerprint() {
    }

    public static String of(SyncConfig config, SyncConfig.JobConfig job) {
        JSONObject object = new JSONObject();
        object.put("job", job);
        object.put("connection", config.connections.get(job.source));
        object.put("target", config.targets.get(job.target));
        object.put("defaults", config.defaults);
        return sha256(JSON.toJSONString(object)).substring(0, 12);
    }

    public static String ofConfig(SyncConfig config) {
        return sha256(JSON.toJSONString(config));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(Character.forDigit((b >>> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
