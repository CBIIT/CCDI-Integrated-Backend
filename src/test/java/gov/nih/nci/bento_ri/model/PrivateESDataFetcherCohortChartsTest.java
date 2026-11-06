package gov.nih.nci.bento_ri.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherCohortChartsTest {

    @Mock
    private InventoryESService inventoryESService;

    @Test
    void cohortChartPropertyConfigurationIsLoadedFromYaml() throws Exception {
        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        Map<String, Map<String, String>> propertyConfiguration = getCohortChartPropertyConfiguration(dataFetcher);

        assertEquals(Set.of("race", "sex_at_birth", "response", "treatment_type"),
                propertyConfiguration.keySet());
        assertEquals(Map.of(
                "index", "participants_table",
                "endpoint", "/participants_table/_search",
                "cardinalityAggName", ""
        ), propertyConfiguration.get("race"));
        assertEquals(Map.of(
                "index", "treatment_responses_table",
                "endpoint", "/treatment_responses_table/_search",
                "cardinalityAggName", "pid"
        ), propertyConfiguration.get("response"));
        assertEquals(Map.of(
                "index", "treatments_table",
                "endpoint", "/treatments_table/_search",
                "cardinalityAggName", "pid"
        ), propertyConfiguration.get("treatment_type"));
    }

    @Test
    void unconfiguredPropertyIsSkippedWithoutQueryingOpenSearch() throws Exception {
        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);

        List<Map<String, Object>> result = invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1"),
                "charts", List.of(Map.of("property", "occupation", "type", "count"))
        ));

        assertTrue(result.isEmpty());
        verifyNoInteractions(inventoryESService);
    }

    @Test
    void participantUniqueChartsUseParticipantIdsAndReverseNestedCountsForBuckets() throws Exception {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(),
                ArgumentMatchers.<Set<String>>any(),
                ArgumentMatchers.<Set<String>>any(),
                ArgumentMatchers.<Set<String>>any(),
                eq("nested_filters"),
                eq("participants_table")
        )).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.addCustomAggregations(
                anyMap(),
                eq("facetAgg"),
                eq("treatment_type"),
                eq("treatment_filters"),
                anyList()
        )).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.send(any(Request.class))).thenReturn(reverseNestedResponse());

        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        List<Map<String, Object>> result = invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1", "participant-2"),
                "charts", List.of(Map.of(
                        "property", "treatment_type",
                        "type", "count"
                ))
        ));

        assertEquals(1, result.size());
        assertEquals("treatment_type", result.get(0).get("property"));
        List<Map<String, Object>> cohorts = mapList(result.get(0), "cohorts");
        assertEquals(1, cohorts.size());
        assertEquals(List.of(
                Map.of("group", "Chemotherapy", "subjects", 2.0),
                Map.of("group", "Other", "subjects", 1.0)
        ), mapList(cohorts.get(0), "participantsByGroup"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(inventoryESService, org.mockito.Mockito.times(2)).buildFacetFilterQuery(
                paramsCaptor.capture(),
                ArgumentMatchers.<Set<String>>any(),
                ArgumentMatchers.<Set<String>>any(),
                ArgumentMatchers.<Set<String>>any(),
                eq("nested_filters"),
                eq("participants_table")
        );
        for (Map<String, Object> queryParams : paramsCaptor.getAllValues()) {
            assertTrue(queryParams.containsKey("id"));
            assertFalse(queryParams.containsKey("pid"));
        }
        verify(inventoryESService, never()).getBucketNames(
                any(), anyMap(), ArgumentMatchers.<Set<String>>any(), any(), any(), any());
    }

    private static JsonObject reverseNestedResponse() {
        return JsonParser.parseString("""
                {
                  "aggregations": {
                    "facetAgg": {
                      "agg_buckets": {
                        "buckets": [
                          {
                            "key": "Other",
                            "doc_count": 7,
                            "top_reverse_nested": {"doc_count": 1}
                          },
                          {
                            "key": "Chemotherapy",
                            "doc_count": 4,
                            "top_reverse_nested": {"doc_count": 2}
                          }
                        ]
                      }
                    }
                  }
                }
                """).getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> getCohortChartPropertyConfiguration(
            PrivateESDataFetcher dataFetcher
    ) throws Exception {
        Field field = PrivateESDataFetcher.class.getDeclaredField("cohortChartProperties");
        field.setAccessible(true);
        return (Map<String, Map<String, String>>) field.get(dataFetcher);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> invokeCohortCharts(
            PrivateESDataFetcher dataFetcher,
            Map<String, Object> params
    ) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod("cohortCharts", Map.class);
        method.setAccessible(true);
        try {
            return (List<Map<String, Object>>) method.invoke(dataFetcher, params);
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
