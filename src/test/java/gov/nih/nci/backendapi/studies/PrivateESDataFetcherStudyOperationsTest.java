package gov.nih.nci.backendapi.studies;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherStudyOperationsTest {

    private static final String STUDIES_ENDPOINT = "/studies_table/_search";
    private static final String PARTICIPANTS_ENDPOINT = "/participants_table/_search";

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
    }

    /**
     * Verifies studyDetails formats profile counts, retrieves exact participant counts, corrects
     * oversized data-category counts, and includes the configured IDC supporting-data record.
     */
    @Test
    void returnsStudyDetailsWithCorrectedCountsAndSupportingData() throws Exception {
        Map<String, Object> study = mutableMap(
                "study_id", "phs002790",
                "study_name", "MCI",
                "diagnosis_anatomic_site", "Brain (5); Kidney");
        stubStudyOverview(study);
        JsonObject diagnosisResponse = facetResponse(Map.of("Neuroblastoma", 8));
        JsonObject dataCategoryResponse = facetResponse(new LinkedHashMap<>(Map.of(
                "Pathology Imaging", 1001,
                "Sequencing", 500,
                "Clinical", 1501,
                "Other", 4)));
        JsonObject correctionResponse = new JsonObject();
        when(inventoryESService.send(any(Request.class)))
                .thenReturn(diagnosisResponse, dataCategoryResponse, correctionResponse);
        stubFacetAggregations(true);
        when(inventoryESService.collectCustomTerms(correctionResponse, "facetAgg"))
                .thenReturn(Map.of("Pathology Imaging", 901, "Clinical", 1401));

        Map<String, Object> result = invoke("studyDetails", Map.of("study_id", "phs002790"));

        assertSame(study, result);
        assertEquals(List.of(
                Map.of("group", "Brain", "subjects", 5),
                Map.of("group", "Kidney", "subjects", 0)), result.get("anatomic_site"));
        assertEquals(List.of(Map.of("group", "Neuroblastoma", "subjects", 8)),
                result.get("diagnoses"));
        assertEquals(Map.of(
                "Pathology Imaging", 901,
                "Clinical", 1401,
                "Sequencing", 500,
                "Other", 4), countsByGroup(mapList(result, "data_categories")));
        List<Map<String, Object>> supportingData = mapList(result, "supporting_data");
        assertEquals(1, supportingData.size());
        assertEquals("IDC", supportingData.get(0).get("data_category"));
        verify(inventoryESService, times(3)).send(any(Request.class));
    }

    /**
     * Verifies studyDetails can return TCIA-only supporting data and skips count correction when
     * every data-category count is at or below its correction threshold.
     */
    @Test
    void returnsTciaSupportingDataWithoutUnneededCountCorrection() throws Exception {
        String studyId = "study-with-tcia";
        Map<String, Object> study = mutableMap(
                "study_id", studyId,
                "diagnosis_anatomic_site", List.of("Bone (2)"));
        setField("DEFAULT_TCIA_DATA", Map.of(studyId, "tcia-data"));
        stubStudyOverview(study);
        when(inventoryESService.send(any(Request.class))).thenReturn(
                facetResponse(Map.of("Osteosarcoma", 2)),
                facetResponse(Map.of(
                        "Pathology Imaging", 1000,
                        "Sequencing", 500,
                        "Clinical", 1500)));
        stubFacetAggregations(false);

        Map<String, Object> result = invoke("studyDetails", Map.of("study_id", studyId));

        assertEquals(List.of(Map.of(
                "data_category", "TCIA", "data_object", "tcia-data")),
                result.get("supporting_data"));
        verify(inventoryESService, times(2)).send(any(Request.class));
    }

    /** Verifies studyDetails returns an empty supporting-data list for an unconfigured study. */
    @Test
    void returnsEmptySupportingDataForUnconfiguredStudy() throws Exception {
        String studyId = "study-without-supporting-data";
        Map<String, Object> study = mutableMap(
                "study_id", studyId,
                "diagnosis_anatomic_site", null);
        stubStudyOverview(study);
        when(inventoryESService.send(any(Request.class))).thenReturn(
                facetResponse(Map.of()),
                facetResponse(Map.of()));
        stubFacetAggregations(false);

        Map<String, Object> result = invoke("studyDetails", Map.of("study_id", studyId));

        assertEquals(List.of(), result.get("supporting_data"));
        assertEquals(List.of(), result.get("anatomic_site"));
    }

    /** Verifies studiesListing forwards sorting and pagination to the studies index. */
    @Test
    void returnsPaginatedStudiesListing() throws Exception {
        Map<String, Object> params = pagingParams("study_name", "desc", 20, 10);
        Map<String, Object> query = new HashMap<>();
        List<Map<String, Object>> expected = List.of(
                Map.of("study_id", "STUDY-2", "study_name", "Second"),
                Map.of("study_id", "STUDY-1", "study_name", "First"));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), anySet(), eq(Set.of()),
                eq("nested_filters"), eq("studies_table"))).thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class), eq(20), eq(10)))
                .thenReturn(expected);

        List<Map<String, Object>> result = invoke("studiesListing", params);

        assertSame(expected, result);
        assertEquals(Map.of("study_name", "desc"), query.get("sort"));
        assertEquals(Map.of("exclude", Set.of("files")), query.get("_source"));
        assertCollectedFields(STUDIES_ENDPOINT, Set.of(
                "id", "study_id", "study_name", "num_of_participants", "num_of_samples",
                "num_of_diagnoses", "sex_at_birth", "num_of_files", "num_of_study_files",
                "num_of_participant_files", "num_of_sample_files", "num_of_publications"));
    }

    /** Verifies the study-profile parser handles null, blank, unsupported, and mixed values. */
    @Test
    void parsesEverySupportedStudyProfileCountShape() throws Exception {
        assertEquals(List.of(), invoke("parseStudyProfileCounts", (Object) null));
        assertEquals(List.of(), invoke("parseStudyProfileCounts", "   "));
        assertEquals(List.of(), invoke("parseStudyProfileCounts", 42));
        assertEquals(List.of(
                Map.of("group", "Brain", "subjects", 12),
                Map.of("group", "Unknown", "subjects", 0)),
                invoke("parseStudyProfileCounts", new ArrayList<>(List.of(
                        "Brain (12)", "", "Unknown"))));
        List<Object> valuesWithNull = new ArrayList<>();
        valuesWithNull.add(null);
        valuesWithNull.add("Bone (3)");
        assertEquals(List.of(Map.of("group", "Bone", "subjects", 3)),
                invoke("parseStudyProfileCounts", valuesWithNull));
    }

    private void stubStudyOverview(Map<String, Object> study) throws Exception {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), eq(Set.of()),
                eq("nested_filters"), any(String.class)))
                .thenAnswer(invocation -> new HashMap<>());
        when(inventoryESService.collectPage(
                ArgumentMatchers.argThat(request -> STUDIES_ENDPOINT.equals(request.getEndpoint())),
                anyMap(), any(String[][].class), eq(1), eq(0)))
                .thenReturn(List.of(study));
    }

    private void stubFacetAggregations(boolean includeCorrectionAggregation) {
        when(inventoryESService.addCustomAggregations(
                anyMap(), eq("facetAgg"), any(String.class),
                eq("sample_diagnosis_genetic_analysis_file_filters"), anyList()))
                .thenAnswer(invocation -> new HashMap<>(invocation.getArgument(0)));
        if (includeCorrectionAggregation) {
            when(inventoryESService.addCustomAggregations(
                    anyMap(), eq("facetAgg"), eq("data_category"),
                    eq("sample_diagnosis_genetic_analysis_file_filters")))
                    .thenAnswer(invocation -> new HashMap<>(invocation.getArgument(0)));
        }
    }

    private void assertCollectedFields(String endpoint, Set<String> expectedFields)
            throws Exception {
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService).collectPage(
                requestCaptor.capture(), anyMap(), propertiesCaptor.capture(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
        assertEquals(endpoint, requestCaptor.getValue().getEndpoint());
        Set<String> actualFields = java.util.Arrays.stream(propertiesCaptor.getValue())
                .map(property -> property[0])
                .collect(Collectors.toSet());
        assertEquals(expectedFields, actualFields);
    }

    private static JsonObject facetResponse(Map<String, Integer> counts) {
        String buckets = counts.entrySet().stream()
                .map(entry -> "{\"key\":\"" + entry.getKey()
                        + "\",\"top_reverse_nested\":{\"doc_count\":"
                        + entry.getValue() + "}}")
                .collect(Collectors.joining(","));
        return JsonParser.parseString(
                "{\"aggregations\":{\"facetAgg\":{\"agg_buckets\":{\"buckets\":["
                        + buckets + "]}}}}")
                .getAsJsonObject();
    }

    private static Map<String, Object> pagingParams(
            String orderBy, String direction, int first, int offset) {
        return mutableMap(
                "order_by", orderBy,
                "sort_direction", direction,
                "first", first,
                "offset", offset);
    }

    private static Map<String, Integer> countsByGroup(List<Map<String, Object>> counts) {
        return counts.stream().collect(Collectors.toMap(
                count -> (String) count.get("group"),
                count -> (Integer) count.get("subjects")));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Object argument) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(
                methodName, argument instanceof Map ? Map.class : Object.class);
        method.setAccessible(true);
        try {
            return (T) method.invoke(dataFetcher, argument);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = PrivateESDataFetcher.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dataFetcher, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            map.put((String) entries[index], entries[index + 1]);
        }
        return map;
    }
}
