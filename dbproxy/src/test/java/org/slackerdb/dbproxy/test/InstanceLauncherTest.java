package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.dbproxy.configuration.ServerConfiguration;
import org.slackerdb.dbproxy.instance.InstanceController;
import org.slackerdb.dbproxy.instance.InstanceException;
import org.slackerdb.dbproxy.instance.InstanceLauncher;
import org.slackerdb.dbproxy.instance.InstanceProbe;
import org.slackerdb.dbproxy.instance.InstanceRegistry;
import org.slackerdb.dbproxy.instance.InstanceScanner;
import org.slackerdb.dbproxy.instance.InstanceSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dbserver_jar} 配置与实例创建的关系。
 *
 * <p>这个参数没有默认值：缺失时不能创建实例，但已有实例的运维操作不受影响。</p>
 */
class InstanceLauncherTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("config: dbserver_jar is loaded from the proxy configuration file")
    void configurationLoadsDbserverJarPattern() throws Exception {
        Path conf = root.resolve("dbproxy.conf");
        Files.writeString(conf, "data_dir=" + root.resolve("instances") + "\n"
                + "dbserver_jar=slackerdb-dbserver-*-standalone.jar\n");

        ServerConfiguration configuration = new ServerConfiguration();
        configuration.loadConfigurationFile(conf.toString());

        assertTrue(configuration.isDbserverJarConfigured());
        assertEquals("slackerdb-dbserver-*-standalone.jar", configuration.getDbserver_Jar());
    }

    @Test
    @DisplayName("config: an invalid glob pattern is rejected while loading the configuration")
    void invalidJarPatternIsRejected() throws Exception {
        Path conf = root.resolve("broken.conf");
        Files.writeString(conf, "dbserver_jar=[\n");

        ServerConfiguration configuration = new ServerConfiguration();
        assertThrows(ServerException.class, () -> configuration.loadConfigurationFile(conf.toString()));
        assertFalse(configuration.isDbserverJarConfigured());
    }

    @Test
    @DisplayName("create: without dbserver_jar the proxy refuses to create an instance")
    void creationIsRefusedWithoutJarPattern() {
        InstanceProbe probe = new InstanceProbe();
        InstanceRegistry registry = new InstanceRegistry(root.resolve("instances"), new InstanceScanner(probe));
        InstanceController controller = new InstanceController(root.resolve("instances"), registry, probe,
                new InstanceLauncher(""), LoggerFactory.getLogger(InstanceLauncherTest.class));

        InstanceException failure = assertThrows(InstanceException.class,
                () -> controller.create(new InstanceSpec("nope", null, false, Map.of())));

        assertEquals(InstanceException.UNAVAILABLE, failure.status());
        assertTrue(failure.getMessage().contains("dbserver_jar"),
                "the failure must name the missing configuration parameter");
        assertFalse(Files.exists(root.resolve("instances").resolve("nope")),
                "a refused create must not leave anything behind");
    }

    @Test
    @DisplayName("launcher: hasJarPattern reflects the configured value")
    void hasJarPatternReflectsConfiguration() {
        assertFalse(new InstanceLauncher(null).hasJarPattern());
        assertFalse(new InstanceLauncher("  ").hasJarPattern());
        assertTrue(new InstanceLauncher("slackerdb-dbserver-*-standalone.jar").hasJarPattern());
    }
}
