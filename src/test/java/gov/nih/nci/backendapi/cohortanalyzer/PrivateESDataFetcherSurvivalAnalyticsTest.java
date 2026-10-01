package gov.nih.nci.backendapi.cohortanalyzer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.http.util.EntityUtils;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherSurvivalAnalyticsTest {

    private static final String KM_PLOT_ENDPOINT = "/km_plot_data/_search";

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
    }

    /**
     * Verifies that the KM plot resolver returns no data and does not query OpenSearch when no
     * cohort argument is supplied.
     */
    @Test
    void returnsNoKmPlotDataWhenNoCohortIsSupplied() throws Exception {
        assertTrue(invokeKmPlot(Map.of()).isEmpty());

        verifyNoInteractions(inventoryESService);
    }

    /**
     * Verifies that null, empty, and incorrectly typed cohort arguments are ignored rather than
     * producing malformed KM plot queries.
     */
    @Test
    void ignoresUnusableKmPlotCohorts() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("c1", null);
        params.put("c2", List.of());
        params.put("c3", "participant-3");

        assertTrue(invokeKmPlot(params).isEmpty());

        verify(inventoryESService, never()).collectPage(
                any(Request.class), anyMap(), any(String[][].class), anyInt(), anyInt());
    }

    /** Verifies that omitted optional cohort arguments do not create extra KM plot requests. */
    @Test
    void queriesOnlyPresentKmPlotCohorts() throws Exception {
        when(inventoryESService.collectPage(
                any(Request.class), anyMap(), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenReturn(new ArrayList<>());

        assertTrue(invokeKmPlot(Map.of("c1", List.of("participant-1"))).isEmpty());

        verify(inventoryESService).collectPage(
                any(Request.class), anyMap(), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0));
    }

    /**
     * Verifies that KM plot requests filter valid records by participant ID, sort by survival
     * time, request the public result fields, and label every returned point with its cohort.
     */
    @Test
    void returnsKmPlotPointsForEachUsableCohort() throws Exception {
        when(inventoryESService.collectPage(
                any(Request.class), anyMap(), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> query = invocation.getArgument(1);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> filters = (List<Map<String, Object>>)
                            ((Map<String, Object>) ((Map<String, Object>) query.get("query"))
                                    .get("bool")).get("filter");
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) ((Map<String, Object>) filters.get(0)
                            .get("terms")).get("id");
                    return new ArrayList<>(List.of(new HashMap<>(Map.of(
                            "id", ids.get(0),
                            "time", 120,
                            "event", 1
                    ))));
                });

        Map<String, Object> params = new HashMap<>();
        params.put("c1", List.of("participant-1"));
        params.put("c2", null);
        params.put("c3", List.of("participant-3"));

        List<Map<String, Object>> result = invokeKmPlot(params);

        assertEquals(List.of(
                Map.of("id", "participant-1", "time", 120, "event", 1, "group", "c1"),
                Map.of("id", "participant-3", "time", 120, "event", 1, "group", "c3")
        ), result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService, times(2)).collectPage(
                requestCaptor.capture(), queryCaptor.capture(), propertiesCaptor.capture(),
                eq(ESService.MAX_ES_SIZE), eq(0));

        assertTrue(requestCaptor.getAllValues().stream()
                .allMatch(request -> KM_PLOT_ENDPOINT.equals(request.getEndpoint())));
        assertEquals(Map.of("time", "asc"), queryCaptor.getAllValues().get(0).get("sort"));
        assertEquals(List.of(
                List.of("id", "id"),
                List.of("time", "time"),
                List.of("event", "event")
        ), propertyPairs(propertiesCaptor.getValue()));

        JsonObject queryJson = JsonParser.parseString(
                new com.google.gson.Gson().toJson(queryCaptor.getAllValues().get(0)))
                .getAsJsonObject();
        JsonArray filters = queryJson.getAsJsonObject("query")
                .getAsJsonObject("bool").getAsJsonArray("filter");
        assertTrue(filters.toString().contains("\"is_valid\":true"));
        assertTrue(filters.toString().contains("\"id\":[\"participant-1\"]"));
    }

    /**
     * Verifies that the risk table starts with each cohort's eligible population and subtracts
     * unique deaths cumulatively across every six-month range.
     */
    @Test
    void returnsCumulativeRiskTablesForAllCohorts() throws Exception {
        when(inventoryESService.getCount(anyMap(), eq("km_plot_data")))
                .thenReturn(10, 5, 2);
        when(inventoryESService.send(any(Request.class))).thenReturn(new JsonObject());
        when(inventoryESService.collectRangCountAggs(any(JsonObject.class), eq("cutoff_times")))
                .thenReturn(Map.of("cutoff_times", rangeCounts(2, 1, 0, 3, 0, 1)))
                .thenReturn(Map.of("cutoff_times", rangeCounts(1, 1, 1, 0, 0, 0)))
                .thenReturn(Map.of("cutoff_times", rangeCounts(0, 1, 0, 0, 0, 0)));

        Map<String, Object> result = invokeRiskTableData(Map.of(
                "c1", List.of("participant-1", "participant-2"),
                "c2", List.of("participant-3"),
                "c3", List.of("participant-4")
        ));

        assertEquals(List.of(
                "0 Months", "6 Months", "12 Months", "18 Months",
                "24 Months", "30 Months", "36 Months"
        ), result.get("timeIntervals"));

        List<Map<String, Object>> cohorts = mapList(result, "cohorts");
        assertEquals(List.of(
                Map.of("group", "0 Months", "subjects", 10),
                Map.of("group", "6 Months", "subjects", 8),
                Map.of("group", "12 Months", "subjects", 7),
                Map.of("group", "18 Months", "subjects", 7),
                Map.of("group", "24 Months", "subjects", 4),
                Map.of("group", "30 Months", "subjects", 4),
                Map.of("group", "36 Months", "subjects", 3)
        ), mapList(cohorts.get(0), "survivalData"));
        assertEquals("c2", cohorts.get(1).get("cohort"));
        assertEquals(2, mapList(cohorts.get(2), "survivalData").get(0).get("subjects"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> countQueryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(inventoryESService, times(3)).getCount(
                countQueryCaptor.capture(), eq("km_plot_data"));
        List<List<String>> expectedCohortIds = List.of(
                List.of("participant-1", "participant-2"),
                List.of("participant-3"),
                List.of("participant-4"));
        for (int index = 0; index < expectedCohortIds.size(); index++) {
            JsonObject countQuery = JsonParser.parseString(new com.google.gson.Gson()
                            .toJson(countQueryCaptor.getAllValues().get(index)))
                    .getAsJsonObject();
            assertEquals(expectedCohortIds.get(index), participantIds(countQuery));
            assertTrue(countQuery.toString().contains("\"is_valid\":true"));
        }

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService, times(3)).send(requestCaptor.capture());
        for (int index = 0; index < requestCaptor.getAllValues().size(); index++) {
            Request request = requestCaptor.getAllValues().get(index);
            assertEquals(KM_PLOT_ENDPOINT, request.getEndpoint());
            JsonObject requestBody = JsonParser.parseString(EntityUtils.toString(request.getEntity()))
                    .getAsJsonObject();
            assertEquals(expectedCohortIds.get(index), participantIds(requestBody));
            assertEquals(expectedCutoffRanges(), requestBody.getAsJsonObject("aggs")
                    .getAsJsonObject("cutoff_times")
                    .getAsJsonObject("range")
                    .getAsJsonArray("ranges"));
            String body = requestBody.toString();
            assertTrue(body.contains("\"event\":1"));
            assertTrue(body.contains("\"is_valid\":true"));
            assertTrue(body.contains("\"field\":\"time\""));
            assertTrue(body.contains("\"field\":\"id\""));
        }
    }

    private static List<String> participantIds(JsonObject query) {
        JsonArray filters = query.getAsJsonObject("query")
                .getAsJsonObject("bool").getAsJsonArray("filter");
        for (var filter : filters) {
            JsonObject filterObject = filter.getAsJsonObject();
            if (!filterObject.has("terms")) {
                continue;
            }
            JsonObject terms = filterObject.getAsJsonObject("terms");
            if (!terms.has("id")) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            terms.getAsJsonArray("id").forEach(id -> ids.add(id.getAsString()));
            return ids;
        }
        throw new AssertionError("Expected participant ID terms filter");
    }

    private static JsonArray expectedCutoffRanges() {
        return JsonParser.parseString("""
                [
                  {"key":"6 Months","from":0,"to":183},
                  {"key":"12 Months","from":183,"to":365},
                  {"key":"18 Months","from":365,"to":548},
                  {"key":"24 Months","from":548,"to":730},
                  {"key":"30 Months","from":730,"to":913},
                  {"key":"36 Months","from":913,"to":1095}
                ]
                """).getAsJsonArray();
    }

    private static JsonArray rangeCounts(int... counts) {
        String[] labels = {
                "6 Months", "12 Months", "18 Months",
                "24 Months", "30 Months", "36 Months"
        };
        JsonArray buckets = new JsonArray();
        for (int index = 0; index < counts.length; index++) {
            JsonObject bucket = new JsonObject();
            bucket.addProperty("key", labels[index]);
            JsonObject uniqueParticipants = new JsonObject();
            uniqueParticipants.addProperty("value", counts[index]);
            bucket.add("unique_participants", uniqueParticipants);
            buckets.add(bucket);
        }
        return buckets;
    }

    private static List<List<String>> propertyPairs(String[][] properties) {
        List<List<String>> result = new ArrayList<>();
        for (String[] property : properties) {
            result.add(List.of(property));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeKmPlot(Map<String, Object> params) throws Exception {
        return (List<Map<String, Object>>) invokePrivate("kMPlot", params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeRiskTableData(Map<String, Object> params) throws Exception {
        return (Map<String, Object>) invokePrivate("riskTableData", params);
    }

    private Object invokePrivate(String methodName, Map<String, Object> params) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(methodName, Map.class);
        method.setAccessible(true);
        try {
            return method.invoke(dataFetcher, params);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nestedException) {
                throw nestedException;
            }
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }
}
