package gov.nih.nci.backendapi.opensearchresponse;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InventoryESService} HTTP helpers ({@code send}, {@code getCount},
 * {@code getBucketNames}) with a mocked OpenSearch client (no live cluster).
 */
@ExtendWith(MockitoExtension.class)
class InventoryESServiceHttpMethodsTest {

    private InventoryESService service;
    private RestClient mockClient;

    @BeforeEach
    void setUp() throws Exception {
        service = InventoryESServiceTestSupport.newService();
        mockClient = mock(RestClient.class);
        InventoryESServiceTestSupport.setClientForTest(service, mockClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        InventoryESServiceTestSupport.closeClient(service);
    }

    /** Verifies that a successful OpenSearch response is parsed into a JSON object. */
    @Test
    void returnsParsedJsonForSuccessfulRequest() throws IOException {
        Response response = InventoryESServiceTestSupport.mockResponseFromFixture(200, "count_response.json");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        JsonObject result = service.send(new Request("GET", "/participants/_count"));

        assertEquals(42, result.get("count").getAsInt());
    }

    /** Verifies that a non-200 OpenSearch status becomes an IOException containing the status. */
    @Test
    void rejectsNonSuccessfulStatus() throws IOException {
        Response response = InventoryESServiceTestSupport.mockJsonResponse(503, "{\"error\":\"unavailable\"}");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        IOException exception = assertThrows(
                IOException.class,
                () -> service.send(new Request("GET", "/participants/_search")));

        assertEquals("Elasticsearch returned code: 503", exception.getMessage());
    }

    /** Verifies that transport failures from the OpenSearch client are propagated unchanged. */
    @Test
    void propagatesTransportFailure() throws IOException {
        IOException expected = new IOException("connection refused");
        when(mockClient.performRequest(any(Request.class))).thenThrow(expected);

        IOException actual = assertThrows(
                IOException.class,
                () -> service.send(new Request("GET", "/participants/_search")));

        assertEquals(expected, actual);
    }

    /** Verifies that malformed JSON in an otherwise successful response is rejected. */
    @Test
    void rejectsMalformedJsonFromSuccessfulRequest() throws IOException {
        Response response = InventoryESServiceTestSupport.mockJsonResponse(200, "{not-json");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        assertThrows(
                JsonSyntaxException.class,
                () -> service.send(new Request("GET", "/participants/_search")));
    }

    /** Verifies the current empty-body parsing contract: an empty entity produces null JSON. */
    @Test
    void returnsNullForEmptyResponseBody() throws IOException {
        Response response = InventoryESServiceTestSupport.mockJsonResponse(200, "");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        assertNull(service.send(new Request("GET", "/participants/_search")));
    }

    /** Verifies that getCount sends the supplied query to the selected index's count endpoint. */
    @Test
    void sendsCountQueryToSelectedIndex() throws IOException {
        Response response = InventoryESServiceTestSupport.mockResponseFromFixture(200, "count_response.json");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        Map<String, Object> query = Map.of("query", Map.of("match_all", Map.of()));
        int count = service.getCount(query, "participants");

        assertEquals(42, count);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockClient).performRequest(requestCaptor.capture());
        Request captured = requestCaptor.getValue();
        assertEquals("GET", captured.getMethod());
        assertEquals("/participants/_count", captured.getEndpoint());
        assertNotNull(captured.getEntity());
    }

    /** Verifies that a count response missing its required count property is rejected. */
    @Test
    void rejectsCountResponseWithoutCount() throws IOException {
        Response response = InventoryESServiceTestSupport.mockJsonResponse(200, "{}");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        assertThrows(
                NullPointerException.class,
                () -> service.getCount(Map.of("query", Map.of("match_all", Map.of())), "participants"));
    }

    /** Verifies that getBucketNames returns only keyed term buckets from the requested endpoint. */
    @Test
    void returnsBucketNamesFromRequestedEndpoint() throws IOException {
        Response response = InventoryESServiceTestSupport.mockResponseFromFixture(200, "bucket_names_aggs.json");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        List<String> bucketNames = service.getBucketNames(
                "treatment_type",
                Map.of("race", List.of("Asian")),
                Set.of("age_at_diagnosis"),
                "participant_id",
                "participants",
                "/participants/_search");

        assertEquals(List.of("Chemotherapy", "Radiation"), bucketNames);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockClient).performRequest(requestCaptor.capture());
        Request captured = requestCaptor.getValue();
        assertEquals("/participants/_search", captured.getEndpoint());
        assertNotNull(captured.getEntity());
    }

    /** Verifies that bucket-name collection ignores malformed buckets that do not have a key. */
    @Test
    void ignoresBucketsWithoutKeys() throws IOException {
        Response response = InventoryESServiceTestSupport.mockJsonResponse(200, """
                {"aggregations":{"treatment_type":{"buckets":[
                  {"doc_count":2},
                  {"key":"Chemotherapy","doc_count":1}
                ]}}}
                """);
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        List<String> bucketNames = service.getBucketNames(
                "treatment_type",
                Map.of(),
                Set.of(),
                "participant_id",
                "participants",
                "/participants/_search");

        assertEquals(List.of("Chemotherapy"), bucketNames);
    }

    /** Verifies that a response without a buckets array produces an empty bucket-name list. */
    @Test
    void returnsNoBucketNamesWhenBucketsAreMissing() throws IOException {
        Response response = InventoryESServiceTestSupport.mockJsonResponse(
                200,
                "{\"aggregations\":{\"treatment_type\":{}}}");
        when(mockClient.performRequest(any(Request.class))).thenReturn(response);

        List<String> bucketNames = service.getBucketNames(
                "treatment_type",
                Map.of(),
                Set.of(),
                "participant_id",
                "participants",
                "/participants/_search");

        assertTrue(bucketNames.isEmpty());
    }
}
