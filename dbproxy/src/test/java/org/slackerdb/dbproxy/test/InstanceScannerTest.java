package org.slackerdb.dbproxy.test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slackerdb.common.utils.OSUtil;
import org.slackerdb.dbproxy.instance.InstancePaths;
import org.slackerdb.dbproxy.instance.InstanceProbe;
import org.slackerdb.dbproxy.instance.InstanceScanner;
import org.slackerdb.dbproxy.instance.InstanceState;
import org.slackerdb.dbproxy.instance.ObservedInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceScannerTest {

    @TempDir
    static Path root;

    private static HttpServer healthServer;

    @BeforeAll
    static void startHealthServer() throws IOException {
        healthServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        healthServer.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        healthServer.start();
    }

    @AfterAll
    static void stopHealthServer() {
        if (healthServer != null) {
            healthServer.stop(0);
        }
    }

    private static void writeConf(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(InstancePaths.CONF_FILE), content, StandardCharsets.UTF_8);
    }

    private static InstanceScanner scanner() {
        return new InstanceScanner(new InstanceProbe(Duration.ofMillis(500)));
    }

    @Test
    @DisplayName("scan: stopped / running / broken instances are reported with observed state")
    void scanDerivesObservedState() throws Exception {
        Path stopped = root.resolve("stopped");
        writeConf(stopped, "data=stopped\nport=31001\nport_x=-1\n");

        Path running = root.resolve("running");
        writeConf(running, "data=running\nport=31002\nport_x="
                + healthServer.getAddress().getPort() + "\n");

        Path broken = root.resolve("broken");
        writeConf(broken, "data=broken\nport=0\nport_x=31003\n");

        Map<String, ObservedInstance> instances = scanner().scan(root);

        assertEquals(InstanceState.STOPPED, instances.get("stopped").state());
        assertEquals(InstanceState.RUNNING, instances.get("running").state(),
                "/health UP is authoritative even without a pid file");
        assertEquals(InstanceState.UNKNOWN, instances.get("broken").state(),
                "port=0 cannot be routed, so the instance must not be guessed as healthy");
        assertNotNull(instances.get("broken").error());
    }

    @Test
    @DisplayName("scan: directories without instance.conf are ignored, not deleted")
    void ignoresForeignDirectories() throws Exception {
        Path foreign = root.resolve("foreign");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("notes.txt"), "not an instance", StandardCharsets.UTF_8);

        Map<String, ObservedInstance> instances = scanner().scan(root);

        assertFalse(instances.containsKey("foreign"));
        assertTrue(Files.exists(foreign), "the scanner must never delete directories it does not understand");
        assertTrue(Files.exists(foreign.resolve("notes.txt")));
    }

    @Test
    @DisplayName("scan: .tmp-* and .trash-* are ignored, and removed when gc is requested")
    void garbageCollectsTemporaryDirectories() throws Exception {
        Path temp = root.resolve(".tmp-crashed-123");
        writeConf(temp, "data=crashed\nport=31004\nport_x=-1\n");
        Path trash = root.resolve(".trash-old-456");
        writeConf(trash, "data=old\nport=31005\nport_x=-1\n");

        Map<String, ObservedInstance> beforeGc = scanner().scan(root, false);
        assertFalse(beforeGc.containsKey(".tmp-crashed-123"));
        assertTrue(Files.exists(temp), "without gc the directory is left alone");

        Map<String, ObservedInstance> afterGc = scanner().scan(root, true);
        assertFalse(afterGc.containsKey(".tmp-crashed-123"));
        assertFalse(Files.exists(temp));
        assertFalse(Files.exists(trash));
    }

    @Test
    @DisplayName("scan: aliases that collide ignoring case are both marked UNKNOWN")
    void caseCollisionsAreRejected() throws Exception {
        Assumptions.assumeFalse(OSUtil.isWindows(),
                "Windows cannot create two directories differing only by case");

        Path upper = root.resolve("Dup");
        writeConf(upper, "data=dup1\nport=31006\nport_x=-1\n");
        Path lower = root.resolve("dup");
        writeConf(lower, "data=dup2\nport=31007\nport_x=-1\n");

        Map<String, ObservedInstance> instances = scanner().scan(root);

        assertEquals(InstanceState.UNKNOWN, instances.get("Dup").state());
        assertEquals(InstanceState.UNKNOWN, instances.get("dup").state());
        assertNotNull(instances.get("dup").error());
    }

    @Test
    @DisplayName("probe: a stale pid file does not make a stopped instance look alive")
    void stalePidIsNotAlive() throws Exception {
        Path dir = root.resolve("stale");
        writeConf(dir, "data=stale\nport=31008\nport_x=-1\npid=instance.pid\n");
        // 一个几乎不可能存在的 pid
        Files.writeString(dir.resolve(InstancePaths.PID_FILE), "999999", StandardCharsets.UTF_8);

        ObservedInstance observed = scanner().probe("stale", dir);
        assertEquals(InstanceState.STOPPED, observed.state());
        assertNull(observed.pid());
    }
}
