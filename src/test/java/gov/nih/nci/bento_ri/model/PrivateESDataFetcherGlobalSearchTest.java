package gov.nih.nci.bento_ri.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherGlobalSearchTest {

    @Mock
    private InventoryESService inventoryESService;

    @Test
    void globalSearchQueriesModelIndexesAndReturnsCombinedHits() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String endpoint = request.getEndpoint();
            JsonObject body = new JsonObject();
            if (endpoint.endsWith("/_count")) {
                body.addProperty("count", endpoint.contains("model_") ? 1 : 0);
                return body;
            }
            JsonObject hits = new JsonObject();
            hits.add("hits", new JsonArray());
            body.add("hits", hits);
            return body;
        });
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                ArgumentMatchers.<List<Map<String, Object>>>any(),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String endpoint = request.getEndpoint();
            if ("/model_nodes/_search".equals(endpoint)) {
                return List.of(mutableMap(
                        "node", "participant",
                        "highlight", "participant"
                ));
            }
            if ("/model_properties/_search".equals(endpoint)) {
                return List.of(mutableMap(
                        "node", "participant",
                        "property", "participant_id",
                        "property_description", "Unique participant identifier",
                        "property_type", "string",
                        "property_required", "true"
                ));
            }
            if ("/model_values/_search".equals(endpoint)) {
                return List.of(mutableMap(
                        "node", "participant",
                        "property", "sex_at_birth",
                        "value", "Male"
                ));
            }
            return List.of();
        });

        Map<String, Object> result = invokeGlobalSearch("participant");

        assertEquals(3, result.get("model_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modelHits = (List<Map<String, Object>>) result.get("model");
        assertEquals(3, modelHits.size());
        assertEquals("node", modelHits.get(0).get("category_type"));
        assertEquals("participant", modelHits.get(0).get("node"));
        assertEquals("property", modelHits.get(1).get("category_type"));
        assertEquals("participant_id", modelHits.get(1).get("property"));
        assertEquals("value", modelHits.get(2).get("category_type"));
        assertEquals("Male", modelHits.get(2).get("value"));
    }

    @Test
    void globalSearchUsesPhrasePrefixForModelFields() throws Exception {
        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        Method method = PrivateESDataFetcher.class.getDeclaredMethod("getGlobalSearchQuery", String.class, Map.class);
        method.setAccessible(true);
        Map<String, Object> category = Map.of(
                "search_field", List.of("node"),
                "category_type", "node"
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) method.invoke(dataFetcher, "participant", category);
        String json = new com.google.gson.Gson().toJson(query);
        assertTrue(json.contains("match_phrase_prefix"));
        assertTrue(json.contains("\"node\""));
        assertTrue(json.contains("participant"));
    }

    @Test
    void globalSearchContinuesWhenModelIndexesAreMissing() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String endpoint = request.getEndpoint();
            if (endpoint.contains("model_")) {
                throw new IOException("no such index [model_nodes]");
            }
            JsonObject body = new JsonObject();
            if (endpoint.endsWith("/_count")) {
                body.addProperty("count", 0);
                return body;
            }
            JsonObject hits = new JsonObject();
            hits.add("hits", new JsonArray());
            body.add("hits", hits);
            return body;
        });
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                ArgumentMatchers.<List<Map<String, Object>>>any(),
                anyInt(),
                anyInt()
        )).thenReturn(List.of());

        Map<String, Object> result = invokeGlobalSearch("participant");

        assertEquals(0, result.get("model_count"));
        assertTrue(((List<?>) result.get("model")).isEmpty());
        assertEquals(0, result.get("participant_count"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeGlobalSearch(String input) throws Exception {
        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        Method method = PrivateESDataFetcher.class.getDeclaredMethod("globalSearch", Map.class);
        method.setAccessible(true);
        Map<String, Object> params = new HashMap<>();
        params.put("input", input);
        params.put("first", 10);
        params.put("offset", 0);
        try {
            return (Map<String, Object>) method.invoke(dataFetcher, params);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
