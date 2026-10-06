package org.slackerdb.plugins.scheduler.runner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 维护运行目录里 {@code hop-config.json} 的「项目」与「生命周期环境」条目。
 *
 * <p>Hop 的 {@code hop-run --project <name>} 需要在 hop-config.json 的
 * {@code projectsConfig.projectConfigurations} 里找到同名项目（含 projectHome / configFilename）；
 * {@code --environment <name>} 需要在 {@code projectsConfig.lifecycleEnvironments} 里找到同名环境，
 * 并通过它的 {@code configurationFiles} 指向的 {@code KEY=VALUE} 文件获得变量。
 *
 * <p>框架不内置任何业务命名：项目名与环境名都由配置模板（{@code scheduler.hop.projectName} /
 * {@code scheduler.hop.environmentName}）展开而来。</p>
 *
 * <p><b>并发安全</b>：同一实例的多个任务可能并行执行，因此本类对读写做进程内串行化，
 * 并以"写临时文件 + 原子替换"的方式落盘，避免半截 JSON。</p>
 */
public final class HopConfigWriter {

    /** 进程内串行化：同 JVM 内并行任务不会互相覆盖。 */
    private static final Object LOCK = new Object();

    private HopConfigWriter() {
    }

    /**
     * 确保 {@code <configFolder>/hop-config.json} 中存在指定项目与环境条目（已存在则更新）。
     *
     * @param configFolder    Hop 配置根目录（也就是运行目录里的 {@code flow}）
     * @param projectName     Hop 项目名（已展开）
     * @param projectHome     项目主目录（已展开，含 project-config.json 与 metadata）
     * @param environmentName 生命周期环境名（已展开）
     * @param variablesFile   变量文件（{@code KEY=VALUE}），作为该环境的 configurationFiles
     */
    public static void ensure(Path configFolder, String projectName, Path projectHome,
                              String environmentName, Path variablesFile) {
        synchronized (LOCK) {
            try {
                Files.createDirectories(configFolder);
                Path configFile = configFolder.resolve("hop-config.json");
                JSONObject root = readOrCreate(configFile, projectName, environmentName);
                JSONObject projectsConfig = root.getJSONObject("projectsConfig");
                if (projectsConfig == null) {
                    projectsConfig = new JSONObject();
                    root.put("projectsConfig", projectsConfig);
                }

                projectsConfig.put("enabled", true);
                projectsConfig.putIfAbsent("projectMandatory", false);
                projectsConfig.putIfAbsent("environmentMandatory", false);
                projectsConfig.putIfAbsent("defaultProjectConfigFile", "project-config.json");
                projectsConfig.putIfAbsent("standardParentProject", "default");

                upsertProject(projectsConfig, projectName, projectHome);
                // 父项目 default：模板通常自带同名目录，缺失时不强加
                Path defaultHome = configFolder.resolve("default");
                if (Files.isDirectory(defaultHome)) {
                    upsertProject(projectsConfig, "default", defaultHome);
                }
                upsertEnvironment(projectsConfig, environmentName, projectName, variablesFile);

                projectsConfig.put("defaultProject", projectName);
                projectsConfig.put("defaultEnvironment", environmentName);

                writeAtomically(configFile, root);
            } catch (IOException e) {
                throw new IllegalStateException("写入 hop-config.json 失败: " + configFolder + " : " + e.getMessage(), e);
            }
        }
    }

    private static JSONObject readOrCreate(Path configFile, String projectName, String environmentName)
            throws IOException {
        if (Files.isRegularFile(configFile)) {
            try {
                JSONObject existing = JSON.parseObject(Files.readString(configFile, StandardCharsets.UTF_8));
                if (existing != null) {
                    return existing;
                }
            } catch (Exception e) {
                // 模板里的 hop-config.json 损坏时不要静默丢弃：改名备份后重建
                Path backup = configFile.resolveSibling("hop-config.json.bak");
                Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        JSONObject root = new JSONObject();
        root.put("variables", new JSONArray());
        JSONObject projectsConfig = new JSONObject();
        projectsConfig.put("enabled", true);
        projectsConfig.put("projectMandatory", false);
        projectsConfig.put("environmentMandatory", false);
        projectsConfig.put("sortByNameLastUsedProjects", true);
        projectsConfig.put("clearingDbCacheWhenSwitching", false);
        projectsConfig.put("standardParentProject", "default");
        projectsConfig.put("defaultProjectConfigFile", "project-config.json");
        projectsConfig.put("defaultProject", projectName);
        projectsConfig.put("defaultEnvironment", environmentName);
        projectsConfig.put("projectConfigurations", new JSONArray());
        projectsConfig.put("lifecycleEnvironments", new JSONArray());
        root.put("projectsConfig", projectsConfig);
        return root;
    }

    private static void upsertProject(JSONObject projectsConfig, String projectName, Path projectHome) {
        JSONArray configurations = projectsConfig.getJSONArray("projectConfigurations");
        if (configurations == null) {
            configurations = new JSONArray();
            projectsConfig.put("projectConfigurations", configurations);
        }
        for (int i = 0; i < configurations.size(); i++) {
            JSONObject item = configurations.getJSONObject(i);
            if (item != null && projectName.equals(item.getString("projectName"))) {
                item.put("projectHome", projectHome.toAbsolutePath().toString());
                item.put("configFilename", "project-config.json");
                return;
            }
        }
        JSONObject entry = new JSONObject();
        entry.put("projectName", projectName);
        entry.put("projectHome", projectHome.toAbsolutePath().toString());
        entry.put("configFilename", "project-config.json");
        configurations.add(entry);
    }

    private static void upsertEnvironment(JSONObject projectsConfig, String environmentName,
                                          String projectName, Path variablesFile) {
        JSONArray environments = projectsConfig.getJSONArray("lifecycleEnvironments");
        if (environments == null) {
            environments = new JSONArray();
            projectsConfig.put("lifecycleEnvironments", environments);
        }
        for (int i = 0; i < environments.size(); i++) {
            JSONObject item = environments.getJSONObject(i);
            if (item != null && environmentName.equals(item.getString("name"))) {
                item.put("projectName", projectName);
                item.put("configurationFiles", JSONArray.of(variablesFile.toAbsolutePath().toString()));
                item.putIfAbsent("canvasText", "");
                item.putIfAbsent("attributesMap", new JSONObject());
                return;
            }
        }
        JSONObject entry = new JSONObject();
        entry.put("name", environmentName);
        entry.put("purpose", "slackerdb scheduler");
        entry.put("projectName", projectName);
        // canvasText / attributesMap 与 Hop GUI 写出的形状保持一致，避免下游解析差异
        entry.put("canvasText", "");
        entry.put("attributesMap", new JSONObject());
        entry.put("configurationFiles", JSONArray.of(variablesFile.toAbsolutePath().toString()));
        environments.add(entry);
    }

    private static void writeAtomically(Path configFile, JSONObject root) throws IOException {
        Path temp = configFile.resolveSibling("hop-config.json.tmp");
        Files.writeString(temp, JSON.toJSONString(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
