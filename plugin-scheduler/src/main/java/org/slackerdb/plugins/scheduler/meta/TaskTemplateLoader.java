package org.slackerdb.plugins.scheduler.meta;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slackerdb.plugins.scheduler.entity.SchedulerTask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模板任务清单加载器。
 *
 * <p><b>约定（框架级，与业务无关）</b>：清单文件名固定 {@value #FILE_NAME}，查找顺序为</p>
 * <ol>
 *   <li>{@code <projectHome>/<projectType>/conf/defaultSchedulerTask.json}</li>
 *   <li>{@code <projectHome>/conf/defaultSchedulerTask.json}（所有模板共用的默认清单）</li>
 * </ol>
 * <p>两处都没有时返回空清单 —— <b>缺清单是正常状态，不是错误</b>（该实例就是没有预置任务）。</p>
 *
 * <p><b>格式</b>：以"任务名 keyed 的对象"为主格式（key 即任务名，天然去重）：</p>
 * <pre>
 * {
 *   "主流程": { "taskScript": "主流程.hwf", "taskRunPolicy": "CRONTAB", "taskCrontabExpr": "0 1/2 * * * ? *" },
 *   "报表":   { "taskScript": "报表-交战情况分析.hwf", "taskRunPolicy": "RUN_ONCE" }
 * }
 * </pre>
 * <p>同时容错接受数组形式（每项必须带 {@code taskName}），以及 {@code {"tasks": [ ... ]}} 包裹形式。</p>
 *
 * <p>每个任务都会做基本校验（策略/类型/必填项）。校验不通过的任务会被跳过并记入
 * {@link Loaded#warnings}，而不是让整个模板或调度期报错。</p>
 */
public final class TaskTemplateLoader {

    public static final String FILE_NAME = "defaultSchedulerTask.json";

    /** 支持的运行策略。 */
    public static final Set<String> RUN_POLICIES = Set.of("RUN_ONCE", "CRONTAB", "INTERVAL");
    /** 支持的失败策略。 */
    public static final Set<String> FAIL_POLICIES = Set.of("STOP", "CONTINUE", "RETRY");
    /** 支持的并发策略。 */
    public static final Set<String> PARALLEL_POLICIES =
            Set.of("PARALLEL", "SERIAL_DISCARD", "SERIAL_DELAY", "SERIAL_CATCHUP");
    /** 支持的脚本类型（SQL 由 HOP 工作流内的 SQL action 执行，不在此列）。 */
    public static final Set<String> SCRIPT_TYPES = Set.of("HOP", "SHELL", "COMMAND");

    /** 加载结果。 */
    public record Loaded(Path file, List<SchedulerTask> tasks, List<String> warnings, List<String> groups) {
        public boolean isEmpty() {
            return tasks.isEmpty();
        }

        public String fileText() {
            return file == null ? "(未找到模板任务清单)" : file.toString();
        }
    }

    private TaskTemplateLoader() {
    }

    /** 按约定顺序解析清单文件路径；都不存在返回 null。 */
    public static Path resolveFile(Path projectHome, String projectType) {
        if (projectHome == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        if (projectType != null && !projectType.isBlank()) {
            candidates.add(projectHome.resolve(projectType).resolve("conf").resolve(FILE_NAME));
        }
        candidates.add(projectHome.resolve("conf").resolve(FILE_NAME));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 加载并转换为任务实体（{@code runId} 由调用方给出，其余字段来自模板）。
     */
    public static Loaded load(Path projectHome, String projectType, String runId) {
        Path file = resolveFile(projectHome, projectType);
        List<String> warnings = new ArrayList<>();
        if (file == null) {
            return new Loaded(null, List.of(), warnings, List.of());
        }
        JSONObject root;
        try {
            root = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("模板任务清单解析失败: " + file + " : " + e.getMessage(), e);
        }
        List<String> groups = readGroups(root);
        List<SchedulerTask> tasks = toTasks(root, runId, warnings);
        List<String> effectiveGroups = normalizeOrder(tasks, groups, warnings);
        return new Loaded(file, sortedByOrder(tasks), warnings, effectiveGroups);
    }

    /**
     * 读取可选的组顺序声明：{@code "groups": ["SETUP_GROUP", "MAIN_GROUP", "OTHER_GROUP"]}。
     *
     * <p>未声明时按任务在清单中<b>首次出现的顺序</b>确定组顺序。</p>
     */
    private static List<String> readGroups(JSONObject root) {
        JSONArray array = root == null ? null : root.getJSONArray("groups");
        List<String> groups = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                String name = array.getString(i);
                if (name != null && !name.isBlank()) {
                    groups.add(name.trim());
                }
            }
        }
        return groups;
    }

    /**
     * 把「组顺序 + 组内顺序」归一化进 {@code taskStartupOrder}，这样编排只依赖已入库的顺序字段。
     *
     * <p>{@code taskStartupOrder = 组序号 * 1000 + 组内序号}；组内序号 = 清单里声明的
     * {@code taskStartupOrder}（稳定排序，其次按任务名）。</p>
     */
    private static List<String> normalizeOrder(List<SchedulerTask> tasks, List<String> declaredGroups,
                                               List<String> warnings) {
        if (tasks.isEmpty()) {
            return declaredGroups;
        }
        List<String> groupOrder = new ArrayList<>(declaredGroups);
        for (SchedulerTask task : tasks) {
            String group = groupName(task);
            if (!groupOrder.contains(group)) {
                groupOrder.add(group);
            }
        }
        Map<String, List<SchedulerTask>> byGroup = new LinkedHashMap<>();
        for (SchedulerTask task : tasks) {
            byGroup.computeIfAbsent(groupName(task), k -> new ArrayList<>()).add(task);
        }
        for (String group : groupOrder) {
            List<SchedulerTask> members = byGroup.get(group);
            if (members == null) {
                continue;
            }
            members.sort((a, b) -> {
                int byOrder = Integer.compare(a.getTaskStartupOrder(), b.getTaskStartupOrder());
                return byOrder != 0 ? byOrder : a.getTaskName().compareTo(b.getTaskName());
            });
            int groupIndex = groupOrder.indexOf(group);
            for (int i = 0; i < members.size(); i++) {
                members.get(i).setTaskStartupOrder(groupIndex * 1000 + i);
            }
        }
        if (declaredGroups.isEmpty()) {
            // 只有存在多个"具名组"时，顺序才需要业务显式声明；单组/无组不必打扰使用者
            long namedGroups = groupOrder.stream().filter(g -> !g.isBlank()).distinct().count();
            if (namedGroups > 1) {
                warnings.add("清单未声明 groups，组顺序按首次出现确定: " + groupOrder
                        + "；如需固定顺序请在清单里加 \"groups\": [...]");
            }
        }
        return groupOrder;
    }

    private static String groupName(SchedulerTask task) {
        return task.getTaskGroup() == null || task.getTaskGroup().isBlank()
                ? "" : task.getTaskGroup().trim();
    }

    private static List<SchedulerTask> sortedByOrder(List<SchedulerTask> tasks) {
        List<SchedulerTask> sorted = new ArrayList<>(tasks);
        sorted.sort((a, b) -> {
            int byOrder = Integer.compare(a.getTaskStartupOrder(), b.getTaskStartupOrder());
            return byOrder != 0 ? byOrder : a.getTaskName().compareTo(b.getTaskName());
        });
        return sorted;
    }

    /** 解析清单 JSON（keyed 对象 / 数组 / {"tasks":[...]}）为任务实体。 */
    public static List<SchedulerTask> toTasks(JSONObject root, String runId, List<String> warnings) {
        List<SchedulerTask> result = new ArrayList<>();
        if (root == null) {
            return result;
        }

        Map<String, JSONObject> entries = new LinkedHashMap<>();
        JSONArray array = root.getJSONArray("tasks");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    warnings.add("tasks[" + i + "] 不是对象，已跳过");
                    continue;
                }
                String name = item.getString("taskName");
                if (name == null || name.isBlank()) {
                    warnings.add("tasks[" + i + "] 缺少 taskName，已跳过");
                    continue;
                }
                entries.put(name, item);
            }
        } else {
            for (String key : root.keySet()) {
                if ("$schema".equals(key) || "variables".equals(key) || "groups".equals(key)) {
                    continue;   // 保留键，不作为任务
                }
                Object value = root.get(key);
                if (!(value instanceof JSONObject item)) {
                    warnings.add("键[" + key + "] 不是任务对象，已跳过");
                    continue;
                }
                entries.put(key, item);
            }
        }

        for (Map.Entry<String, JSONObject> entry : entries.entrySet()) {
            SchedulerTask task = toTask(runId, entry.getKey(), entry.getValue(), warnings);
            if (task != null) {
                result.add(task);
            }
        }
        return result;
    }

    private static SchedulerTask toTask(String runId, String taskName, JSONObject item, List<String> warnings) {
        String script = item.getString("taskScript");
        if (script == null || script.isBlank()) {
            warnings.add("任务[" + taskName + "] 缺少 taskScript，已跳过");
            return null;
        }

        String scriptType = upper(item.getString("taskScriptType"), "HOP");
        if (!SCRIPT_TYPES.contains(scriptType)) {
            warnings.add("任务[" + taskName + "] 的 taskScriptType=[" + scriptType + "] 不受支持（可用: "
                    + SCRIPT_TYPES + "），已跳过");
            return null;
        }

        String runPolicy = upper(item.getString("taskRunPolicy"), "RUN_ONCE");
        if (!RUN_POLICIES.contains(runPolicy)) {
            warnings.add("任务[" + taskName + "] 的 taskRunPolicy=[" + runPolicy + "] 不受支持（可用: "
                    + RUN_POLICIES + "），已跳过");
            return null;
        }

        String failPolicy = upper(item.getString("taskFailPolicy"), "CONTINUE");
        if (!FAIL_POLICIES.contains(failPolicy)) {
            warnings.add("任务[" + taskName + "] 的 taskFailPolicy=[" + failPolicy + "] 不受支持（可用: "
                    + FAIL_POLICIES + "），已按 CONTINUE 处理");
            failPolicy = "CONTINUE";
        }

        String parallelPolicy = upper(item.getString("taskParallelPolicy"), "PARALLEL");
        if (!PARALLEL_POLICIES.contains(parallelPolicy)) {
            warnings.add("任务[" + taskName + "] 的 taskParallelPolicy=[" + parallelPolicy + "] 不受支持（可用: "
                    + PARALLEL_POLICIES + "），已按 PARALLEL 处理");
            parallelPolicy = "PARALLEL";
        }

        int interval = item.getIntValue("taskInterval", 0);
        String crontabExpr = item.getString("taskCrontabExpr");
        if ("CRONTAB".equals(runPolicy) && (crontabExpr == null || crontabExpr.isBlank())) {
            warnings.add("任务[" + taskName + "] 的 taskRunPolicy=CRONTAB 但缺少 taskCrontabExpr，已跳过");
            return null;
        }
        if ("INTERVAL".equals(runPolicy) && interval <= 0) {
            warnings.add("任务[" + taskName + "] 的 taskRunPolicy=INTERVAL 但 taskInterval<=0，已跳过");
            return null;
        }

        SchedulerTask task = new SchedulerTask();
        task.setRunId(runId);
        task.setTaskName(taskName);
        task.setTaskScript(script);
        task.setTaskScriptType(scriptType);
        task.setTaskRunPolicy(runPolicy);
        task.setTaskFailPolicy(failPolicy);
        task.setTaskRetryTimes(item.getIntValue("taskRetryTimes", 0));
        task.setTaskRetryInterval(item.getIntValue("taskRetryInterval", 60));
        task.setTaskParallelPolicy(parallelPolicy);
        task.setTaskCrontabExpr(crontabExpr);
        task.setTaskInterval(interval);
        task.setTaskTimeout(item.getIntValue("taskTimeout", 0));
        task.setTaskEnabled(item.getBooleanValue("taskEnabled", true));
        task.setTaskMandatory(item.getBooleanValue("taskMandatory", false));
        task.setTaskGroup(item.getString("taskGroup"));
        task.setTaskStartupOrder(item.getIntValue("taskStartupOrder", 0));
        task.setTaskDescription(item.getString("taskDescription"));
        task.setConfigJson(item.getString("configJson"));

        // 任务级变量：随 JobData 下发，作为变量层中最靠近任务的一层
        JSONObject variables = item.getJSONObject("variables");
        if (variables != null && !variables.isEmpty()) {
            Map<String, String> flat = new LinkedHashMap<>();
            for (String key : variables.keySet()) {
                Object value = variables.get(key);
                if (value == null || value instanceof JSONObject || value instanceof JSONArray) {
                    continue;
                }
                flat.put(key, String.valueOf(value));
            }
            task.setVariablesJson(JSON.toJSONString(flat));
        }
        return task;
    }

    private static String upper(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
