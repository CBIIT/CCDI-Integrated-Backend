package gov.nih.nci.integration;

import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple integration test for OpenSearch connectivity.
 * This lightweight test verifies OpenSearch is accessible without starting the full Spring application.
 * 
 * Test naming follows *IntegrationTest.java pattern for maven-failsafe-plugin.
 */
public class OpenSearchIntegrationTest {

    private static final String DEV_CONTAINER_ES_HOST = "ccdi-integrated-opensearch-1";
    private static final List<String> REQUIRED_INDICES = List.of(
        "cohorts",
        "participants_table",
        "files_table",
        "model_nodes"
    );

    private RestClient restClient;
    
    @BeforeEach
    public void setup() {
        // Get OpenSearch connection details from environment variables (set in GitHub Actions)
        String defaultHost = Files.exists(Path.of("/.dockerenv"))
            ? DEV_CONTAINER_ES_HOST
            : "localhost";
        String esHost = System.getenv().getOrDefault("ES_HOST", defaultHost);
        int esPort = Integer.parseInt(System.getenv().getOrDefault("ES_PORT", "9200"));
        String esScheme = System.getenv().getOrDefault("ES_SCHEME", "http");
        
        // Create OpenSearch REST client
        restClient = RestClient.builder(
            new HttpHost(esHost, esPort, esScheme)
        ).build();
    }
    
    @AfterEach
    public void teardown() throws Exception {
        if (restClient != null) {
            restClient.close();
        }
    }
    
    /**
     * Verify OpenSearch is accessible and responding
     */
    @Test
    public void testOpenSearchIsAccessible() throws Exception {
        // Create a simple GET request to the cluster health endpoint
        Request request = new Request("GET", "/_cluster/health");
        
        // Execute the request
        Response response = restClient.performRequest(request);
        
        // Verify we got a successful response
        assertNotNull(response, "Response should not be null");
        assertEquals(200, response.getStatusLine().getStatusCode(), "OpenSearch should return 200 OK");
        
    }

    /**
     * Verify the indices used by the main Integrated backend data paths exist.
     */
    @Test
    public void testRequiredCcdiIndicesExist() throws Exception {
        Request request = new Request("GET", "/_cat/indices?h=index&format=text&s=index");
        Response response = restClient.performRequest(request);

        assertEquals(200, response.getStatusLine().getStatusCode());
        String responseBody = EntityUtils.toString(response.getEntity());
        assertNotNull(responseBody);

        List<String> indices = responseBody.lines()
            .map(String::trim)
            .filter(index -> !index.isEmpty())
            .toList();

        for (String requiredIndex : REQUIRED_INDICES) {
            assertTrue(
                indices.contains(requiredIndex),
                () -> "Expected OpenSearch index '" + requiredIndex + "' but found " + indices
            );
        }
    }
}

