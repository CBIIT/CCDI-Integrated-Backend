package gov.nih.nci.backendapi.overviewtables;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherOverviewTablesTest {

    private static final Set<String> PAGING_ARGUMENTS =
            Set.of("first", "offset", "order_by", "sort_direction");

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
    }

    /** Verifies participant overview projection, participant endpoint, sorting, and nested exclusions. */
    @Test
    void returnsParticipantOverview() throws Exception {
        verifyOverview(
                "participantOverview",
                "/participants_table/_search",
                "participants_table",
                "race",
                "race_str",
                Map.of("exclude", Set.of(
                        "sample_diagnosis_genetic_analysis_file_filters",
                        "survival_filters",
                        "treatment_filters",
                        "treatment_response_filters")),
                List.of(List.of("participant_id", "participant_id"),
                        List.of("synonym_id", "alternate_participant_id")),
                true);
    }

    /** Verifies diagnosis overview uses the diagnosis index and its public-to-index field mappings. */
    @Test
    void returnsDiagnosisOverview() throws Exception {
        verifyOverview(
                "diagnosisOverview",
                "/diagnoses_table/_search",
                "diagnoses_table",
                "anatomic_site",
                "diagnosis_anatomic_site_str",
                Map.of("exclude", Set.of(
                        "sample_genetic_analysis_file_filters",
                        "survival_filters",
                        "treatment_filters",
                        "treatment_response_filters")),
                List.of(List.of("diagnosis_id", "diagnosis_id"),
                        List.of("anatomic_site", "diagnosis_anatomic_site")),
                false);
    }

    /** Verifies genetic-analysis overview uses the correct endpoint, projection, and sort field. */
    @Test
    void returnsGeneticAnalysisOverview() throws Exception {
        verifyOverview(
                "geneticAnalysisOverview",
                "/genetic_analyses_table/_search",
                "genetic_analyses_table",
                "gene_symbol",
                "gene_symbol",
                Map.of("exclude", Set.of(
                        "sample_diagnosis_file_filters",
                        "survival_filters",
                        "treatment_filters",
                        "treatment_response_filters")),
                List.of(List.of("genetic_analysis_id", "genetic_analysis_id"),
                        List.of("hgvs_protein", "hgvs_protein")),
                false);
    }

    /** Verifies treatment overview uses the treatment index and omits unrelated nested records. */
    @Test
    void returnsTreatmentOverview() throws Exception {
        verifyOverview(
                "treatmentOverview",
                "/treatments_table/_search",
                "treatments_table",
                "treatment_agent",
                "treatment_agent",
                Map.of("exclude", Set.of(
                        "sample_diagnosis_genetic_analysis_file_filters",
                        "survival_filters",
                        "treatment_response_filters")),
                List.of(List.of("treatment_id", "treatment_id"),
                        List.of("age_at_treatment_end", "age_at_treatment_end")),
                false);
    }

    /** Verifies treatment-response overview uses its own endpoint, projection, and exclusions. */
    @Test
    void returnsTreatmentResponseOverview() throws Exception {
        verifyOverview(
                "treatmentResponseOverview",
                "/treatment_responses_table/_search",
                "treatment_responses_table",
                "response",
                "response",
                Map.of("exclude", Set.of(
                        "sample_diagnosis_genetic_analysis_file_filters",
                        "survival_filters",
                        "treatment_filters")),
                List.of(List.of("treatment_response_id", "treatment_response_id"),
                        List.of("age_at_response", "age_at_response")),
                false);
    }

    /** Verifies survival overview uses the survival endpoint and excludes unrelated nested records. */
    @Test
    void returnsSurvivalOverview() throws Exception {
        verifyOverview(
                "survivalOverview",
                "/survivals_table/_search",
                "survivals_table",
                "first_event",
                "first_event",
                Map.of("exclude", Set.of(
                        "sample_diagnosis_genetic_analysis_file_filters",
                        "treatment_filters",
                        "treatment_response_filters")),
                List.of(List.of("survival_id", "survival_id"),
                        List.of("last_known_survival_status", "last_known_survival_status")),
                false);
    }

    /** Verifies sample overview maps its display fields and removes every unrelated nested family. */
    @Test
    void returnsSampleOverview() throws Exception {
        verifyOverview(
                "sampleOverview",
                "/samples_table/_search",
                "samples_table",
                "anatomic_site",
                "sample_anatomic_site_str",
                Map.of("exclude", Set.of(
                        "diagnosis_filters",
                        "genetic_analysis_filters",
                        "file_filters",
                        "survival_filters",
                        "treatment_filters",
                        "treatment_response_filters")),
                List.of(List.of("sample_id", "sample_id"),
                        List.of("anatomic_site", "sample_anatomic_site_str")),
                false);
    }

    /** Verifies file overview requests only its supported source fields and sortable keyword field. */
    @Test
    void returnsFileOverview() throws Exception {
        Set<String> includedFields = Set.of(
                "id", "file_id", "guid", "file_name", "data_category", "file_description",
                "file_type", "file_size", "library_selection", "library_source_material",
                "library_source_molecule", "library_strategy", "file_mapping_level", "file_access",
                "anatomic_site", "participant_age_at_collection", "sample_tumor_status",
                "tumor_spatial_extent", "sample_description", "percent_tumor", "percent_necrosis",
                "consent_codes", "fixation_embedding_method", "staining_method", "study_id",
                "participant_id", "sample_id", "md5sum", "files", "datamodel_dcf_indexd_guid",
                "datamodel_guid", "datamodel_file_id");

        verifyOverview(
                "fileOverview",
                "/files_table/_search",
                "files_table",
                "library_selection",
                "library_selection.sort",
                Map.of("includes", includedFields),
                List.of(List.of("guid", "datamodel_dcf_indexd_guid"),
                        List.of("datamodel_file_id", "datamodel_file_id")),
                false);
    }

    /** Verifies study overview limits the studies query to IDs aggregated from matching participants. */
    @Test
    void returnsStudyOverviewForMatchingParticipantStudies() throws Exception {
        verifyStudyOverview(List.of("STUDY-2", "STUDY-1"), List.of("STUDY-2", "STUDY-1"));
    }

    /** Verifies an empty participant aggregation becomes an impossible study ID rather than all studies. */
    @Test
    void returnsNoStudiesWhenNoParticipantStudyMatches() throws Exception {
        verifyStudyOverview(List.of(), List.of("-1"));
    }

    /** Verifies unsupported sort inputs fall back to the operation's default field and ascending order. */
    @Test
    void usesDefaultSortForUnsupportedOverviewSortInputs() throws Exception {
        Map<String, Object> params = pagingParams("not_a_field", "sideways", 5, 1);
        Map<String, Object> query = new HashMap<>();
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(PAGING_ARGUMENTS), eq(Set.of()),
                eq("nested_filters"), eq("diagnoses_table"))).thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class), eq(5), eq(1)))
                .thenReturn(List.of());

        invoke("diagnosisOverview", params);

        assertEquals(Map.of("diagnosis_id", "asc"), query.get("sort"));
    }

    private void verifyOverview(
            String methodName,
            String endpoint,
            String overviewType,
            String orderBy,
            String expectedSortField,
            Map<String, Set<String>> expectedSource,
            List<List<String>> expectedPropertyPairs,
            boolean returnEmptyPage) throws Exception {
        Map<String, Object> params = pagingParams(orderBy, "DESC", 17, 3);
        Map<String, Object> query = new HashMap<>();
        List<Map<String, Object>> expected = returnEmptyPage
                ? new ArrayList<>()
                : new ArrayList<>(List.of(new HashMap<>(Map.of("id", "result-1"))));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(PAGING_ARGUMENTS), eq(Set.of()),
                eq("nested_filters"), eq(overviewType))).thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class), eq(17), eq(3)))
                .thenReturn(expected);

        List<Map<String, Object>> result = invoke(methodName, params);

        assertSame(expected, result);
        assertEquals(Map.of(expectedSortField, "desc"), query.get("sort"));
        assertEquals(expectedSource, query.get("_source"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService).collectPage(
                requestCaptor.capture(), eq(query), propertiesCaptor.capture(), eq(17), eq(3));
        assertEquals(endpoint, requestCaptor.getValue().getEndpoint());
        List<List<String>> actualProperties = propertyPairs(propertiesCaptor.getValue());
        assertTrue(actualProperties.containsAll(expectedPropertyPairs));
    }

    private void verifyStudyOverview(List<String> bucketStudyIds, List<String> expectedStudyIds)
            throws Exception {
        Map<String, Object> params = pagingParams("study_name", "DESC", 11, 4);
        Map<String, Object> participantQuery = new HashMap<>();
        Map<String, Object> aggregationQuery = new HashMap<>();
        Map<String, Object> studyQuery = new HashMap<>();
        JsonObject aggregationResponse = new JsonObject();
        JsonArray buckets = new JsonArray();
        bucketStudyIds.forEach(studyId -> {
            JsonObject bucket = new JsonObject();
            bucket.addProperty("key", studyId);
            buckets.add(bucket);
        });

        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), eq(PAGING_ARGUMENTS), eq(Set.of()),
                eq("nested_filters"), any(String.class)))
                .thenAnswer(invocation -> "participants_table".equals(invocation.getArgument(5))
                        ? participantQuery : studyQuery);
        when(inventoryESService.addAggregations(eq(participantQuery), any(String[].class)))
                .thenReturn(aggregationQuery);
        when(inventoryESService.send(any(Request.class))).thenReturn(aggregationResponse);
        when(inventoryESService.collectTermAggs(
                eq(aggregationResponse), any(String[].class)))
                .thenReturn(Map.of("study_id", buckets));
        List<Map<String, Object>> expected = new ArrayList<>(List.of(
                new HashMap<>(Map.of("study_id", "STUDY-1"))));
        when(inventoryESService.collectPage(
                any(Request.class), eq(studyQuery), any(String[][].class), eq(11), eq(4)))
                .thenReturn(expected);

        List<Map<String, Object>> result = invoke("studyOverview", params);

        assertSame(expected, result);
        assertEquals(Map.of("study_name", "desc"), studyQuery.get("sort"));
        assertEquals(Map.of("exclude", Set.of("files")), studyQuery.get("_source"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(inventoryESService, org.mockito.Mockito.times(2)).buildFacetFilterQuery(
                paramsCaptor.capture(), anySet(), eq(PAGING_ARGUMENTS), eq(Set.of()),
                eq("nested_filters"), typeCaptor.capture());
        assertEquals(List.of("participants_table", "studies_table"), typeCaptor.getAllValues());
        assertEquals(expectedStudyIds, paramsCaptor.getAllValues().get(1).get("study_id"));

        ArgumentCaptor<Request> aggregationRequest = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(aggregationRequest.capture());
        assertEquals("/participants_table/_search", aggregationRequest.getValue().getEndpoint());

        ArgumentCaptor<Request> studyRequest = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService).collectPage(
                studyRequest.capture(), eq(studyQuery), propertiesCaptor.capture(), eq(11), eq(4));
        assertEquals("/studies_table/_search", studyRequest.getValue().getEndpoint());
        assertTrue(propertyPairs(propertiesCaptor.getValue()).containsAll(List.of(
                List.of("study_id", "study_id"),
                List.of("personnel_name", "PIs"),
                List.of("diagnosis", "diagnosis_cancer"))));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Map<String, Object> params) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(methodName, Map.class);
        method.setAccessible(true);
        try {
            return (T) method.invoke(dataFetcher, params);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static Map<String, Object> pagingParams(
            String orderBy, String direction, int first, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_by", orderBy);
        params.put("sort_direction", direction);
        params.put("first", first);
        params.put("offset", offset);
        return params;
    }

    private static List<List<String>> propertyPairs(String[][] properties) {
        List<List<String>> result = new ArrayList<>();
        for (String[] property : properties) {
            result.add(List.of(property));
        }
        return result;
    }
}
