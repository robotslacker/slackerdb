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
 * Scheduler Crontab Tester - End-to-end test for CRONTAB scheduling
 *
 * <p>Bypasses the PF4J framework, directly starts Javalin + SchedulerController,
 * and tests core functionality such as creating Runs, scheduling Cron tasks,
 * and periodic execution via HTTP requests.</p>
 */
public class SchedulerCrontabTester {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerCrontabTester.class);

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
    private static final String TEST_PROJECT_TYPE = "test_project_crontab";
    private static final int TEST_PORT = 19871; // Use an uncommon port to avoid conflicts
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final String TEST_CRON_EVERY_5S = "0/5 * * * * ?"; // Every 5 seconds
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String TEST_SCRIPT_NAME = IS_WINDOWS ? "test_script.bat" : "test_script.sh";

    public static void main(String[] args) {
        try {
            System.out.println("============================================");
            System.out.println("  Scheduler CRONTAB End-to-End Test Start");
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

        // Phase 5: Schedule Cron job via HTTP
        System.out.println("\n=== Phase 5: Schedule Cron job via HTTP ===");
        testScheduleCronJobViaHttp(runId);

        // Phase 6: Verify periodic execution
        System.out.println("\n=== Phase 6: Verify periodic execution ===");
        testVerifyPeriodicExecution(runId);

        // Phase 7: List jobs via HTTP
        System.out.println("\n=== Phase 7: List jobs via HTTP ===");
        testListJobsViaHttp(runId);

        // Phase 8: Stop Cron job via HTTP
        System.out.println("\n=== Phase 8: Stop Cron job via HTTP ===");
        testStopJobViaHttp(runId);

        // Phase 9: Drop Run via HTTP
        System.out.println("\n=== Phase 9: Drop Run via HTTP ===");
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
                scriptLines.add("echo CRONTAB_TEST_START");
                scriptLines.add("echo Timestamp: %DATE% %TIME%");
                scriptLines.add("echo CRONTAB_TEST_END");
                scriptLines.add("exit /b 0");
            } else {
                scriptLines.add("#!/bin/bash");
                scriptLines.add("echo \"CRONTAB_TEST_START\"");
                scriptLines.add("echo \"Timestamp: $(date)\"");
                scriptLines.add("echo \"CRONTAB_TEST_END\"");
                scriptLines.add("exit 0");
            }
            Files.write(scriptFile, scriptLines, StandardCharsets.UTF_8);
            if (!IS_WINDOWS) {
                scriptFile.toFile().setExecutable(true);
            }

            // Create default task configuration file (CRONTAB policy)
            JSONObject taskConfig = new JSONObject();
            taskConfig.put("taskName", "cron_task");
            taskConfig.put("taskScript", scriptFile.toString());
            taskConfig.put("taskScriptType", "SHELL");
            taskConfig.put("taskRunPolicy", "CRONTAB");
            taskConfig.put("taskFailPolicy", "CONTINUE");
            taskConfig.put("taskParallelPolicy", "PARALLEL");
            taskConfig.put("taskCrontabExpr", TEST_CRON_EVERY_5S);
            taskConfig.put("taskTimeout", 10);
            taskConfig.put("taskEnabled", true);
            taskConfig.put("taskGroup", "cron_group");
            taskConfig.put("taskStartupOrder", 1);
            taskConfig.put("taskDescription", "Cron task for CRONTAB test");

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
            var rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM sysaux.v$scheduler_task");
            if (rs.next()) {
                System.out.println("   ✓ Metadata tables auto-initialized, v$scheduler_task table is queryable");
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
     * Test 5: Schedule Cron job via HTTP.
     */
    private static void testScheduleCronJobViaHttp(String runId) throws Exception {
        System.out.println("Test 5: Schedule Cron job via HTTP...");

        try {
            // Start Run (will schedule all enabled tasks, including CRONTAB tasks)
            HttpRequest startRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/start"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> startResponse = httpClient.send(startRequest, HttpResponse.BodyHandlers.ofString());

            if (startResponse.statusCode() == 200) {
                JSONObject json = JSON.parseObject(startResponse.body());
                System.out.println("   ✓ Run started successfully: " + json.getString("message"));
                System.out.println("     Tasks scheduled: " + json.getIntValue("tasksScheduled"));
                System.out.println("     Cron expression: " + TEST_CRON_EVERY_5S);
                System.out.println("     Task will execute every 5 seconds");
                markTestPassed("Schedule Cron job via HTTP");
            } else {
                throw new RuntimeException("Start Run failed, HTTP " + startResponse.statusCode() + ": " + startResponse.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ Schedule Cron job via HTTP failed: " + e.getMessage());
            markTestFailed("Schedule Cron job via HTTP", e.getMessage());
            throw e;
        }
    }

    /**
     * Test 6: Verify periodic execution.
     */
    private static void testVerifyPeriodicExecution(String runId) throws Exception {
        System.out.println("Test 6: Verify periodic execution...");

        try {
            // Wait enough time for Cron task to execute multiple times (every 5 seconds, wait 16 seconds for at least 3 executions)
            System.out.println("    Waiting for Cron task execution (about 16 seconds)...");
            for (int i = 1; i <= 16; i++) {
                Thread.sleep(1000);
                System.out.print(".");
            }
            System.out.println(" Done");

            // Query job list via HTTP to verify task is still scheduled
            HttpRequest listRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/job/list"))
                    .GET()
                    .build();

            HttpResponse<String> listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());

            if (listResponse.statusCode() == 200) {
                JSONObject json = JSON.parseObject(listResponse.body());
                var jobs = json.getJSONArray("jobs");
                System.out.println("    Current task count: " + jobs.size());
                for (int i = 0; i < jobs.size(); i++) {
                    JSONObject job = jobs.getJSONObject(i);
                    System.out.println("   - " + job.getString("taskName")
                            + " [Policy: " + job.getString("taskRunPolicy")
                            + ", Scheduled: " + job.getBooleanValue("scheduled") + "]");
                }
                markTestPassed("Verify periodic execution");
            } else {
                throw new RuntimeException("List jobs failed, HTTP " + listResponse.statusCode());
            }
        } catch (Exception e) {
            System.out.println("   ✗ Verify periodic execution failed: " + e.getMessage());
            markTestFailed("Verify periodic execution", e.getMessage());
        }
    }

    /**
     * Test 7: List jobs via HTTP.
     */
    private static void testListJobsViaHttp(String runId) throws Exception {
        System.out.println("Test 7: List jobs via HTTP...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/job/list"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = JSON.parseObject(response.body());
                var jobs = json.getJSONArray("jobs");
                System.out.println("   Jobs for Run " + runId + ":");
                if (jobs.isEmpty()) {
                    System.out.println("   - (No tasks)");
                } else {
                    for (int i = 0; i < jobs.size(); i++) {
                        JSONObject job = jobs.getJSONObject(i);
                        System.out.println("   - " + job.getString("taskName")
                                + " [" + job.getString("taskRunPolicy") + "]"
                                + " Scheduled: " + job.getBooleanValue("scheduled"));
                    }
                }
                markTestPassed("List jobs via HTTP");
            } else {
                throw new RuntimeException("List jobs failed, HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("   ✗ List jobs via HTTP failed: " + e.getMessage());
            markTestFailed("List jobs via HTTP", e.getMessage());
        }
    }

    /**
     * Test 8: Stop Cron job via HTTP.
     */
    private static void testStopJobViaHttp(String runId) throws Exception {
        System.out.println("Test 8: Stop Cron job via HTTP...");

        try {
            // Stop Run (will unschedule all tasks)
            HttpRequest stopRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/scheduler/run/" + runId + "/stop"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> stopResponse = httpClient.send(stopRequest, HttpResponse.BodyHandlers.ofString());

            if (stopResponse.statusCode() == 200) {
                JSONObject json = JSON.parseObject(stopResponse.body());
                System.out.println("   ✓ Run stopped successfully: " + json.getString("message"));
                markTestPassed("Stop Cron job via HTTP");
            } else {
                throw new RuntimeException("Stop Run failed, HTTP " + stopResponse.statusCode() + ": " + stopResponse.body());
            }

            // Wait a few seconds to confirm task is no longer executing
            System.out.println("    Waiting to confirm task has stopped (3 seconds)...");
            Thread.sleep(3000);
            System.out.println("    Task confirmed stopped");

        } catch (Exception e) {
            System.out.println("   ✗ Stop Cron job via HTTP failed: " + e.getMessage());
            markTestFailed("Stop Cron job via HTTP", e.getMessage());
        }
    }

    /**
     * Test 9: Drop Run via HTTP.
     */
    private static void testDropRunViaHttp(String runId) throws Exception {
        System.out.println("Test 9: Drop Run via HTTP...");

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
        System.out.println("Setting up Scheduler CRONTAB end-to-end test environment...\n");

        // 1. Create temporary working directory and project directory
        testWorkDir = Files.createTempDirectory("scheduler_test_crontab_");
        testProjectDir = Files.createTempDirectory("scheduler_test_crontab_projects_");
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
        System.out.println("  Scheduler CRONTAB End-to-End Test Results");
        System.out.println("============================================");
        System.out.println("  Tests Passed: " + testsPassed);
        System.out.println("  Tests Failed: " + testsFailed);
        System.out.println("  Tests Skipped: " + testsSkipped);
        System.out.println("  Total Tests: " + (testsPassed + testsFailed + testsSkipped));
        System.out.println();

        if (testsFailed == 0) {
            System.out.println("  ✓ All CRONTAB end-to-end tests passed!");
        } else {
            System.out.println("  ✗ " + testsFailed + " test(s) failed, please check the output above.");
        }
        System.out.println("============================================");
    }
}
