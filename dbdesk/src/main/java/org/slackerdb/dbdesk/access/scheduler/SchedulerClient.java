package org.slackerdb.dbdesk.access.scheduler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调度器（ETL 插件）的管理口客户端：`/scheduler/*`。
 *
 * <p>和 {@code access.http.ManagementClient} 分开：那个只管"实例/健康/会话"，这个只管调度，
 * 两侧的契约（`/scheduler/capabilities` 的 `features`）本来就不一样。两个都只依赖
 * {@code java.net.http}，不引第三方 HTTP 库。</p>
 *
 * <p>约定：**非 2xx 一律抛 {@link SchedulerException}**，消息里带上服务端返回的 {@code error}，
 * 界面直接显示，不吞错。</p>
 */
public final class SchedulerClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一次 run。 */
    public record RunInfo(String runId, String projectType, String status, String workDir, String createTime) {
    }

    /** 一条任务（含运行态）。 */
    public record TaskInfo(String name, String script, String scriptType, String runPolicy,
                           String crontabExpr, int interval, int timeout, boolean enabled,
                           boolean scheduled, boolean running, String triggerState, String runtimeState) {
    }

    /** 能力声明。 */
    public record Capabilities(int contractVersion, boolean runCrud, boolean taskCrud,
                               boolean templateRead, boolean templates, boolean globalHistory,
                               boolean quartzStarted, int quartzThreads, boolean hopReady,
                               List<String> hopReasons) {
    }

    /** 一条执行流水（跨 run）。 */
    public record HistoryRow(String runId, String taskName, String startTime, String endTime,
                             int retCode, String retMsg, String logFile) {

        /** 结果列：成功 / 失败 / 进行中。 */
        public String result() {
            if (endTime == null || endTime.isBlank()) {
                return "进行中";
            }
            return retCode == 0 ? "成功" : "失败";
        }

        /** 耗时（开始→结束）；跑着或时间缺失时给 "—"。 */
        public String duration() {
            if (startTime == null || startTime.isBlank() || endTime == null || endTime.isBlank()) {
                return "—";
            }
            try {
                java.time.LocalDateTime from = java.time.LocalDateTime.parse(normalize(startTime));
                java.time.LocalDateTime to = java.time.LocalDateTime.parse(normalize(endTime));
                long seconds = java.time.Duration.between(from, to).getSeconds();
                return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";
            } catch (RuntimeException e) {
                return "—";
            }
        }

        private static String normalize(String value) {
            // 服务端可能给数组形式（[2026,10,22,20,57,10]）或 ISO 文本，这里统一成 ISO
            String text = value.trim();
            if (text.startsWith("[")) {
                String[] parts = text.substring(1, text.length() - 1).split(",");
                if (parts.length >= 6) {
                    return String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                            Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()),
                            Integer.parseInt(parts[4].trim()), Integer.parseInt(parts[5].trim()));
                }
            }
            return text.replace(' ', 'T');
        }
    }

    /** 模板导入结果。 */
    public record ImportResult(int count, int taskCount, List<String> warnings) {
    }

    /** 历史页：行 + 汇总。 */
    public record HistoryPage(List<HistoryRow> rows, int success, int failed, int running) {
    }
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;

    public SchedulerClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(10));
    }

    public SchedulerClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Capabilities capabilities() throws SchedulerException {
        JsonNode root = get("/scheduler/capabilities");
        JsonNode features = root.path("features");
        JsonNode quartz = root.path("quartz");
        JsonNode hop = root.path("hop");
        List<String> reasons = new ArrayList<>();
        for (JsonNode reason : hop.path("reasons")) {
            reasons.add(reason.asString(""));
        }
        return new Capabilities(
                root.path("contractVersion").asInt(0),
                features.path("runCrud").asBoolean(false),
                features.path("taskCrud").asBoolean(false),
                features.path("templateRead").asBoolean(false),
                features.path("templates").asBoolean(false),
                features.path("globalHistory").asBoolean(false),
                quartz.path("started").asBoolean(false),
                quartz.path("threads").asInt(0),
                hop.path("ready").asBoolean(false),
                List.copyOf(reasons));
    }

    public List<RunInfo> runs() throws SchedulerException {
        JsonNode root = get("/scheduler/run/list");
        List<RunInfo> runs = new ArrayList<>();
        for (JsonNode node : root.path("runs")) {
            runs.add(new RunInfo(
                    node.path("runId").asString(""),
                    node.path("projectType").asString(""),
                    node.path("status").asString(""),
                    node.path("workDir").asString(""),
                    node.path("createTime").asString("")));
        }
        return runs;
    }

    public void createRun(String projectType, String runId) throws SchedulerException {
        ObjectNode body = JSON.createObjectNode();
        body.put("projectType", projectType);
        if (runId != null && !runId.isBlank()) {
            body.put("runId", runId.strip());
        }
        post("/scheduler/run/create", body);
    }

    public void startRun(String runId) throws SchedulerException {
        post("/scheduler/run/" + runId + "/start", JSON.createObjectNode());
    }

    public void stopRun(String runId) throws SchedulerException {
        post("/scheduler/run/" + runId + "/stop", JSON.createObjectNode());
    }

    public void dropRun(String runId) throws SchedulerException {
        post("/scheduler/run/" + runId + "/drop", JSON.createObjectNode());
    }

    public List<TaskInfo> tasks(String runId) throws SchedulerException {
        JsonNode root = get("/scheduler/run/" + runId + "/task/list");
        List<TaskInfo> tasks = new ArrayList<>();
        for (JsonNode node : root.path("tasks")) {
            tasks.add(new TaskInfo(
                    node.path("taskName").asString(""),
                    node.path("taskScript").asString(""),
                    node.path("taskScriptType").asString(""),
                    node.path("taskRunPolicy").asString(""),
                    node.path("taskCrontabExpr").asString(""),
                    node.path("taskInterval").asInt(0),
                    node.path("taskTimeout").asInt(0),
                    node.path("taskEnabled").asBoolean(false),
                    node.path("scheduled").asBoolean(false),
                    node.path("running").asBoolean(false),
                    node.path("states").path("trigger").asString(""),
                    node.path("states").path("runtime").asString("")));
        }
        return tasks;
    }

    public void createTask(String runId, Map<String, Object> payload) throws SchedulerException {
        post("/scheduler/run/" + runId + "/task/create", JSON.valueToTree(payload));
    }

    public void updateTask(String runId, String taskName, Map<String, Object> patch) throws SchedulerException {
        ObjectNode body = JSON.valueToTree(patch);
        body.put("taskName", taskName);
        post("/scheduler/run/" + runId + "/task/update", body);
    }

    public void deleteTask(String runId, String taskName) throws SchedulerException {
        ObjectNode body = JSON.createObjectNode();
        body.put("taskName", taskName);
        post("/scheduler/run/" + runId + "/task/delete", body);
    }

    /** 登记调度（把任务交给 Quartz）。 */
    public void scheduleTask(String runId, String taskName) throws SchedulerException {
        ObjectNode body = JSON.createObjectNode();
        body.put("taskName", taskName);
        post("/scheduler/run/" + runId + "/task/schedule", body);
    }

    /** 可用的项目类型（模板目录名）。 */
    public List<String> projects() throws SchedulerException {
        JsonNode root = get("/scheduler/project/list");
        List<String> projects = new ArrayList<>();
        for (JsonNode node : root.path("projects")) {
            projects.add(node.asString(""));
        }
        return projects;
    }

    /** 跨 run 的执行流水；{@code hours=0} 表示不限时间。 */
    public HistoryPage history(int hours, int limit) throws SchedulerException {
        JsonNode root = get("/scheduler/history?hours=" + hours + "&limit=" + limit);
        List<HistoryRow> rows = new ArrayList<>();
        for (JsonNode node : root.path("history")) {
            rows.add(new HistoryRow(
                    node.path("runId").asString(""),
                    node.path("taskName").asString(""),
                    text(node.path("startTime")),
                    text(node.path("endTime")),
                    node.path("retCode").asInt(0),
                    node.path("retMsg").asString(""),
                    node.path("logFile").asString("")));
        }
        JsonNode summary = root.path("summary");
        return new HistoryPage(rows,
                summary.path("success").asInt(0),
                summary.path("failed").asInt(0),
                summary.path("running").asInt(0));
    }

    /** 时间字段可能是文本或数组（[yyyy,M,d,H,m,s,…]），统一渲染成 "yyyy-MM-dd HH:mm:ss"。 */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (!node.isArray() || node.size() < 2) {
            return node.isArray() ? node.toString() : node.asString("");
        }
        try {
            int year = node.get(0).asInt();
            int month = node.size() > 1 ? node.get(1).asInt() : 1;
            int day = node.size() > 2 ? node.get(2).asInt() : 1;
            int hour = node.size() > 3 ? node.get(3).asInt() : 0;
            int minute = node.size() > 4 ? node.get(4).asInt() : 0;
            int second = node.size() > 5 ? node.get(5).asInt() : 0;
            return String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second);
        } catch (RuntimeException e) {
            return node.toString();
        }
    }
    /** 整包导入模板（zip 的 base64）。 */
    public ImportResult importZip(String projectType, String base64) throws SchedulerException {
        ObjectNode body = JSON.createObjectNode();
        body.put("contentBase64", base64);
        JsonNode root = post("/scheduler/project/" + projectType + "/import", body);
        List<String> warnings = new ArrayList<>();
        for (JsonNode node : root.path("warnings")) {
            warnings.add(node.asString(""));
        }
        return new ImportResult(root.path("count").asInt(0), root.path("taskCount").asInt(0), warnings);
    }

    /** 读模板文件内容。 */
    public String templateFile(String projectType, String path) throws SchedulerException {
        JsonNode root = get("/scheduler/project/" + projectType + "/file?path=" + urlEncode(path));
        return root.path("content").asString("");
    }

    /**
     * 上传（写）模板文件。
     *
     * @return 服务端的 warnings（字段拼错、策略不支持等），空表示没有告警
     */
    public List<String> uploadTemplate(String projectType, String path, String content) throws SchedulerException {
        ObjectNode body = JSON.createObjectNode();
        body.put("path", path);
        body.put("content", content);
        JsonNode root = post("/scheduler/project/" + projectType + "/upload", body);
        List<String> warnings = new ArrayList<>();
        for (JsonNode node : root.path("warnings")) {
            warnings.add(node.asString(""));
        }
        return warnings;
    }

    public void deleteTemplate(String projectType, String path) throws SchedulerException {
        ObjectNode body = JSON.createObjectNode();
        body.put("path", path);
        post("/scheduler/project/" + projectType + "/delete", body);
    }

    /** 任务日志尾巴（默认 200 行）。 */
    public String taskLog(String runId, String taskName, int tail) throws SchedulerException {
        JsonNode root = get("/scheduler/run/" + runId + "/log?taskName=" + urlEncode(taskName) + "&tail=" + tail);
        StringBuilder builder = new StringBuilder();
        for (JsonNode line : root.path("lines")) {
            builder.append(line.asString("")).append('\n');
        }
        return builder.toString();
    }

    @Override
    public void close() {
        // HttpClient 不需要显式关闭（JDK 21 的实现没有 close）；留这个方法是为了调用方写法统一
    }

    // ========== HTTP ==========

    private JsonNode get(String path) throws SchedulerException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    private JsonNode post(String path, JsonNode body) throws SchedulerException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private JsonNode send(HttpRequest.Builder builder) throws SchedulerException {
        try {
            HttpResponse<String> response = http.send(builder.timeout(timeout).build(),
                    HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SchedulerException(extractError(text, response.statusCode()));
            }
            return text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (SchedulerException e) {
            throw e;
        } catch (Exception e) {
            throw new SchedulerException("调度器不可达：" + e.getMessage(), e);
        }
    }

    private static String extractError(String body, int status) {
        try {
            JsonNode node = JSON.readTree(body);
            String error = node.path("error").asString("");
            if (!error.isBlank()) {
                String problems = node.path("problems").isArray() ? node.path("problems").toString() : "";
                return "HTTP " + status + " · " + error + (problems.isBlank() ? "" : " " + problems);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON：原样带上
        }
        return "HTTP " + status + " · " + body;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 调度器调用失败（含服务端 error 文案）。 */
    public static class SchedulerException extends Exception {
        public SchedulerException(String message) {
            super(message);
        }

        public SchedulerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
