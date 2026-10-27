package org.slackerdb.dbproxy.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbproxy.instance.InstanceConf;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceConfTest {

    private static final String SAMPLE = """
            # generated
            data=sales
            data_dir=data
            port=31001
            port_x=31002
            pid=instance.pid
            log=logs/server.log
            bind=127.0.0.1
            daemon=false
            advertise_address=
            access_mode=READ_WRITE
            """;

    @Test
    @DisplayName("parse: comments/blank lines are ignored and keys are case-insensitive")
    void parsesKeyValuePairs() throws Exception {
        InstanceConf conf = InstanceConf.parse(SAMPLE);

        assertEquals("sales", conf.dataName());
        assertEquals(31001, conf.port());
        assertEquals(31002, conf.portX());
        assertEquals("instance.pid", conf.pidFile());
        assertEquals("127.0.0.1", conf.bind());
        assertEquals("READ_WRITE", conf.accessMode());
        assertTrue(conf.isUsable());
    }

    @Test
    @DisplayName("problems: random/unroutable ports are reported instead of guessed")
    void reportsUnroutableConfiguration() throws Exception {
        InstanceConf conf = InstanceConf.parse("data=x\nport=0\nport_x=-1\n");
        List<String> problems = conf.problems();
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("port=")), problems.toString());
        assertFalse(conf.isUsable());
    }

    @Test
    @DisplayName("problems: missing data name is reported")
    void reportsMissingData() throws Exception {
        InstanceConf conf = InstanceConf.parse("port=31001\nport_x=31002\n");
        assertTrue(conf.problems().stream().anyMatch(p -> p.contains("data=")));
    }

    @Test
    @DisplayName("render: keys are written lowercase and can be parsed back")
    void renderRoundTrips() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("data", "hr");
        values.put("port", "32001");
        values.put("port_x", "32002");

        String text = InstanceConf.render(values);
        assertTrue(text.contains("data=hr"));
        assertTrue(text.contains("port=32001"));
        assertTrue(text.startsWith("#"), "the generated file should explain itself");

        InstanceConf parsed = InstanceConf.parse(text);
        assertEquals("hr", parsed.dataName());
        assertEquals(32001, parsed.port());
    }

    @Test
    @DisplayName("load: a missing file fails loudly (the scanner turns it into a skipped directory)")
    void missingFileIsAnError() {
        boolean failed = false;
        try {
            InstanceConf.load(Path.of("does-not-exist", "instance.conf"));
        } catch (Exception e) {
            failed = true;
        }
        assertTrue(failed);
    }
}
