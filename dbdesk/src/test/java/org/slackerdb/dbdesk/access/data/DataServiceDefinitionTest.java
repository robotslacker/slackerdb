package org.slackerdb.dbdesk.access.data;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 服务端列表接口的两种历史键名（{@code parameter} / {@code parameters}）都要能读。
 */
class DataServiceDefinitionTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesObjectFormParameterMap() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "serviceName": "getUser",
                  "serviceVersion": "v1",
                  "serviceType": "GET",
                  "category": "users",
                  "sql": "select * from users where id = ${id}",
                  "parameter": {"id": "1"}
                }
                """);

        DataServiceDefinition definition = DataServiceDefinition.fromJson(node);

        assertEquals("getUser", definition.serviceName());
        assertEquals("v1", definition.serviceVersion());
        assertEquals("GET", definition.serviceType());
        assertEquals(1, definition.parameters().size());
        assertEquals("id", definition.parameters().get(0).name());
        assertEquals("1", definition.parameters().get(0).defaultValue());
    }

    @Test
    void parsesArrayFormParameters() throws Exception {
        JsonNode node = json.readTree("""
                {
                  "serviceName": "addUser",
                  "serviceVersion": "v1",
                  "serviceType": "POST",
                  "parameters": [{"name": "id", "defaultValue": "2"}]
                }
                """);

        DataServiceDefinition definition = DataServiceDefinition.fromJson(node);

        assertEquals(1, definition.parameters().size());
        assertEquals("id", definition.parameters().get(0).name());
        assertEquals("2", definition.parameters().get(0).defaultValue());
    }
}
