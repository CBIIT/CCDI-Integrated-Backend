package gov.nih.nci.backendapi.overviewtables;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gov.nih.nci.bento_ri.model.ParticipantRequest;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.CPIFetcherService;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.AdditionalMatchers.aryEq;
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

    private static final List<List<String>> PARTICIPANT_PROPERTIES = pairs(
            "id", "id",
            "participant_id", "participant_id",
            "dbgap_accession", "dbgap_accession",
            "study_id", "study_id",
            "race", "race",
            "sex_at_birth", "sex_at_birth",
            "synonym_id", "alternate_participant_id",
            "files", "files",
            "diagnosis", "diagnosis_str",
            "anatomic_site", "diagnosis_anatomic_site_str",
            "diagnosis_category", "diagnosis_category_str",
            "age_at_diagnosis", "age_at_diagnosis_str",
            "treatment_agent", "treatment_agent_str",
            "treatment_type", "treatment_type_str",
            "age_at_treatment_start", "age_at_treatment_start_str",
            "first_event", "first_event_str",
            "last_known_survival_status", "last_known_survival_status_str",
            "age_at_last_known_survival_status", "age_at_last_known_survival_status_str");

    private static final List<List<String>> DIAGNOSIS_PROPERTIES = pairs(
            "id", "id",
            "pid", "pid",
            "diagnosis_id", "diagnosis_id",
            "participant_id", "participant_id",
            "dbgap_accession", "dbgap_accession",
            "study_id", "study_id",
            "diagnosis", "diagnosis",
            "anatomic_site", "diagnosis_anatomic_site",
            "disease_phase", "disease_phase",
            "diagnosis_classification_system", "diagnosis_classification_system",
            "diagnosis_basis", "diagnosis_basis",
            "diagnosis_category", "diagnosis_category",
            "age_at_diagnosis", "age_at_diagnosis",
            "diagnosis_comment", "diagnosis_comment",
            "tumor_spatial_extent", "tumor_spatial_extent",
            "toronto_childhood_cancer_staging", "toronto_childhood_cancer_staging",
            "tumor_grade", "tumor_grade",
            "tumor_stage_clinical_t", "tumor_stage_clinical_t",
            "tumor_stage_clinical_n", "tumor_stage_clinical_n",
            "tumor_stage_clinical_m", "tumor_stage_clinical_m",
            "tumor_stage_clinical_o", "tumor_stage_clinical_o");

    private static final List<List<String>> GENETIC_ANALYSIS_PROPERTIES = pairs(
            "ga_id", "id",
            "pid", "pid",
            "genetic_analysis_id", "genetic_analysis_id",
            "participant_id", "participant_id",
            "dbgap_accession", "dbgap_accession",
            "study_id", "study_id",
            "alteration", "alteration",
            "fusion_partner_gene", "fusion_partner_gene",
            "gene_symbol", "gene_symbol",
            "reported_significance", "reported_significance",
            "reported_significance_system", "reported_significance_system",
            "status", "status",
            "test", "test",
            "alteration_effect", "alteration_effect",
            "alteration_type", "alteration_type",
            "chromosome", "chromosome",
            "exon", "exon",
            "fusion_partner_exon", "fusion_partner_exon",
            "reference_genome", "reference_genome",
            "cytoband", "cytoband",
            "genomic_source_category", "genomic_source_category",
            "hgvs_coding", "hgvs_coding",
            "hgvs_genome", "hgvs_genome",
            "hgvs_protein", "hgvs_protein");

    private static final List<List<String>> TREATMENT_PROPERTIES = pairs(
            "t_id", "id",
            "pid", "pid",
            "treatment_id", "treatment_id",
            "participant_id", "participant_id",
            "dbgap_accession", "dbgap_accession",
            "study_id", "study_id",
            "treatment_type", "treatment_type",
            "treatment_agent", "treatment_agent",
            "age_at_treatment_start", "age_at_treatment_start",
            "age_at_treatment_end", "age_at_treatment_end");

    private static final List<List<String>> TREATMENT_RESPONSE_PROPERTIES = pairs(
            "tr_id", "id",
            "pid", "pid",
            "treatment_response_id", "treatment_response_id",
            "participant_id", "participant_id",
            "dbgap_accession", "dbgap_accession",
            "study_id", "study_id",
            "response", "response",
            "response_category", "response_category",
            "response_system", "response_system",
            "age_at_response", "age_at_response");

    private static final List<List<String>> SURVIVAL_PROPERTIES = pairs(
            "s_id", "id",
            "pid", "pid",
            "survival_id", "survival_id",
            "participant_id", "participant_id",
            "dbgap_accession", "dbgap_accession",
            "study_id", "study_id",
            "age_at_event_free_survival_status", "age_at_event_free_survival_status",
            "age_at_last_known_survival_status", "age_at_last_known_survival_status",
            "cause_of_death", "cause_of_death",
            "event_free_survival_status", "event_free_survival_status",
            "first_event", "first_event",
            "last_known_survival_status", "last_known_survival_status");

    private static final List<List<String>> STUDY_PROPERTIES = pairs(
            "id", "id",
            "study_id", "study_id",
            "grant_id", "grant_id",
            "dbgap_accession", "dbgap_accession",
            "study_name", "study_name",
            "study_phase", "study_phase",
            "personnel_name", "PIs",
            "num_of_participants", "num_of_participants",
            "diagnosis", "diagnosis_cancer",
            "num_of_samples", "num_of_samples",
            "anatomic_site", "diagnosis_anatomic_site",
            "num_of_files", "num_of_files",
            "file_type", "file_types",
            "pubmed_id", "pubmed_ids",
            "files", "files");

    private static final List<List<String>> SAMPLE_PROPERTIES = pairs(
            "id", "id",
            "sample_id", "sample_id",
            "participant_id", "participant_id",
            "study_id", "study_id",
            "anatomic_site", "sample_anatomic_site_str",
            "participant_age_at_collection", "participant_age_at_collection",
            "laterality", "laterality",
            "sample_description", "sample_description",
            "sample_tumor_status", "sample_tumor_status",
            "percent_tumor", "percent_tumor",
            "percent_necrosis", "percent_necrosis",
            "pdx_id", "pdx_id",
            "cell_line_id", "cell_line_id",
            "tumor_spatial_extent", "tumor_spatial_extent",
            "diagnosis", "diagnosis_str",
            "diagnosis_category", "diagnosis_category_str",
            "files", "files");

    private static final List<List<String>> FILE_PROPERTIES = pairs(
            "id", "id",
            "file_id", "file_id",
            "guid", "datamodel_dcf_indexd_guid",
            "datamodel_dcf_indexd_guid", "datamodel_dcf_indexd_guid",
            "datamodel_guid", "datamodel_guid",
            "datamodel_file_id", "datamodel_file_id",
            "file_name", "file_name",
            "data_category", "data_category",
            "file_description", "file_description",
            "file_type", "file_type",
            "file_size", "file_size",
            "library_selection", "library_selection",
            "library_source_material", "library_source_material",
            "library_source_molecule", "library_source_molecule",
            "library_strategy", "library_strategy",
            "file_mapping_level", "file_mapping_level",
            "file_access", "file_access",
            "anatomic_site", "anatomic_site",
            "participant_age_at_collection", "participant_age_at_collection",
            "sample_tumor_status", "sample_tumor_status",
            "tumor_spatial_extent", "tumor_spatial_extent",
            "sample_description", "sample_description",
            "percent_tumor", "percent_tumor",
            "percent_necrosis", "percent_necrosis",
            "consent_codes", "consent_codes",
            "fixation_embedding_method", "fixation_embedding_method",
            "staining_method", "staining_method",
            "study_id", "study_id",
            "participant_id", "participant_id",
            "sample_id", "sample_id",
            "md5sum", "md5sum",
            "files", "files");

    @Mock
    private InventoryESService inventoryESService;

    @Mock
    private CPIFetcherService cpiFetcherService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
        setField("cpiFetcherService", cpiFetcherService);
    }

    /** Verifies participant overview projection, participant endpoint, sorting, and nested exclusions. */
    @Test
    void returnsParticipantOverview() throws Exception {
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenReturn(List.of());

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
                PARTICIPANT_PROPERTIES,
                true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParticipantRequest>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cpiFetcherService).fetchAssociatedParticipantIds(idsCaptor.capture());
        assertEquals(1, idsCaptor.getValue().size());
        assertEquals("PARTICIPANT-1", idsCaptor.getValue().get(0).getParticipantId());
        assertEquals("STUDY-1", idsCaptor.getValue().get(0).getStudyId());
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
                DIAGNOSIS_PROPERTIES,
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
                GENETIC_ANALYSIS_PROPERTIES,
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
                TREATMENT_PROPERTIES,
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
                TREATMENT_RESPONSE_PROPERTIES,
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
                SURVIVAL_PROPERTIES,
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
                SAMPLE_PROPERTIES,
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
                FILE_PROPERTIES,
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
            boolean useParticipantFixture) throws Exception {
        Map<String, Object> params = pagingParams(orderBy, "DESC", 17, 3);
        Map<String, Object> query = new HashMap<>();
        List<Map<String, Object>> expected = useParticipantFixture
                ? new ArrayList<>(List.of(new HashMap<>(Map.of(
                        "id", "result-1",
                        "participant_id", "PARTICIPANT-1",
                        "study_id", "STUDY-1"))))
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
        assertEquals(expectedPropertyPairs, actualProperties);
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
        when(inventoryESService.addAggregations(
                eq(participantQuery), aryEq(new String[] {"study_id"})))
                .thenReturn(aggregationQuery);
        when(inventoryESService.send(any(Request.class))).thenReturn(aggregationResponse);
        when(inventoryESService.collectTermAggs(
                eq(aggregationResponse), aryEq(new String[] {"study_id"})))
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
        assertEquals(STUDY_PROPERTIES, propertyPairs(propertiesCaptor.getValue()));
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

    private void setField(String fieldName, Object value) throws Exception {
        Field field = PrivateESDataFetcher.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(dataFetcher, value);
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

    private static List<List<String>> pairs(String... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Projection fixtures require source/result pairs");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            result.add(List.of(values[index], values[index + 1]));
        }
        return List.copyOf(result);
    }

    private static List<List<String>> propertyPairs(String[][] properties) {
        List<List<String>> result = new ArrayList<>();
        for (String[] property : properties) {
            result.add(List.of(property));
        }
        return result;
    }
}
