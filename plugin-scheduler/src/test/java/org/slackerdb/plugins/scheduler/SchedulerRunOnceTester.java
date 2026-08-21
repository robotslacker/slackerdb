package org.slackerdb.plugins.scheduler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.slackerdb.plugin.DBPluginContext;
import org.slackerdb.plugins.scheduler.controller.SchedulerController;
import org.slackerdb.plugins.scheduler.meta.SchedulerMeta;
import org.slackerdb.plugins.scheduler.quartz.DynamicQuartzScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/**
 * Scheduler Run-Once Tester - End-to-end test for RUN_ONCE scheduling
 *
 * <p>Bypasses the PF4J framework, directly starts Javalin + SchedulerController,
 * and tests core functionality such as creating Runs and scheduling one-time tasks via HTTP requests.</p>
 */
public class SchedulerRunOnceTester {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerRunOnceTester.class);

    // Test result statistics
    private static int testsPassed = 0;
    private static int testsFailed = 0;
    private static int testsSkipped = 0;

    // Test environment resources
    private static Connection conn;
    private static Javalin app;
    private static SchedulerMeta meta;
    private static DynamicQuartzScheduler quartzScheduler;
    private static Path testWorkDir;
    private static Path testProjectDir;
    private static HttpClient httpClient;

    // Test configuration
    private static final String TEST_PROJECT_TYPE = "test_project_once";
    private static final int TEST_PORT = 19870; // Use an uncommon port to avoid conflicts
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String TEST_SCRIPT_NAME = IS_WINDOWS ? "test_script.bat" : "test_script.sh";

    public static void main(String[] args) {
        try {
            System.out.println("============================================");
            System.out.println("  Scheduler RUN_ONCE End-to-End Test Start");
            System.out.println("============================================");
            System.out.println();

            setup();

            // Execute test suite
            runTestSuite();

            // Print test results
            printTestSummary();

        } catch (Exception e) {
            logger.error("Error during test execution", e);
            System.err.println("Error during test execution: " + e.getMessage());
            e.printStackTrace();
            testsFailed++;
        } finally {
            try {
                teardown();
            } catch (Exception ex) {
                logger.error("Error during cleanup", ex);
                System.err.println("Error during cleanup: " + ex.getMessage());
            }
        }

        // Determine exit code based on test results
        System.exit(testsFailed > 0 ? 1 : 0);
    }

    /**
     * Run the complete test suite.
     */
    private static void runTestSuite() throws Exception {
        // Phase 1: Create test project directory and scripts
        System.out.println("=== Phase 1: Create test project directory and scripts ===");
        testCreateProjectStructure();

        // Phase 2: Verify metadata tables are auto-initialized
        System.out.println("\n=== Phase 2: Verify metadata tables are auto-initialized ===");
        testVerifyMetaInitialization();

        // Phase 3: Create Run via HTTP
        System.out.println("\n=== Phase 3: Create Run via HTTP ===");
        String runId = testCreateRunViaHttp();

        // Phase 4: Query Run via HTTP
        System.out.println("\n=== Phase 4: Query Run via HTTP ===");
        testGetRunViaHttp(runId);

        // Phase 5: Start Run via HTTP (schedule all tasks)
        System.out.println("\n=== Phase 5: Start Run via HTTP ===");
        testStartRunViaHttp(runId);

        // Phase 6: List Runs via HTTP
        System.out.println("\n=== Phase 6: List Runs via HTTP ===");
        testListRunsViaHttp();

        // Phase 7: Stop Run via HTTP
        System.out.println("\n=== Phase 7: Stop Run via HTTP ===");
        testStopRunViaHttp(runId);

        // Phase 8: Drop Run via HTTP
        System.out.println("\n=== Phase 8: Drop Run via HTTP ===");
        testDropRunViaHttp(runId);
    }

    /**
     * Test 1: Create test project directory structure.
     */
    private static void testCreateProjectStructure() throws Exception {
        System.out.println("Test 1: Create test project directory structure...");

        try {
            // Create project template directory structure: {projectHome}/{projectType}/flow/
            Path projectsDir = testProjectDir.resolve(TEST_PROJECT_TYPE);
            Path flowDir = projectsDir.resolve("flow");
            Path confDir = projectsDir.resolve("conf");
            Files.createDirectories(flowDir);
            Files.createDirectories(confDir);

            // Create test script
            Path scriptFile = flowDir.resolve(TEST_SCRIPT_NAME);
            List<String> scriptLines = new ArrayList<>();
            if (IS_WINDOWS) {
                scriptLines.add("@echo off");
                scriptLines.add("echo RUN_ONCE_TEST_START");
                scriptLines.add("echo Timestamp: %DATE% %TIME%");
                scriptLines.add("echo RUN_ONCE_TEST_END");
                scriptLines.add("exit /b 0");
            } else {
                scriptLines.add("#!/bin/bash");
                scriptLines.add("echo \"RUN_ONCE_TEST_START\"");
                scriptLines.add("echo \"Timestamp: $(date)\"");
                scriptLines.add("echo \"RUN_ONCE_TEST_END\"");
                scriptLines.add("exit 0");
            }
            Files.write(scriptFile, scriptLines, StandardCharsets.UTF_8);
            if (!IS_WINDOWS) {
                scriptFile.toFile().setExecutable(true);
            }

            // Create default task configuration file
            JSONObject taskConfig = new JSONObject();
            taskConfig.put("taskName", "default_task");
            taskConfig.put("taskScript", scriptFile.toString());
            taskConfig.put("taskScriptType", "SHELL");
            taskConfig.put("taskRunPolicy", "RUN_ONCE");
            taskConfig.put("taskFailPolicy", "STOP");
            taskConfig.put("taskParallelPolicy", "SEQUENTIAL");
            taskConfig.put("taskTimeout", 30);
            taskConfig.put("taskEnabled", true);
            taskConfig.put("taskGroup", "default");
            taskConfig.put("taskStartupOrder", 1);
            taskConfig.put("taskDescription", "Default task for RUN_ONCE test");

            JSONObject configRoot = new JSONObject();
            configRoot.put("tasks", List.of(taskConfig));

            Path configFile = confDir.resolve("defaultSchedulerTask_" + TEST_PROJECT_TYPE + ".json");
            Files.writeString(configFile, configRoot.toJSONString(), StandardCharsets.UTF_8);

            if (Files.exists(scriptFile) && Files.exists(configFile)) {
                System.out.println("   ✓ Test project directory and scripts created successfully");
                System.out.println("     Project path: " + projectsDir);
                markTestPassed("Create test project directory structure");
            } else {
                throw new IOException("File creation failed");
            }
        } catch (Exception e) {
            System.out.println("   ✗ Create test project directory structure failed: " + e.getMessage());
            markTestFailed("Create test project directory structure", e.getMessage());
            throw e;
        }
    }

    /**
     * Test 2: Verify metadata tables are auto-initialized.
     */
    private static void testVerifyMetaInitialization() throws Exception {
        System.out.println("Test 2: Verify metadata tables are auto-initialized...");

        try {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM sysaux.v$scheduler_run");
            if (rs.next()) {
                System.out.println("   ✓ Metadata tables auto-initialized, v$scheduler_run table is queryable");
            }
            rs.close();
            stmt.close();
            markTestPassed("Verify metadata tables are auto-initialized");
        } catch (Exception e) {
            System.out.println("   ✗ Metadata table initialization verification failed: " + e.getMessage());
            markTestFailed("Verify metadata tables are auto-initialized", e.getMessage());
            throw e;
        }
    }

    /**
     * Test 3: Create Run via HTTP.
     */
    private static String testCreateRunViaHttp() throws Exception {
        System.out.println("Test 3: Create Run via HTTP...");

        try {
            JSONObject body = new JSONObject();
            body.put("projectType", TEST_PROJECT_TYPE);
            body.put("configJson", "{\"description\":\"Test run via HTTP\"}");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/create"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                JSONObject json = JSON.parseObject(response.body());
                String runId = json.getString("runId");
                String status = json.getString("status");
                System.out.println("   ✓ Run created successfully: runId=" + runId + ", status=" + status);
                System.out.println("     Project type: " + json.getString("projectType"));
                markTestPassed("Create Run via HTTP");
                return runId;
            } else {
                throw new RuntimeException("Create Run failed, HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ Create Run via HTTP failed: " + e.getMessage());
            markTestFailed("Create Run via HTTP", e.getMessage());
            throw e;
        }
    }

    /**
     * Test 4: Query Run via HTTP.
     */
    private static void testGetRunViaHttp(String runId) throws Exception {
        System.out.println("Test 4: Query Run via HTTP...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                String retrievedRunId = json.getString("runId");
                String status = json.getString("status");
                System.out.println("   ✓ Run queried successfully: runId=" + retrievedRunId + ", status=" + status);
                markTestPassed("Query Run via HTTP");
            } else {
                throw new RuntimeException("Query Run failed, HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ Query Run via HTTP failed: " + e.getMessage());
            markTestFailed("Query Run via HTTP", e.getMessage());
        }
    }

    /**
     * Test 5: Start Run via HTTP.
     */
    private static void testStartRunViaHttp(String runId) throws Exception {
        System.out.println("Test 5: Start Run via HTTP...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/start"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                System.out.println("   ✓ Run started successfully: " + json.getString("message"));
                System.out.println("     Tasks scheduled: " + json.getIntValue("tasksScheduled"));
                markTestPassed("Start Run via HTTP");
            } else {
                throw new RuntimeException("Start Run failed, HTTP " + response.statusCode() + ": " + response.body());
            }

            // Wait for task execution
            System.out.println("    Waiting for task execution (3 seconds)...");
            Thread.sleep(3000);

        } catch (Exception e) {
            System.out.println("   ✗ Start Run via HTTP failed: " + e.getMessage());
            markTestFailed("Start Run via HTTP", e.getMessage());
        }
    }

    /**
     * Test 6: List Runs via HTTP.
     */
    private static void testListRunsViaHttp() throws Exception {
        System.out.println("Test 6: List Runs via HTTP...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/list"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                var runs = json.getJSONArray("runs");
                System.out.println("   ✓ Runs listed successfully, count: " + runs.size());
                for (int i = 0; i < runs.size(); i++) {
                    JSONObject run = runs.getJSONObject(i);
                    System.out.println("   - " + run.getString("runId") + " [" + run.getString("status") + "]");
                }
                markTestPassed("List Runs via HTTP");
            } else {
                throw new RuntimeException("List Runs failed, HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ List Runs via HTTP failed: " + e.getMessage());
            markTestFailed("List Runs via HTTP", e.getMessage());
        }
    }

    /**
     * Test 7: Stop Run via HTTP.
     */
    private static void testStopRunViaHttp(String runId) throws Exception {
        System.out.println("Test 7: Stop Run via HTTP...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/stop"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                System.out.println("   ✓ Run stopped successfully: " + json.getString("message"));
                markTestPassed("Stop Run via HTTP");
            } else {
                throw new RuntimeException("Stop Run failed, HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ Stop Run via HTTP failed: " + e.getMessage());
            markTestFailed("Stop Run via HTTP", e.getMessage());
        }
    }

    /**
     * Test 8: Drop Run via HTTP.
     */
    private static void testDropRunViaHttp(String runId) throws Exception {
        System.out.println("Test 8: Drop Run via HTTP...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/drop"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                System.out.println("   ✓ Run dropped successfully: " + json.getString("message"));
                markTestPassed("Drop Run via HTTP");
            } else {
                throw new RuntimeException("Drop Run failed, HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ Drop Run via HTTP failed: " + e.getMessage());
            markTestFailed("Drop Run via HTTP", e.getMessage());
        }
    }

    /**
     * Setup test environment.
     */
    private static void setup() throws Exception {
        System.out.println("Setting up Scheduler RUN_ONCE end-to-end test environment...\n");

        // 1. Create temporary working directory and project directory
        testWorkDir = Files.createTempDirectory("scheduler_test_once_");
        testProjectDir = Files.createTempDirectory("scheduler_test_once_projects_");
        System.out.println("Test working directory: " + testWorkDir);
        System.out.println("Test project directory: " + testProjectDir);

        // 2. Create DuckDB in-memory database connection
        conn = DriverManager.getConnection("jdbc:duckdb:");
        conn.setAutoCommit(true);
        System.out.println("DuckDB in-memory database connection created successfully");

        // Create sysaux schema
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS sysaux");
            System.out.println("sysaux schema created successfully");
        }

        // 3. Create Logger
        Logger pluginLogger = LoggerFactory.getLogger(PluginScheduler.class);

        // 4. Initialize metadata layer
        meta = new SchedulerMeta(conn, pluginLogger);
        meta.initializeTables();
        System.out.println("SchedulerMeta initialized (metadata tables auto-created)");

        // 5. Initialize Quartz scheduler
        quartzScheduler = new DynamicQuartzScheduler(pluginLogger);
        quartzScheduler.start();
        System.out.println("DynamicQuartzScheduler started successfully");

        // 6. Start Javalin HTTP server
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
        });
        app.start(TEST_PORT);

        // 7. Register REST routes
        SchedulerController controller = new SchedulerController(
                app, meta, quartzScheduler, pluginLogger,
                testWorkDir.toString(), testProjectDir.toString());
        controller.registerRoutes();
        System.out.println("Javalin HTTP server started successfully, port: " + TEST_PORT);

        // 8. Create HTTP client
        httpClient = HttpClient.newHttpClient();

        System.out.println("\nTest environment setup complete\n");
    }

    /**
     * Cleanup test environment.
     */
    private static void teardown() {
        System.out.println("\nCleaning up test environment...");

        try {
            // Stop Javalin
            if (app != null) {
                app.stop();
                System.out.println("Javalin server stopped");
            }

            // Stop Quartz scheduler
            if (quartzScheduler != null) {
                quartzScheduler.shutdown();
                System.out.println("Quartz scheduler stopped");
            }

            // Close database connection
            if (conn != null) {
                conn.close();
                System.out.println("Database connection closed");
            }

            // Clean up temporary directories
            if (testWorkDir != null) {
                deleteDirectory(testWorkDir.toFile());
                System.out.println("Temporary directory cleaned: " + testWorkDir);
            }
            if (testProjectDir != null) {
                deleteDirectory(testProjectDir.toFile());
                System.out.println("Temporary project directory cleaned: " + testProjectDir);
            }

            System.out.println("Test environment cleanup complete");
        } catch (Exception e) {
            System.err.println("Exception during cleanup: " + e.getMessage());
        }
    }

    /**
     * Recursively delete a directory.
     */
    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * Mark a test as passed.
     */
    private static void markTestPassed(String testName) {
        System.out.println("   ✓ " + testName + ": Passed");
        testsPassed++;
    }

    /**
     * Mark a test as failed.
     */
    private static void markTestFailed(String testName, String reason) {
        System.out.println("   ✗ " + testName + ": Failed - " + reason);
        testsFailed++;
    }

    /**
     * Mark a test as skipped.
     */
    private static void markTestSkipped(String testName, String reason) {
        System.out.println("   - " + testName + ": Skipped - " + reason);
        testsSkipped++;
    }

    /**
     * Print test result summary.
     */
    private static void printTestSummary() {
        System.out.println("\n============================================");
        System.out.println("  Scheduler RUN_ONCE End-to-End Test Results");
        System.out.println("============================================");
        System.out.println("  Tests Passed: " + testsPassed);
        System.out.println("  Tests Failed: " + testsFailed);
        System.out.println("  Tests Skipped: " + testsSkipped);
        System.out.println("  Total Tests: " + (testsPassed + testsFailed + testsSkipped));
        System.out.println();

        if (testsFailed == 0) {
            System.out.println("  ✓ All RUN_ONCE end-to-end tests passed!");
        } else {
            System.out.println("  ✗ " + testsFailed + " test(s) failed, please check the output above.");
        }
        System.out.println("============================================");
    }
}
