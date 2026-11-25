package gov.nih.nci.backendapi.opensearchresponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryESServiceDocValuesTest {
    private static final String[][] PROPERTIES = {
        {"pid", "id"}, {"id", "id"}, {"diagnosis", "diagnosis_str"},
        {"categories", "diagnosis_category"}, {"age", "age"}, {"missing", "missing"}
    };
    private InventoryESService service;
    private RestClient client;

    @BeforeEach
    void setUp() throws Exception {
        service = InventoryESServiceTestSupport.newService();
        client = mock(RestClient.class);
        InventoryESServiceTestSupport.setClientForTest(service, client);
    }

    @AfterEach
    void tearDown() throws Exception {
        InventoryESServiceTestSupport.closeClient(service);
    }

    @Test
    void projectsAliasesScalarsListsEmptyAndMissingValuesWithoutSource() throws Exception {
        JsonObject response = JsonParser.parseString("""
            {"hits":{"hits":[{"fields":{"id":["p1"],"diagnosis_str":["A; B"],
              "diagnosis_category":["one"],"age":[123]}},
              {"fields":{"diagnosis_str":[""],"diagnosis_category":[]}}, {}]}}
            """).getAsJsonObject();
        var rows = service.collectDocValuesPage(response, PROPERTIES, Set.of("categories"), 10);
        assertEquals(3, rows.size());
        assertEquals("p1", rows.get(0).get("pid"));
        assertEquals("p1", rows.get(0).get("id"));
        assertEquals("A; B", rows.get(0).get("diagnosis"));
        assertEquals(List.of("one"), rows.get(0).get("categories"));
        assertEquals("123", rows.get(0).get("age"));
        assertNull(rows.get(0).get("missing"));
        assertEquals("", rows.get(1).get("diagnosis"));
        assertNull(rows.get(1).get("categories"));
        assertTrue(rows.get(2).values().stream().allMatch(value -> value == null));
    }

    @Test
    void doesNotSilentlyDiscardMultipleValuesForScalarProperty() {
        var response = JsonParser.parseString("""
            {"hits":{"hits":[{"fields":{"diagnosis_str":["A","B"]}}]}}
            """).getAsJsonObject();
        assertThrows(IOException.class,
            () -> service.collectDocValuesPage(response, PROPERTIES, Set.of("categories"), 1));
    }

    @Test
    void usesFromSizeWithinResultWindow() throws Exception {
        var response = InventoryESServiceTestSupport.mockJsonResponse(200, page(null, 3, 2).toString());
        when(client.performRequest(any())).thenReturn(response);
        var query = query();
        var rows = service.collectDocValuesPage(new Request("GET", "/participants_table/_search"),
            query, PROPERTIES, Set.of("categories"), 2, 3);
        assertEquals(List.of("p3", "p4"), rows.stream().map(row -> row.get("id")).toList());
        var captor = ArgumentCaptor.forClass(Request.class);
        verify(client).performRequest(captor.capture());
        var body = JsonParser.parseString(EntityUtils.toString(captor.getValue().getEntity())).getAsJsonObject();
        assertFalse(body.get("_source").getAsBoolean());
        assertEquals(2, body.get("size").getAsInt());
        assertEquals(3, body.get("from").getAsInt());
        assertEquals("id", body.getAsJsonArray("docvalue_fields").get(0).getAsString());
    }

    @Test
    void scrollsAcrossPageBoundaryAndClearsLatestScrollId() throws Exception {
        var first = InventoryESServiceTestSupport.mockJsonResponse(200, page("s1", 0, 10000).toString());
        var second = InventoryESServiceTestSupport.mockJsonResponse(200, page("s2", 10000, 3).toString());
        var cleared = InventoryESServiceTestSupport.mockJsonResponse(200, "{}");
        when(client.performRequest(any())).thenReturn(first, second, cleared);
        var query = query();
        query.put("from", 7);
        var rows = service.collectDocValuesPage(new Request("GET", "/participants_table/_search"),
            query, PROPERTIES, Set.of("categories"), 3, 9999);
        assertEquals(List.of("p9999", "p10000", "p10001"),
            rows.stream().map(row -> row.get("id")).toList());
        var captor = ArgumentCaptor.forClass(Request.class);
        verify(client, times(3)).performRequest(captor.capture());
        var requests = captor.getAllValues();
        assertEquals("1m", requests.get(0).getParameters().get("scroll"));
        assertFalse(query.containsKey("from"));
        assertEquals("POST", requests.get(1).getMethod());
        assertEquals("DELETE", requests.get(2).getMethod());
        assertTrue(EntityUtils.toString(requests.get(2).getEntity()).contains("s2"));
    }

    @Test
    void clearsScrollWhenNextRequestFails() throws Exception {
        var first = InventoryESServiceTestSupport.mockJsonResponse(200, page("s1", 0, 1).toString());
        var cleared = InventoryESServiceTestSupport.mockJsonResponse(200, "{}");
        when(client.performRequest(any())).thenReturn(first)
            .thenThrow(new IOException("search failed")).thenReturn(cleared);
        var exception = assertThrows(IOException.class, () -> service.collectDocValuesPage(
            new Request("GET", "/participants_table/_search"), query(), PROPERTIES,
            Set.of("categories"), 1, 10000));
        assertEquals("search failed", exception.getMessage());
        var captor = ArgumentCaptor.forClass(Request.class);
        verify(client, times(3)).performRequest(captor.capture());
        assertEquals("DELETE", captor.getAllValues().get(2).getMethod());
    }

    @Test
    void stopsAtEndOfResultsAndHandlesZeroSizeWithoutRequest() throws Exception {
        assertEquals(List.of(), service.collectDocValuesPage(new Request("GET", "/participants_table/_search"),
            query(), PROPERTIES, Set.of("categories"), 0, 0));
        verifyNoInteractions(client);
        var empty = InventoryESServiceTestSupport.mockJsonResponse(200, page("s1", 0, 0).toString());
        var cleared = InventoryESServiceTestSupport.mockJsonResponse(200, "{}");
        when(client.performRequest(any())).thenReturn(empty, cleared);
        assertEquals(List.of(), service.collectDocValuesPage(new Request("GET", "/participants_table/_search"),
            query(), PROPERTIES, Set.of("categories"), 1, 10000));
        verify(client, times(2)).performRequest(any());
    }

    private static Map<String, Object> query() {
        return new HashMap<>(Map.of("_source", false, "docvalue_fields", List.of("id")));
    }

    private static JsonObject page(String scrollId, int start, int count) {
        JsonObject response = new JsonObject();
        if (scrollId != null) {
            response.addProperty("_scroll_id", scrollId);
        }
        JsonArray hits = new JsonArray();
        for (int i = start; i < start + count; i++) {
            hits.add(InventoryESServiceTestSupport.GSON.toJsonTree(
                Map.of("fields", Map.of("id", List.of("p" + i)))));
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("hits", hits);
        response.add("hits", wrapper);
        return response;
    }
}
