package gov.nih.nci.integration;

import com.google.gson.JsonObject;
import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Container-backed integration tests for the backend's OpenSearch connection and basic querying.
 * Test naming follows the {@code *IntegrationTest.java} pattern used by Maven Failsafe.
 */
public class OpenSearchIntegrationTest {

    private static final String DEV_CONTAINER_ES_HOST = "ccdi-integrated-opensearch-1";

    private InventoryESService inventoryESService;
    private String testIndex;
    
    @BeforeEach
    public void setup() throws Exception {
        // GitHub Actions supplies localhost explicitly; the fallback supports this repository's dev container.
        String defaultHost = Files.exists(Path.of("/.dockerenv"))
            ? DEV_CONTAINER_ES_HOST
            : "localhost";
        String esHost = System.getenv().getOrDefault("ES_HOST", defaultHost);
        int esPort = Integer.parseInt(System.getenv().getOrDefault("ES_PORT", "9200"));
        String esScheme = System.getenv().getOrDefault("ES_SCHEME", "http");

        inventoryESService = InventoryESServiceTestSupport.newService(esHost, esPort, esScheme);
    }
    
    @AfterEach
    public void teardown() throws Exception {
        if (inventoryESService != null) {
            try {
                if (testIndex != null) {
                    inventoryESService.send(new Request(
                            "DELETE",
                            "/" + testIndex + "?ignore_unavailable=true"));
                }
            } finally {
                InventoryESServiceTestSupport.closeClient(inventoryESService);
            }
        }
    }

    /** Verifies that the backend service can reach the container and parse its health response. */
    @Test
    public void connectsToOpenSearchContainer() throws Exception {
        JsonObject health = inventoryESService.send(new Request("GET", "/_cluster/health"));

        assertNotNull(health);
        assertNotNull(health.get("cluster_name"));
        assertNotEquals("red", health.get("status").getAsString());
    }

    /** Verifies that the backend can index fixtures and execute match-all and term count queries. */
    @Test
    public void indexesAndQueriesDocuments() throws Exception {
        testIndex = "ccdi-backend-integration-" + UUID.randomUUID();

        Request createIndex = new Request("PUT", "/" + testIndex);
        createIndex.setJsonEntity("""
                {
                  "mappings": {
                    "properties": {
                      "study_id": {"type": "keyword"},
                      "participant_count": {"type": "integer"}
                    }
                  }
                }
                """);
        JsonObject createResponse = inventoryESService.send(createIndex);
        assertEquals(testIndex, createResponse.get("index").getAsString());

        String bulkBody = """
                {"index":{"_index":"%s","_id":"study-1"}}
                {"study_id":"STUDY-1","participant_count":5}
                {"index":{"_index":"%s","_id":"study-2"}}
                {"study_id":"STUDY-2","participant_count":8}
                """.formatted(testIndex, testIndex);
        Request bulkRequest = new Request("POST", "/_bulk?refresh=wait_for");
        bulkRequest.setEntity(new StringEntity(
                bulkBody,
                ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
        JsonObject bulkResponse = inventoryESService.send(bulkRequest);
        assertFalse(bulkResponse.get("errors").getAsBoolean());

        int allDocuments = inventoryESService.getCount(
                Map.of("query", Map.of("match_all", Map.of())),
                testIndex);
        int selectedStudy = inventoryESService.getCount(
                Map.of("query", Map.of("term", Map.of("study_id", "STUDY-2"))),
                testIndex);

        assertEquals(2, allDocuments);
        assertEquals(1, selectedStudy);
    }
}
