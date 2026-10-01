package gov.nih.nci.backendapi.cohortanalyzer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** Verifies that the supported chart properties and their source indices load from YAML. */
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

    /** Verifies that an unknown chart property is skipped without contacting OpenSearch. */
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

    /**
     * Verifies that missing or empty chart/cohort inputs return no charts without contacting
     * OpenSearch.
     */
    @Test
    void returnsNoChartsWhenRequiredInputsAreMissingOrEmpty() throws Exception {
        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);

        assertTrue(invokeCohortCharts(dataFetcher, null).isEmpty());
        assertTrue(invokeCohortCharts(dataFetcher, Map.of()).isEmpty());
        assertTrue(invokeCohortCharts(dataFetcher, Map.of("charts", List.of())).isEmpty());
        assertTrue(invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of(),
                "charts", List.of(Map.of("property", "race", "type", "count"))
        )).isEmpty());
        assertTrue(invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1"),
                "charts", List.of()
        )).isEmpty());
        assertTrue(invokeCohortCharts(dataFetcher, Map.of(
                "c1", "participant-1",
                "charts", List.of(Map.of("property", "race", "type", "count"))
        )).isEmpty());
        assertTrue(invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1"),
                "charts", "not-a-chart-list"
        )).isEmpty());

        verifyNoInteractions(inventoryESService);
    }

    /** Verifies that properties explicitly disabled in configuration are not queried. */
    @Test
    void unavailablePropertyIsSkippedWithoutQueryingOpenSearch() throws Exception {
        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        getCohortChartPropertyConfiguration(dataFetcher).put("disabled_property", Map.of(
                "available", "false",
                "index", "participants_table",
                "endpoint", "/participants_table/_search",
                "cardinalityAggName", ""
        ));

        List<Map<String, Object>> result = invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1"),
                "charts", List.of(Map.of("property", "disabled_property", "type", "count"))
        ));

        assertTrue(result.isEmpty());
        verifyNoInteractions(inventoryESService);
    }

    /**
     * Verifies that participant-root charts use participant IDs, calculate percentages from the
     * participant index, and combine buckets beyond the five-item display limit.
     */
    @Test
    void participantRootChartsReturnPercentagesAndOtherBucket() throws Exception {
        List<String> bucketNames = List.of("A", "B", "C", "D", "E", "F");
        when(inventoryESService.getBucketNames(
                eq("race"), anyMap(), ArgumentMatchers.<Set<String>>any(),
                eq(null), eq("participants_table"), eq("/participants_table/_search")))
                .thenReturn(bucketNames);
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), ArgumentMatchers.<Set<String>>any(),
                ArgumentMatchers.<Set<String>>any(), ArgumentMatchers.<Set<String>>any(),
                any(), eq("participants_table")))
                .thenAnswer(invocation -> new HashMap<String, Object>());
        when(inventoryESService.getCount(anyMap(), eq("participants_table"))).thenReturn(4);
        when(inventoryESService.addAggregations(
                anyMap(), any(String[].class), eq(null), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.send(any(Request.class))).thenReturn(new JsonObject());
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("A", 2);
        counts.put("B", 1);
        counts.put("C", 1);
        counts.put("D", 0);
        counts.put("E", 0);
        counts.put("F", 1);
        when(inventoryESService.collectTermAggs(any(JsonObject.class), any(String[].class)))
                .thenReturn(Map.of("race", termBuckets(counts)));

        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        List<Map<String, Object>> result = invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1", "participant-2"),
                "charts", List.of(Map.of("property", "race", "type", "percentage"))
        ));

        List<Map<String, Object>> percentages = mapList(
                mapList(result.get(0), "cohorts").get(0), "participantsByGroup");
        assertEquals(List.of(
                Map.of("group", "A", "subjects", 50.0),
                Map.of("group", "B", "subjects", 25.0),
                Map.of("group", "C", "subjects", 25.0),
                Map.of("group", "D", "subjects", 0.0),
                Map.of("group", "E", "subjects", 0.0),
                Map.of("group", "F", "subjects", 25.0),
                Map.of("group", "OtherFew", "subjects", 25.0)
        ), percentages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(inventoryESService).getBucketNames(
                eq("race"), paramsCaptor.capture(), ArgumentMatchers.<Set<String>>any(),
                eq(null), eq("participants_table"), eq("/participants_table/_search"));
        assertEquals(List.of("participant-1", "participant-2"),
                paramsCaptor.getValue().get("id"));
    }

    /**
     * Verifies both chart display limits, the zero-denominator percentage fallback, and the
     * behavior for a chart type that does not request count or percentage output.
     */
    @Test
    void largeChartsReturnBothOtherBucketsAndSafeZeroPercentages() throws Exception {
        List<String> bucketNames = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(index -> "group-" + index)
                .toList();
        when(inventoryESService.getBucketNames(
                eq("race"), anyMap(), ArgumentMatchers.<Set<String>>any(),
                eq(null), eq("participants_table"), eq("/participants_table/_search")))
                .thenReturn(bucketNames);
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), ArgumentMatchers.<Set<String>>any(),
                ArgumentMatchers.<Set<String>>any(), ArgumentMatchers.<Set<String>>any(),
                any(), eq("participants_table")))
                .thenAnswer(invocation -> new HashMap<String, Object>());
        when(inventoryESService.getCount(anyMap(), eq("participants_table"))).thenReturn(0);
        when(inventoryESService.addAggregations(
                anyMap(), any(String[].class), eq(null), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.send(any(Request.class))).thenReturn(new JsonObject());
        Map<String, Integer> counts = new LinkedHashMap<>();
        bucketNames.forEach(bucket -> counts.put(bucket, 1));
        when(inventoryESService.collectTermAggs(any(JsonObject.class), any(String[].class)))
                .thenReturn(Map.of("race", termBuckets(counts)));

        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        List<Map<String, Object>> result = invokeCohortCharts(dataFetcher, Map.of(
                "c1", List.of("participant-1"),
                "charts", List.of(
                        Map.of("property", "race", "type", "percentage"),
                        Map.of("property", "race", "type", "unsupported")
                )
        ));

        List<Map<String, Object>> percentages = mapList(
                mapList(result.get(0), "cohorts").get(0), "participantsByGroup");
        assertEquals(22, percentages.size());
        assertTrue(percentages.stream()
                .anyMatch(item -> "OtherFew".equals(item.get("group"))
                        && Double.valueOf(0.0).equals(item.get("subjects"))));
        assertTrue(percentages.stream()
                .anyMatch(item -> "OtherMany".equals(item.get("group"))
                        && Double.valueOf(0.0).equals(item.get("subjects"))));
        assertFalse(mapList(result.get(1), "cohorts").get(0)
                .containsKey("participantsByGroup"));
    }

    /**
     * Verifies that participant-linked charts use participant IDs and reverse-nested participant
     * counts rather than child-document counts.
     */
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
                Map.of("group", "Surgery", "subjects", 2.0),
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
                          },
                          {
                            "key": "Surgery",
                            "doc_count": 5,
                            "top_reverse_nested": {"doc_count": 2}
                          }
                        ]
                      }
                    }
                  }
                }
                """).getAsJsonObject();
    }

    private static JsonArray termBuckets(Map<String, Integer> counts) {
        JsonArray buckets = new JsonArray();
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            JsonObject bucket = new JsonObject();
            bucket.addProperty("key", count.getKey());
            bucket.addProperty("doc_count", count.getValue());
            buckets.add(bucket);
        }
        return buckets;
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
