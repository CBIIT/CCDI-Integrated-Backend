package gov.nih.nci.backendapi.participantsearch;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.opensearch.client.Request;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrivateESDataFetcherFacetCountsTest {

    private static final String PARTICIPANTS_ENDPOINT = "/participants_table/_search";

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;
    private Cache<String, Object> cache;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
        cache = Caffeine.newBuilder().build();
        setPrivateField("caffeineCache", cache);
        setPrivateField("facetFilters", Map.of());
    }

    /**
     * Verifies that participant search returns every summary count, forwards filter and pagination
     * arguments to the query builder, stores the result, and serves the next request from cache.
     */
    @Test
    void returnsSummaryCountsAndCachesTheResult() throws Exception {
        stubSummaryCountQueries();
        Map<String, Object> params = mutableMap(
                "race", List.of("Asian"),
                "first", 25,
                "offset", 50
        );

        Map<String, Object> firstResult = invokeSearchParticipants(params);

        assertEquals(2, firstResult.get("numberOfStudies"));
        assertEquals(11, firstResult.get("numberOfParticipants"));
        assertEquals(12, firstResult.get("numberOfDiagnosis"));
        assertEquals(13, firstResult.get("numberOfGeneticAnalyses"));
        assertEquals(14, firstResult.get("numberOfTreatments"));
        assertEquals(15, firstResult.get("numberOfTreatmentResponses"));
        assertEquals(16, firstResult.get("numberOfSurvivals"));
        assertEquals(17, firstResult.get("numberOfSamples"));
        assertEquals(18, firstResult.get("numberOfFiles"));
        assertEquals(19, firstResult.get("participantsFileCount"));
        verify(inventoryESService, times(9)).buildFacetFilterQuery(
                eq(params), anySet(), eq(Set.of()), eq(Set.of()), eq("nested_filters"), anyString());

        clearInvocations(inventoryESService);
        Map<String, Object> cachedResult = invokeSearchParticipants(params);

        assertSame(firstResult, cachedResult);
        verifyNoInteractions(inventoryESService);
    }

    /** Verifies that each accepted empty import-data shape still permits a cached response. */
    @Test
    void usesCacheWhenImportDataIsAbsentEmptyOrBlank() throws Exception {
        for (Map<String, Object> params : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("import_data", List.of()),
                Map.<String, Object>of("import_data", List.of("")))) {
            Map<String, Object> expected = Map.of("cached", true);
            String key = invokePrivate("generateCacheKey", new Class<?>[]{Map.class}, params);
            cache.put(key, expected);

            assertSame(expected, invokeSearchParticipants(params));
        }

        verifyNoInteractions(inventoryESService);
    }

    /** Verifies that imported participant IDs bypass both cache reads and cache writes. */
    @Test
    void bypassesCacheWhenImportDataIsProvided() throws Exception {
        stubSummaryCountQueries();
        Map<String, Object> params = Map.of("import_data", List.of("PARTICIPANT-1"));
        cache.put("all", Map.of("cached", true));

        Map<String, Object> result = invokeSearchParticipants(params);

        assertEquals(11, result.get("numberOfParticipants"));
        assertEquals(1, cache.estimatedSize());
        verify(inventoryESService, times(9)).send(any(Request.class));
    }

    /** Verifies that failures raised by a parallel facet job become the API's checked I/O error. */
    @Test
    void reportsParallelFacetFailuresAsIoErrors() throws Exception {
        stubSummaryCountQueries();
        setPrivateField("facetFilters", Map.of(
                "participants_table",
                List.of(facet("race", null, "race_filter_count", null))
        ));
        when(inventoryESService.addAggregations(anyMap(), any(String[].class), any(), anyList()))
                .thenThrow(new IllegalStateException("aggregation failed"));

        IOException exception = assertThrows(IOException.class,
                () -> invokeSearchParticipants(Map.of()));

        assertEquals("Failed while computing facet counts in parallel", exception.getMessage());
        assertTrue(exception.getCause().getCause().getMessage().contains("aggregation failed"));
        assertEquals(0, cache.estimatedSize());
    }

    /** Verifies that successful parallel facet jobs are merged into the participant-search result. */
    @Test
    void mergesParallelFacetCountsIntoTheSearchResult() throws Exception {
        stubSummaryCountQueries();
        setPrivateField("facetFilters", Map.of(
                "participants_table",
                List.of(facet("race", null, "race_filter_count", "race_widget_count"))
        ));
        when(inventoryESService.addAggregations(anyMap(), any(String[].class), any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.collectTermAggs(any(JsonObject.class), any(String[].class)))
                .thenReturn(Map.of("race", termBuckets("Asian", 6)));

        Map<String, Object> result = invokeSearchParticipants(
                Map.of("race", List.of("Asian"), "first", 20, "offset", 20));

        assertEquals(termCounts("Asian", 6), result.get("race_filter_count"));
        assertEquals(termCounts("Asian", 6), result.get("race_widget_count"));
    }

    /**
     * Verifies plain term facets, including filter counts and the selected-value widget recount.
     */
    @Test
    void computesTermFilterAndSelectedWidgetCounts() throws Exception {
        stubTermAggregations(termBuckets("Asian", 7, "White", 4));
        Map<String, Object> filter = facet("race", null, "race_filter_count", "race_widget_count");
        filter.put("_index", "participants_table");
        filter.put("_endpoint", PARTICIPANTS_ENDPOINT);

        Map<String, Object> result = invokeComputeFacetCounts(
                filter, Map.of("race", List.of("Asian")));

        assertEquals(termCounts("Asian", 7, "White", 4), result.get("race_filter_count"));
        assertEquals(termCounts("Asian", 7, "White", 4), result.get("race_widget_count"));
        verify(inventoryESService, times(2)).addAggregations(
                anyMap(), any(String[].class), eq(null), anyList());
    }

    /**
     * Verifies that an unselected or null-valued term widget reuses its filter counts and that a
     * facet without a widget returns only the filter-count field.
     */
    @Test
    void reusesTermCountsWhenTheWidgetHasNoSelectedValues() throws Exception {
        stubTermAggregations(termBuckets("Asian", 3));
        Map<String, Object> widgetFacet = facet(
                "race", null, "race_filter_count", "race_widget_count");
        widgetFacet.put("_index", "participants_table");
        widgetFacet.put("_endpoint", PARTICIPANTS_ENDPOINT);

        Map<String, Object> absent = invokeComputeFacetCounts(widgetFacet, Map.of());
        Map<String, Object> nullValue = invokeComputeFacetCounts(
                widgetFacet, mutableMap("race", null));
        Map<String, Object> empty = invokeComputeFacetCounts(
                widgetFacet, Map.of("race", List.of()));

        Map<String, Object> noWidgetFacet = new HashMap<>(widgetFacet);
        noWidgetFacet.put("widget_count_name", null);
        Map<String, Object> noWidget = invokeComputeFacetCounts(noWidgetFacet, Map.of());

        assertSame(absent.get("race_filter_count"), absent.get("race_widget_count"));
        assertSame(nullValue.get("race_filter_count"), nullValue.get("race_widget_count"));
        assertSame(empty.get("race_filter_count"), empty.get("race_widget_count"));
        assertEquals(Set.of("race_filter_count"), noWidget.keySet());
        verify(inventoryESService, times(4)).addAggregations(
                anyMap(), any(String[].class), eq(null), anyList());
    }

    /**
     * Verifies exact participant term counts use nested/reverse-nested aggregation results rather
     * than approximate cardinality values.
     */
    @Test
    void computesExactParticipantCountsForNestedTermFacets() throws Exception {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), eq("nested_filters"), eq("participants_table")))
                .thenReturn(new HashMap<>());
        when(inventoryESService.addCustomAggregations(
                anyMap(), eq("facetAgg"), eq("diagnosis"),
                eq("sample_diagnosis_genetic_analysis_file_filters"), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.send(any(Request.class))).thenReturn(customTermResponse());
        Map<String, Object> filter = facet(
                "diagnosis", "pid", "diagnosis_filter_count", "diagnosis_widget_count");
        filter.put("_index", "diagnoses_table");
        filter.put("_endpoint", "/diagnoses_table/_search");

        Map<String, Object> result = invokeComputeFacetCounts(
                filter, Map.of("diagnosis", List.of("Neuroblastoma")));

        assertEquals(termCounts("Neuroblastoma", 5), result.get("diagnosis_filter_count"));
        assertEquals(termCounts("Neuroblastoma", 5), result.get("diagnosis_widget_count"));
        verify(inventoryESService, times(2)).send(
                org.mockito.ArgumentMatchers.argThat(
                        request -> PARTICIPANTS_ENDPOINT.equals(request.getEndpoint())));
    }

    /**
     * Verifies genetic facet labels come from the genetic-analysis index while their exact counts
     * come from the participant index, excluding serialized array labels and zero-count genes.
     */
    @Test
    void computesExactGeneticFacetParticipantCounts() throws Exception {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), eq("nested_filters"), anyString()))
                .thenReturn(new HashMap<>());
        when(inventoryESService.addCustomAggregations(
                anyMap(), eq("facetAgg"), eq("gene_symbol"), anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            return switch (request.getEndpoint()) {
                case "/genetic_analyses_table/_search" -> JsonParser.parseString("""
                        {"aggregations":{"facetAgg":{"buckets":[
                          {"key":"ALK"}, {"key":"MYCN"}, {"key":"TP53"},
                          {"key":"[ALK, MYCN]"}, {"key":""}, {"key":null}
                        ]}}}
                        """).getAsJsonObject();
                case PARTICIPANTS_ENDPOINT -> JsonParser.parseString("""
                        {"aggregations":{"facetAgg":{"agg_buckets":{"buckets":[
                          {"key":"ALK","top_reverse_nested":{"doc_count":4}},
                          {"key":"MYCN","doc_count":2},
                          {"key":"[ALK, MYCN]","doc_count":8}
                        ]}}}}
                        """).getAsJsonObject();
                default -> throw new AssertionError(
                        "Unexpected OpenSearch endpoint: " + request.getEndpoint());
            };
        });
        Map<String, Object> filter = facet(
                "gene_symbol", "pid", "gene_filter_count", null);
        filter.put("_index", "genetic_analyses_table");
        filter.put("_endpoint", "/genetic_analyses_table/_search");

        Map<String, Object> result = invokeComputeFacetCounts(
                filter, Map.of("id", List.of("PARTICIPANT-1")));

        assertEquals(List.of(
                Map.of("group", "ALK", "subjects", 4),
                Map.of("group", "MYCN", "subjects", 2)),
                result.get("gene_filter_count"));
    }

    /**
     * Verifies range filter statistics and exact participant range buckets, including an explicit
     * cardinality index override for the widget query.
     */
    @Test
    void computesRangeFilterAndExactParticipantWidgetCounts() throws Exception {
        stubRangeStatistics(2, 10, 50);
        when(inventoryESService.addCustomRangeAggregations(
                anyMap(), eq("facetAgg"), eq("age_at_diagnosis"),
                eq("sample_diagnosis_genetic_analysis_file_filters")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            return switch (request.getEndpoint()) {
                case "/diagnoses_table/_search" -> new JsonObject();
                case PARTICIPANTS_ENDPOINT -> customRangeResponse();
                default -> throw new AssertionError(
                        "Unexpected OpenSearch endpoint: " + request.getEndpoint());
            };
        });
        Map<String, Object> filter = facet(
                "age_at_diagnosis", "pid", "age_filter_count", "age_widget_count");
        filter.put("cardinality_index_name", "participants_table");
        filter.put("_index", "diagnoses_table");
        filter.put("_endpoint", "/diagnoses_table/_search");

        Map<String, Object> result = invokeComputeFacetCounts(filter, Map.of());

        assertEquals(Map.of("lowerBound", 10, "subjects", 2, "upperBound", 50),
                result.get("age_filter_count"));
        assertEquals(List.of(
                Map.of("group", "0-19", "subjects", 4),
                Map.of("group", "20-39", "subjects", 3),
                Map.of("group", "40+", "subjects", 0)),
                result.get("age_widget_count"));
    }

    /** Verifies non-cardinality range widgets use range-count aggregations on their own index. */
    @Test
    void computesRangeWidgetCountsWithoutCardinality() throws Exception {
        stubRangeStatistics(0, 0, 0);
        when(inventoryESService.addRangeCountAggregations(
                anyMap(), eq("age_at_treatment_start"), eq(null)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.collectRangCountAggs(
                any(JsonObject.class), eq("age_at_treatment_start")))
                .thenReturn(Map.of("age_at_treatment_start", termBuckets("0-9", 6)));
        when(inventoryESService.send(any(Request.class))).thenReturn(new JsonObject());
        Map<String, Object> filter = facet(
                "age_at_treatment_start", null, "age_filter_count", "age_widget_count");
        filter.put("_index", "treatments_table");
        filter.put("_endpoint", "/treatments_table/_search");

        Map<String, Object> result = invokeComputeFacetCounts(filter, Map.of());

        assertEquals(Map.of("lowerBound", 0, "subjects", 0, "upperBound", 0),
                result.get("age_filter_count"));
        assertEquals(termCounts("0-9", 6), result.get("age_widget_count"));
    }

    /** Verifies a misconfigured range facet fails clearly instead of sending to a null endpoint. */
    @Test
    void rejectsRangeWidgetsWithoutAnEndpointMapping() throws Exception {
        stubRangeStatistics(1, 1, 2);
        when(inventoryESService.send(any(Request.class))).thenReturn(new JsonObject());
        Map<String, Object> filter = facet(
                "age_at_diagnosis", "pid", "age_filter_count", "age_widget_count");
        filter.put("_index", "unknown_index");
        filter.put("_endpoint", "/unknown/_search");

        IOException exception = assertThrows(IOException.class,
                () -> invokeComputeFacetCounts(filter, Map.of()));

        assertEquals("No OpenSearch endpoint mapping found for index: unknown_index",
                exception.getMessage());
        verify(inventoryESService, never()).addCustomRangeAggregations(
                anyMap(), anyString(), anyString(), anyString());
    }

    /** Verifies every participant nested facet family maps to the correct OpenSearch path. */
    @Test
    void mapsFacetIndexesToParticipantNestedPaths() throws Exception {
        assertEquals("survival_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class}, "survivals_table"));
        assertEquals("treatment_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class}, "treatments_table"));
        assertEquals("treatment_response_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class},
                "treatment_responses_table"));
        assertEquals("sample_diagnosis_genetic_analysis_file_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class}, "samples_table"));
        assertEquals("sample_diagnosis_genetic_analysis_file_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class}, "diagnoses_table"));
        assertEquals("sample_diagnosis_genetic_analysis_file_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class},
                "genetic_analyses_table"));
        assertEquals("sample_diagnosis_genetic_analysis_file_filters", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class}, "files_table"));
        assertEquals("", invokePrivate(
                "getParticipantsFacetNestedPath", new Class<?>[]{String.class}, "participants_table"));
    }

    /** Verifies cache keys encode filters and ranges and reject an entirely open range. */
    @Test
    void generatesCacheKeysForFacetFilterValuesAndRanges() throws Exception {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("age_at_diagnosis", List.of(5, 10));
        params.put("race", java.util.Arrays.asList("Asian", null));
        params.put("search", "tumor");
        params.put("first", 25);
        params.put("include", true);

        String key = invokePrivate("generateCacheKey", new Class<?>[]{Map.class}, params);

        assertTrue(key.contains("age_at_diagnosis[5,10]"));
        assertTrue(key.contains("race[Asian, null]"));
        assertTrue(key.contains("search[tumor]"));
        assertTrue(key.contains("first[25]"));
        assertTrue(key.contains("include[true]"));
        String firstRangeKey = invokePrivate(
                "generateCacheKey",
                new Class<?>[]{Map.class},
                Map.of("age_at_diagnosis", List.of(1, 234)));
        String secondRangeKey = invokePrivate(
                "generateCacheKey",
                new Class<?>[]{Map.class},
                Map.of("age_at_diagnosis", List.of(12, 34)));
        assertNotEquals(firstRangeKey, secondRangeKey);
        IOException exception = assertThrows(IOException.class, () -> invokePrivate(
                "generateCacheKey",
                new Class<?>[]{Map.class},
                Map.of("age_at_diagnosis", java.util.Arrays.asList(null, null))));
        assertEquals("Lower bound and Upper bound can't be both null!", exception.getMessage());
    }

    /**
     * Verifies term aggregation responses support exact scripted counts, legacy cardinality counts,
     * legacy bucket counts, document-count fallbacks, and invalid bucket labels.
     */
    @Test
    void interpretsEverySupportedTermAggregationCountShape() throws Exception {
        JsonArray buckets = JsonParser.parseString("""
                [
                  {"key":null,"doc_count":9},
                  {"key":"","doc_count":9},
                  {"key":"scripted","exact_count":{"value":5},"doc_count":9},
                  {"key":"legacyBuckets","exact_count":{"buckets":[{},{}]},"doc_count":9},
                  {"key":"exactFallback","exact_count":{},"doc_count":3},
                  {"key":"legacyCardinality","cardinality_count":{"value":4},"doc_count":9},
                  {"key":"legacyFallback","cardinality_count":{"value":0},"doc_count":6},
                  {"key":"zero","cardinality_count":{"value":0},"doc_count":0}
                ]
                """).getAsJsonArray();

        List<Map<String, Object>> counts = invokePrivate(
                "getGroupCountHelper",
                new Class<?>[]{JsonArray.class, String.class},
                buckets,
                "pid");

        assertEquals(List.of(
                Map.of("group", "scripted", "subjects", 5),
                Map.of("group", "legacyBuckets", "subjects", 2),
                Map.of("group", "exactFallback", "subjects", 3),
                Map.of("group", "legacyCardinality", "subjects", 4),
                Map.of("group", "legacyFallback", "subjects", 6),
                Map.of("group", "zero", "subjects", 0)), counts);
    }

    private void stubSummaryCountQueries() throws IOException {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), eq("nested_filters"), anyString()))
                .thenReturn(new HashMap<>());
        when(inventoryESService.addNodeCountAggregations(anyMap(), eq("study_id")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        JsonArray studies = new JsonArray();
        studies.add(new JsonObject());
        studies.add(new JsonObject());
        when(inventoryESService.collectNodeCountAggs(any(JsonObject.class), eq("study_id")))
                .thenReturn(Map.of("study_id", studies));
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            return switch (endpoint) {
                case "/diagnoses_table/_count" -> countResponse(12);
                case "/genetic_analyses_table/_count" -> countResponse(13);
                case "/treatments_table/_count" -> countResponse(14);
                case "/treatment_responses_table/_count" -> countResponse(15);
                case "/survivals_table/_count" -> countResponse(16);
                case "/samples_table/_count" -> countResponse(17);
                case "/files_table/_count" -> countResponse(18);
                default -> participantSummaryResponse(11, 19);
            };
        });
    }

    private void stubTermAggregations(JsonArray buckets) throws IOException {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), eq("nested_filters"), anyString()))
                .thenReturn(new HashMap<>());
        when(inventoryESService.addAggregations(anyMap(), any(String[].class), any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryESService.collectTermAggs(any(JsonObject.class), any(String[].class)))
                .thenReturn(Map.of("race", buckets));
        when(inventoryESService.send(any(Request.class))).thenReturn(new JsonObject());
    }

    private void stubRangeStatistics(int count, int min, int max) throws IOException {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), eq("nested_filters"), anyString()))
                .thenReturn(new HashMap<>());
        when(inventoryESService.addRangeAggregations(anyMap(), anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        JsonObject stats = new JsonObject();
        stats.addProperty("count", count);
        stats.addProperty("min", min);
        stats.addProperty("max", max);
        when(inventoryESService.collectRangAggs(any(JsonObject.class), anyString()))
                .thenAnswer(invocation -> Map.of(invocation.getArgument(1), stats));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeSearchParticipants(Map<String, Object> params) throws Exception {
        return invokePrivate("searchParticipants", new Class<?>[]{Map.class}, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeComputeFacetCounts(
            Map<String, Object> filter, Map<String, Object> params) throws Exception {
        return invokePrivate(
                "computeFacetCounts", new Class<?>[]{Map.class, Map.class}, filter, params);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivate(String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return (T) method.invoke(dataFetcher, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void setPrivateField(String name, Object value) throws Exception {
        Field field = PrivateESDataFetcher.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dataFetcher, value);
    }

    private static Map<String, Object> facet(
            String field,
            String cardinality,
            String filterCountName,
            String widgetCountName
    ) {
        return mutableMap(
                "agg_name", field,
                "cardinality_agg_name", cardinality,
                "filter_count_name", filterCountName,
                "widget_count_name", widgetCountName
        );
    }

    private static JsonObject countResponse(int count) {
        JsonObject response = new JsonObject();
        response.addProperty("count", count);
        return response;
    }

    private static JsonObject participantSummaryResponse(int participants, int files) {
        return JsonParser.parseString("""
                {
                  "hits": {"total": {"value": %d}},
                  "aggregations": {"file_count": {"value": %d}}
                }
                """.formatted(participants, files)).getAsJsonObject();
    }

    private static JsonObject customTermResponse() {
        return JsonParser.parseString("""
                {
                  "aggregations": {
                    "facetAgg": {
                      "agg_buckets": {
                        "buckets": [
                          {"key": "Neuroblastoma", "doc_count": 9,
                           "top_reverse_nested": {"doc_count": 5}},
                          {"key": "", "doc_count": 2},
                          {"key": null, "doc_count": 1}
                        ]
                      }
                    }
                  }
                }
                """).getAsJsonObject();
    }

    private static JsonObject customRangeResponse() {
        return JsonParser.parseString("""
                {
                  "aggregations": {
                    "facetAgg": {
                      "agg_buckets": {
                        "buckets": [
                          {"key": "0-19", "doc_count": 8,
                           "top_reverse_nested": {"doc_count": 4}},
                          {"key_as_string": "20-39", "doc_count": 3},
                          {"key": "40+"},
                          {"doc_count": 1}
                        ]
                      }
                    }
                  }
                }
                """).getAsJsonObject();
    }

    private static JsonArray termBuckets(Object... values) {
        JsonArray buckets = new JsonArray();
        for (int i = 0; i < values.length; i += 2) {
            JsonObject bucket = new JsonObject();
            bucket.addProperty("key", (String) values[i]);
            bucket.addProperty("doc_count", (Integer) values[i + 1]);
            buckets.add(bucket);
        }
        return buckets;
    }

    private static List<Map<String, Object>> termCounts(Object... values) {
        java.util.ArrayList<Map<String, Object>> counts = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            counts.add(Map.of("group", values[i], "subjects", values[i + 1]));
        }
        return counts;
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
