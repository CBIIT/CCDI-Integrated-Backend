package gov.nih.nci.backendapi.statsbar;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherStatsBarTest {

    private static final String DIAGNOSES_ENDPOINT = "/diagnoses_table/_search";
    private static final String PARTICIPANTS_ENDPOINT = "/participants_table/_search";

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
    }

    /**
     * Verifies numberOfDiseases builds the diagnosis filter and reports the number of distinct
     * diagnosis aggregation buckets returned by OpenSearch.
     */
    @Test
    void returnsNumberOfDiseasesFromDiagnosisBuckets() throws Exception {
        Map<String, Object> params = Map.of();
        Map<String, Object> filterQuery = new HashMap<>(
                Map.of("query", Map.of("match_all", Map.of())));
        Map<String, Object> aggregationQuery = new HashMap<>(filterQuery);
        aggregationQuery.put("size", 0);
        JsonObject response = new JsonObject();
        JsonArray buckets = JsonParser.parseString("""
                [
                  {"key":"Neuroblastoma"},
                  {"key":"Osteosarcoma"},
                  {"key":"Ewing sarcoma"}
                ]
                """).getAsJsonArray();
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(Set.of()), eq(Set.of()),
                eq("nested_filters"), eq("diagnoses_table"))).thenReturn(filterQuery);
        when(inventoryESService.addNodeCountAggregations(filterQuery, "diagnosis"))
                .thenReturn(aggregationQuery);
        when(inventoryESService.send(any(Request.class))).thenReturn(response);
        when(inventoryESService.collectNodeCountAggs(response, "diagnosis"))
                .thenReturn(Map.of("diagnosis", buckets));

        Integer result = invokeCount("numberOfDiseases", params);

        assertEquals(3, result);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(requestCaptor.capture());
        assertRequest(requestCaptor.getValue(), DIAGNOSES_ENDPOINT, aggregationQuery);
    }

    /**
     * Verifies numberOfParticipants builds a participant filter and delegates the final count to
     * the participants_table count endpoint helper.
     */
    @Test
    void returnsNumberOfParticipantsFromParticipantCount() throws Exception {
        Map<String, Object> params = Map.of();
        Map<String, Object> query = new HashMap<>(
                Map.of("query", Map.of("match_all", Map.of())));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(Set.of()), eq(Set.of()),
                eq("nested_filters"), eq("participants_table"))).thenReturn(query);
        when(inventoryESService.getCount(query, "participants_table")).thenReturn(125);

        Integer result = invokeCount("numberOfParticipants", params);

        assertEquals(125, result);
        verify(inventoryESService).getCount(query, "participants_table");
    }

    /**
     * Verifies numberOfStudies builds a study filter and delegates the final count to the
     * studies_table count endpoint helper.
     */
    @Test
    void returnsNumberOfStudiesFromStudyCount() throws Exception {
        Map<String, Object> params = Map.of();
        Map<String, Object> query = new HashMap<>(
                Map.of("query", Map.of("match_all", Map.of())));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(Set.of()), eq(Set.of()),
                eq("nested_filters"), eq("studies_table"))).thenReturn(query);
        when(inventoryESService.getCount(query, "studies_table")).thenReturn(14);

        Integer result = invokeCount("numberOfStudies", params);

        assertEquals(14, result);
        verify(inventoryESService).getCount(query, "studies_table");
    }

    /**
     * Verifies numberOfMCICount restricts the participant search to the configured MCI study and
     * returns the total-hit value from participants_table.
     */
    @Test
    void returnsMciParticipantCountForConfiguredStudy() throws Exception {
        Map<String, Object> expectedParams = Map.of("study_id", List.of("phs002790"));
        Map<String, Object> query = new HashMap<>(
                Map.of("query", Map.of("match_all", Map.of())));
        JsonObject response = JsonParser.parseString("""
                {"hits":{"total":{"value":37,"relation":"eq"},"hits":[]}}
                """).getAsJsonObject();
        when(inventoryESService.buildFacetFilterQuery(
                eq(expectedParams), anySet(), eq(Set.of()), eq(Set.of()),
                eq("nested_filters"), eq("participants_table"))).thenReturn(query);
        when(inventoryESService.send(any(Request.class))).thenReturn(response);

        Integer result = invokeCount("getParticipantsCount");

        assertEquals(37, result);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(requestCaptor.capture());
        assertRequest(requestCaptor.getValue(), PARTICIPANTS_ENDPOINT, query);
    }

    private static void assertRequest(
            Request request, String expectedEndpoint, Map<String, Object> expectedQuery)
            throws Exception {
        assertEquals("GET", request.getMethod());
        assertEquals(expectedEndpoint, request.getEndpoint());
        assertNotNull(request.getEntity());
        JsonObject actualQuery = JsonParser.parseString(
                EntityUtils.toString(request.getEntity())).getAsJsonObject();
        JsonObject expectedJson = new Gson().toJsonTree(expectedQuery).getAsJsonObject();
        assertEquals(expectedJson, actualQuery);
    }

    private Integer invokeCount(String methodName, Object... arguments) throws Exception {
        Class<?>[] parameterTypes = arguments.length == 0
                ? new Class<?>[]{}
                : new Class<?>[]{Map.class};
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return (Integer) method.invoke(dataFetcher, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
