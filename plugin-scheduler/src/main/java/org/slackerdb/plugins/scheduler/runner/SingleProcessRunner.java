package org.slackerdb.plugins.scheduler.runner;

import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Single process runner for executing scripts (SQL, HOP, SHELL).
 *
 * <p>This class handles the actual execution of a script file within a Run's
 * working directory. It supports SQL scripts (via DuckDB JDBC), HOP files
 * (via embedded HopService), and shell scripts.</p>
 */
public class SingleProcessRunner {

    private final Logger logger;

    /** HopService instance for executing HOP workflows/pipelines in-process */
    private HopService hopService;

    /** Hop 工作目录 */
    private String hopWorkDirectory;

    /** Hop 插件目录 */
    private String hopPluginDirectory;

    public SingleProcessRunner(Logger logger) {
        this.logger = logger;
    }

    /**
     * 设置 Hop 运行环境。
     * <p>
     * 如果设置了 workDirectory 和 pluginDirectory，则 HOP 类型的脚本将使用
     * 嵌入式的 HopService 来执行，而不是调用外部的 hop-run 命令。
     *
     * @param workDirectory   Hop 工作目录（包含 metadata 等子目录）
     * @param pluginDirectory Hop 插件目录
     */
    public void setHopEnvironment(String workDirectory, String pluginDirectory) {
        this.hopWorkDirectory = workDirectory;
        this.hopPluginDirectory = pluginDirectory;
    }

    /**
     * Execute a script.
     *
     * @param workDir    the working directory for execution
     * @param scriptPath the path to the script file
     * @param scriptType the type of script (SQL, HOP, SHELL)
     * @param timeout    timeout in seconds (0 = no timeout)
     * @return exit code (0 = success)
     */
    public int execute(String workDir, String scriptPath, String scriptType, int timeout) throws Exception {
        logger.info("[RUNNER] Executing script: type={}, path={}, workDir={}", scriptType, scriptPath, workDir);

        switch (scriptType.toUpperCase()) {
            case "SQL":
                return executeSqlScript(scriptPath, timeout);
            case "HOP":
                return executeHopScript(scriptPath, workDir, timeout);
            case "SHELL":
                return executeShellScript(scriptPath, workDir, timeout);
            default:
                logger.error("[RUNNER] Unsupported script type: {}", scriptType);
                return -1;
        }
    }

    /**
     * Execute a SQL script via DuckDB JDBC.
     *
     * <p>Reads the SQL file, splits by semicolons, strips comments,
     * and executes each statement via JDBC.</p>
     */
    private int executeSqlScript(String scriptPath, int timeout) {
        logger.info("[RUNNER] Executing SQL script via JDBC: {}", scriptPath);

        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            logger.error("[RUNNER] SQL script file not found: {}", scriptPath);
            return -1;
        }

        String jdbcUrl = "jdbc:duckdb:";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {

            // Set timeout if specified
            if (timeout > 0) {
                stmt.setQueryTimeout(timeout);
            }

            // Read the SQL file
            String sqlContent = Files.readString(Path.of(scriptPath));

            // Split by semicolons and execute each statement
            List<String> sqlStatements = splitSqlStatements(sqlContent);
            int executedCount = 0;

            for (String sql : sqlStatements) {
                if (sql.trim().isEmpty()) {
                    continue;
                }

                try {
                    logger.debug("[RUNNER] Executing SQL: {}", sql);
                    boolean hasResult = stmt.execute(sql);
                    if (hasResult) {
                        // Consume the result set (not needed for script execution)
                        var rs = stmt.getResultSet();
                        while (rs.next()) {
                            // Just consume
                        }
                        rs.close();
                    }
                    executedCount++;
                } catch (SQLException e) {
                    logger.error("[RUNNER] SQL execution error: {}", e.getMessage());
                    logger.error("[RUNNER] Failed SQL: {}", sql);
                    return -1;
                }
            }

            logger.info("[RUNNER] SQL script completed: {} statements executed.", executedCount);
            return 0;

        } catch (SQLException e) {
            logger.error("[RUNNER] JDBC connection error: {}", e.getMessage());
            return -1;
        } catch (IOException e) {
            logger.error("[RUNNER] Error reading SQL file: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Split SQL content into individual statements by semicolons,
     * respecting single-quote strings.
     */
    private List<String> splitSqlStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder currentSegment = new StringBuilder();
        boolean inSingleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // Handle single-quote strings
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            }

            // Split by semicolon only when not inside quotes
            if (c == ';' && !inSingleQuote) {
                String trimmed = currentSegment.toString().trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
                currentSegment.setLength(0);
            } else {
                currentSegment.append(c);
            }
        }

        // Add the last segment
        String trimmed = currentSegment.toString().trim();
        if (!trimmed.isEmpty()) {
            result.add(trimmed);
        }

        return result;
    }

    /**
     * Execute a HOP script via embedded HopService.
     * <p>
     * 如果已通过 setHopEnvironment() 设置了 hop 工作目录和插件目录，
     * 则使用嵌入式的 HopService 在 JVM 进程内执行 HOP 文件；
     * 否则回退到调用外部的 hop-run 命令。
     */
    private int executeHopScript(String scriptPath, String workDir, int timeout) throws Exception {
        // 如果已配置 Hop 环境，使用嵌入式的 HopService
        if (hopWorkDirectory != null && hopPluginDirectory != null
                && !hopWorkDirectory.isEmpty() && !hopPluginDirectory.isEmpty()) {
            return executeHopWithService(scriptPath, workDir, timeout);
        }

        // 回退到外部命令方式
        logger.warn("[RUNNER] Hop 环境未配置，回退到外部 hop-run 命令");
        String command = "hop-run -f \"" + scriptPath + "\"";
        return runProcess(command, workDir, timeout);
    }

    /**
     * 使用嵌入式的 HopService 执行 HOP 文件。
     */
    private int executeHopWithService(String scriptPath, String workDir, int timeout) throws Exception {
        logger.info("[RUNNER] Executing HOP script via embedded HopService: {}", scriptPath);

        // 懒初始化 HopService
        if (hopService == null) {
            hopService = new HopService(logger);
            hopService.setEnv(hopWorkDirectory, hopPluginDirectory);
            // 使用 workDir 下的 audit 和 logs 目录
            hopService.setAuditDir(workDir + "/audit");
            hopService.setLogDir(workDir + "/logs");
        }

        try {
            // 执行 HOP 文件（传入空的环境变量 Map）
            hopService.runHop(scriptPath, Collections.emptyMap());
            logger.info("[RUNNER] HOP script completed successfully: {}", scriptPath);
            return 0;
        } catch (Exception e) {
            logger.error("[RUNNER] HOP script execution failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Execute a shell script.
     */
    private int executeShellScript(String scriptPath, String workDir, int timeout) throws Exception {
        String command;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command = "cmd /c \"" + scriptPath + "\"";
        } else {
            command = "sh \"" + scriptPath + "\"";
        }
        return runProcess(command, workDir, timeout);
    }

    /**
     * Run a process and wait for completion.
     */
    private int runProcess(String command, String workDir, int timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.command("cmd", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        logger.debug("[RUNNER] Command: {}", String.join(" ", pb.command()));

        Process process = pb.start();

        // Read output in background
        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("[RUNNER] {}", line);
                }
            } catch (IOException e) {
                logger.warn("[RUNNER] Error reading process output: {}", e.getMessage());
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        int exitCode;
        if (timeout > 0) {
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("[RUNNER] Process timed out after {} seconds.", timeout);
                return -2; // Timeout exit code
            }
            exitCode = process.exitValue();
        } else {
            exitCode = process.waitFor();
        }

        logger.info("[RUNNER] Process completed with exit code: {}", exitCode);
        return exitCode;
    }
}
