package org.slackerdb.dbserver.service;

import ch.qos.logback.classic.Logger;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slackerdb.dbserver.server.DBInstance;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PluginService 提供插件管理的HTTP API。
 * 支持插件的加载、卸载、启动、停止操作。
 */
public class PluginService {
    private final Logger logger;
    private final DBInstance dbInstance;

    public PluginService(
            DBInstance dbInstance,
            Javalin managementApp,
            Logger logger
    ) {
        this.logger = logger;
        this.dbInstance = dbInstance;

        // 注册插件管理路由
        managementApp.get("/plugin/list", this::handleListPlugins);
        managementApp.post("/plugin/load", this::handleLoadPlugin);
        managementApp.post("/plugin/unload", this::handleUnloadPlugin);
        managementApp.post("/plugin/start", this::handleStartPlugin);
        managementApp.post("/plugin/stop", this::handleStopPlugin);
    }

    /**
     * 处理加载插件请求。
     * 请求体：{"filename": "pluginFileName"}，例如 {"filename": "myplugin.jar"}
     * 支持两种格式：
     * 1. 简单文件名（如 "myplugin.jar"） - 会从配置的插件目录加载
     * 2. 完整路径（如 "C:/temp/myplugin.jar"） - 直接使用该路径加载
     */
    private void handleLoadPlugin(Context ctx) {
        try {
            JSONObject body = JSONObject.parseObject(ctx.body());
            if (body == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Request body is empty or malformed"));
                return;
            }
            String filename = body.getString("filename");
            if (filename == null || filename.trim().isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Parameter 'filename' is missing or empty"));
                return;
            }

            PluginManager pluginManager = dbInstance.getPluginManager();
            if (pluginManager == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin manager is not initialized"));
                return;
            }

            Path pluginPath;
            
            // 判断是否为完整路径（包含路径分隔符）
            boolean isFullPath = filename.contains("/") || filename.contains("\\");
            
            if (isFullPath) {
                // 完整路径：直接使用，但需要安全检查防止路径遍历
                if (filename.contains("..")) {
                    ctx.json(Map.of("retCode", -1, "retMsg", "Filename contains illegal characters"));
                    return;
                }
                pluginPath = Paths.get(filename);
            } else {
                // 简单文件名：从插件目录加载
                // 安全检查：防止路径遍历攻击
                if (filename.contains("..")) {
                    ctx.json(Map.of("retCode", -1, "retMsg", "Filename contains illegal characters"));
                    return;
                }
                
                String pluginsDir = dbInstance.serverConfiguration.getPlugins_dir();
                if (pluginsDir == null || pluginsDir.trim().isEmpty()) {
                    ctx.json(Map.of("retCode", -1, "retMsg", "Plugin directory is not configured"));
                    return;
                }
                pluginPath = Paths.get(pluginsDir, filename);
            }
            
            if (!pluginPath.toFile().exists()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin file does not exist: " + pluginPath));
                return;
            }

            // 检查插件是否已加载（通过尝试加载并获取插件ID）
            // 注意：这里无法在加载前检查，因为插件ID来自JAR文件的manifest
            // 加载插件
            String pluginId = pluginManager.loadPlugin(pluginPath);
            logger.info("[PLUGIN] Plugin {} (file: {}) loaded successfully", pluginId, filename);
            ctx.json(Map.of("retCode", 0, "retMsg", "Plugin loaded successfully", "pluginId", pluginId));
        } catch (JSONException e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "JSON parsing error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[PLUGIN] Failed to load plugin", e);
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to load plugin: " + e.getMessage()));
        }
    }

    /**
     * 处理卸载插件请求。
     * 请求体：{"plugid": "pluginId"}
     */
    private void handleUnloadPlugin(Context ctx) {
        try {
            JSONObject body = JSONObject.parseObject(ctx.body());
            if (body == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Request body is empty or malformed"));
                return;
            }
            String pluginId = body.getString("plugid");
            if (pluginId == null || pluginId.trim().isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Parameter 'plugid' is missing or empty"));
                return;
            }

            PluginManager pluginManager = dbInstance.getPluginManager();
            if (pluginManager == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin manager is not initialized"));
                return;
            }

            // 检查插件是否存在
            PluginWrapper plugin = pluginManager.getPlugin(pluginId);
            if (plugin == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin is not loaded"));
                return;
            }

            // 如果插件正在运行，先停止
            if (plugin.getPluginState() == PluginState.STARTED) {
                pluginManager.stopPlugin(pluginId);
            }

            // 卸载插件
            pluginManager.unloadPlugin(pluginId);
            logger.info("[PLUGIN] Plugin {} unloaded successfully", pluginId);
            ctx.json(Map.of("retCode", 0, "retMsg", "Plugin unloaded successfully"));
        } catch (JSONException e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "JSON parsing error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[PLUGIN] Failed to unload plugin", e);
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to unload plugin: " + e.getMessage()));
        }
    }

    /**
     * 处理启动插件请求。
     * 请求体：{"plugid": "pluginId"}
     */
    private void handleStartPlugin(Context ctx) {
        try {
            JSONObject body = JSONObject.parseObject(ctx.body());
            if (body == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Request body is empty or malformed"));
                return;
            }
            String pluginId = body.getString("plugid");
            if (pluginId == null || pluginId.trim().isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Parameter 'plugid' is missing or empty"));
                return;
            }

            PluginManager pluginManager = dbInstance.getPluginManager();
            if (pluginManager == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin manager is not initialized"));
                return;
            }

            // 检查插件是否已加载
            PluginWrapper plugin = pluginManager.getPlugin(pluginId);
            if (plugin == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin is not loaded, please load it first"));
                return;
            }

            // 检查插件状态
            if (plugin.getPluginState() == PluginState.STARTED) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin is already running"));
                return;
            }

            // 启动插件
            PluginState state = pluginManager.startPlugin(pluginId);
            if (state == PluginState.STARTED) {
                logger.info("[PLUGIN] Plugin {} started successfully", pluginId);
                ctx.json(Map.of("retCode", 0, "retMsg", "Plugin started successfully"));
            } else {
                logger.warn("[PLUGIN] Plugin {} failed to start, state: {}", pluginId, state);
                ctx.json(Map.of("retCode", -1, "retMsg", "Failed to start plugin, state: " + state));
            }
        } catch (JSONException e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "JSON parsing error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[PLUGIN] Failed to start plugin", e);
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to start plugin: " + e.getMessage()));
        }
    }

    /**
     * 处理停止插件请求。
     * 请求体：{"plugid": "pluginId"}
     */
    private void handleStopPlugin(Context ctx) {
        try {
            JSONObject body = JSONObject.parseObject(ctx.body());
            if (body == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Request body is empty or malformed"));
                return;
            }
            String pluginId = body.getString("plugid");
            if (pluginId == null || pluginId.trim().isEmpty()) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Parameter 'plugid' is missing or empty"));
                return;
            }

            PluginManager pluginManager = dbInstance.getPluginManager();
            if (pluginManager == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin manager is not initialized"));
                return;
            }

            // 检查插件是否已加载
            PluginWrapper plugin = pluginManager.getPlugin(pluginId);
            if (plugin == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin is not loaded"));
                return;
            }

            // 检查插件状态
            if (plugin.getPluginState() != PluginState.STARTED) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin is not in running state"));
                return;
            }

            // 停止插件
            PluginState state = pluginManager.stopPlugin(pluginId);
            if (state == PluginState.STOPPED || state == PluginState.DISABLED) {
                logger.info("[PLUGIN] Plugin {} stopped successfully", pluginId);
                ctx.json(Map.of("retCode", 0, "retMsg", "Plugin stopped successfully"));
            } else {
                logger.warn("[PLUGIN] Plugin {} failed to stop, state: {}", pluginId, state);
                ctx.json(Map.of("retCode", -1, "retMsg", "Failed to stop plugin, state: " + state));
            }
        } catch (JSONException e) {
            ctx.json(Map.of("retCode", -1, "retMsg", "JSON parsing error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[PLUGIN] Failed to stop plugin", e);
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to stop plugin: " + e.getMessage()));
        }
    }

    /**
     * 处理列出插件请求。
     * 返回所有已加载插件的信息，包括插件ID、状态、文件路径等。
     */
    private void handleListPlugins(Context ctx) {
        try {
            PluginManager pluginManager = dbInstance.getPluginManager();
            if (pluginManager == null) {
                ctx.json(Map.of("retCode", -1, "retMsg", "Plugin manager is not initialized"));
                return;
            }

            List<Map<String, Object>> pluginList = pluginManager.getPlugins().stream()
                    .map(plugin -> {
                        Map<String, Object> info = new java.util.HashMap<>();
                        info.put("pluginId", plugin.getPluginId());
                        info.put("version", plugin.getDescriptor().getVersion());
                        info.put("state", plugin.getPluginState().toString());
                        info.put("file", plugin.getPluginPath().toString());
                        info.put("description", plugin.getDescriptor().getPluginDescription());
                        
                        // 获取挂载时间（如果插件是DBPlugin实例）
                        if (plugin.getPlugin() instanceof org.slackerdb.plugin.DBPlugin dbPlugin) {
                            info.put("mountTime", dbPlugin.getMountTime());
                            info.put("mountTimeFormatted", dbPlugin.getMountTimeFormatted());
                        } else {
                            info.put("mountTime", 0L);
                            info.put("mountTimeFormatted", "");
                        }
                        
                        return info;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("retCode", 0);
            result.put("retMsg", "Success");
            result.put("plugins", pluginList);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("[PLUGIN] Failed to list plugins", e);
            ctx.json(Map.of("retCode", -1, "retMsg", "Failed to list plugins: " + e.getMessage()));
        }
    }

    /**
     * 停止服务（暂无需要清理的资源）
     */
    public void stop() {
        // 暂无需要停止的监控线程
    }
}