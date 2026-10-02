package gov.nih.nci.backendapi.opensearchquery;

import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the shared facet-filter query builder. */
class InventoryESServiceFacetFilterQueryTest {

    private InventoryESService service;

    @BeforeEach
    void setUp() throws Exception {
        service = InventoryESServiceTestSupport.newService();
    }

    @AfterEach
    void tearDown() throws Exception {
        InventoryESServiceTestSupport.closeClient(service);
    }

    /** Verifies empty root and files-table filters produce match-all queries. */
    @Test
    void emptyFiltersProduceMatchAllQueries() throws IOException {
        assertEquals(Map.of("match_all", Map.of()),
                buildQuery(Map.of(), Set.of(), Set.of(), "participants_table").get("query"));
        assertEquals(Map.of("match_all", Map.of()),
                buildQuery(Map.of(), Set.of(), Set.of(), "files_table").get("query"));
    }

    /** Verifies participant filters are routed to their root and nested document families. */
    @Test
    void participantFiltersUseTheirExpectedNestedFamilies() throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("race", List.of("Asian"));
        params.put("participant_ids", List.of("P-1"));
        params.put("diagnosis", List.of("Neuroblastoma"));
        params.put("gene_symbol", List.of("ALK"));
        params.put("sample_tumor_status", List.of("Tumor"));
        params.put("file_type", List.of("BAM"));
        params.put("last_known_survival_status", List.of("Alive"));
        params.put("treatment_type", List.of("Chemotherapy"));
        params.put("response", List.of("Complete Response"));

        Map<String, Object> query = buildQuery(params, Set.of(), Set.of(), "participants_table");

        assertContains(query, terms("race", "Asian"));
        assertContains(query, terms("participant_id", "P-1"));
        assertContains(query, terms(
                "sample_diagnosis_genetic_analysis_file_filters.diagnosis", "Neuroblastoma"));
        assertContains(query, terms(
                "sample_diagnosis_genetic_analysis_file_filters.gene_symbol", "ALK"));
        assertContains(query, terms(
                "sample_diagnosis_genetic_analysis_file_filters.sample_tumor_status", "Tumor"));
        assertContains(query, terms(
                "sample_diagnosis_genetic_analysis_file_filters.file_type", "BAM"));
        assertEquals(Set.of(
                        "sample_diagnosis_genetic_analysis_file_filters",
                        "survival_filters",
                        "treatment_filters",
                        "treatment_response_filters"),
                nestedPaths(query));
        InventoryESServiceTestSupport.assertJsonRoundTrip(query);
    }

    /** Verifies file searches use the combined participant hierarchy and retain root file terms. */
    @Test
    void fileFiltersUseTheCombinedNestedHierarchy() throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("participant_ids", List.of("P-1"));
        params.put("diagnosis", List.of("Neuroblastoma"));
        params.put("gene_symbol", List.of("ALK"));
        params.put("sample_tumor_status", List.of("Tumor"));
        params.put("last_known_survival_status", List.of("Alive"));
        params.put("treatment_type", List.of("Chemotherapy"));
        params.put("response", List.of("Complete Response"));
        params.put("data_category", List.of("Sequencing Reads"));

        Map<String, Object> query = buildQuery(params, Set.of(), Set.of(), "files_table");

        assertContains(query, terms("combined_filters.participant_id", "P-1"));
        assertContains(query, terms(
                "combined_filters.sample_diagnosis_genetic_analysis_filters.diagnosis",
                "Neuroblastoma"));
        assertContains(query, terms(
                "combined_filters.sample_diagnosis_genetic_analysis_filters.sample_tumor_status",
                "Tumor"));
        assertContains(query, terms("data_category", "Sequencing Reads"));
        assertEquals(Set.of(
                        "combined_filters",
                        "combined_filters.sample_diagnosis_genetic_analysis_filters",
                        "combined_filters.survival_filters",
                        "combined_filters.treatment_filters",
                        "combined_filters.treatment_response_filters"),
                nestedPaths(query));
        InventoryESServiceTestSupport.assertJsonRoundTrip(query);
    }

    /** Verifies samples route diagnosis, genetic-analysis, and file terms independently. */
    @Test
    void sampleFiltersUseSeparateNestedFamilies() throws IOException {
        Map<String, Object> query = buildQuery(Map.of(
                        "diagnosis", List.of("Osteosarcoma"),
                        "gene_symbol", List.of("TP53"),
                        "file_type", List.of("BAM")),
                Set.of(), Set.of(), "samples_table");

        assertContains(query, terms("diagnosis_filters.diagnosis", "Osteosarcoma"));
        assertContains(query, terms("genetic_analysis_filters.gene_symbol", "TP53"));
        assertContains(query, terms("file_filters.file_type", "BAM"));
        assertEquals(Set.of("diagnosis_filters", "genetic_analysis_filters", "file_filters"),
                nestedPaths(query));
    }

    /** Verifies diagnosis and genetic-analysis indexes use their respective shared nested families. */
    @Test
    void indexSpecificFiltersUseTheCorrectSharedNestedFamily() throws IOException {
        Map<String, Object> diagnosisQuery = buildQuery(Map.of(
                        "sample_tumor_status", List.of("Tumor"),
                        "gene_symbol", List.of("ALK"),
                        "file_type", List.of("BAM")),
                Set.of(), Set.of(), "diagnoses_table");
        Map<String, Object> geneticQuery = buildQuery(Map.of(
                        "sample_tumor_status", List.of("Tumor"),
                        "diagnosis", List.of("Neuroblastoma"),
                        "file_type", List.of("BAM")),
                Set.of(), Set.of(), "genetic_analyses_table");

        assertEquals(Set.of("sample_genetic_analysis_file_filters"), nestedPaths(diagnosisQuery));
        assertContains(diagnosisQuery,
                terms("sample_genetic_analysis_file_filters.gene_symbol", "ALK"));
        assertEquals(Set.of("sample_diagnosis_file_filters"), nestedPaths(geneticQuery));
        assertContains(geneticQuery,
                terms("sample_diagnosis_file_filters.diagnosis", "Neuroblastoma"));
    }

    /** Verifies age ranges include the indexed unknown sentinel by default. */
    @Test
    void participantAgeRangeIncludesUnknownValuesByDefault() throws IOException {
        Map<String, Object> query = buildQuery(
                Map.of("age_at_diagnosis", List.of(365, 730)),
                Set.of("age_at_diagnosis"), Set.of(), "participants_table");

        assertContains(query, Map.of("range", Map.of(
                "sample_diagnosis_genetic_analysis_file_filters.age_at_diagnosis",
                Map.of("gte", 365, "lte", 730))));
        assertContains(query, Map.of("term", Map.of(
                "sample_diagnosis_genetic_analysis_file_filters.age_at_diagnosis", -999)));
    }

    /** Verifies the exclude-unknown option requires a value and rejects the unknown sentinel. */
    @Test
    void participantAgeRangeCanExcludeUnknownValues() throws IOException {
        Map<String, Object> query = buildQuery(Map.of(
                        "age_at_diagnosis", List.of(365, 730),
                        "age_at_diagnosis_unknownAges", List.of("exclude")),
                Set.of("age_at_diagnosis"), Set.of(), "participants_table");

        assertContains(query, Map.of("bool", Map.of(
                "must", List.of(Map.of("exists", Map.of("field",
                        "sample_diagnosis_genetic_analysis_file_filters.age_at_diagnosis"))),
                "must_not", List.of(Map.of("term", Map.of(
                        "sample_diagnosis_genetic_analysis_file_filters.age_at_diagnosis", -999))))));
    }

    /** Verifies the only-unknown option emits an exact sentinel terms filter. */
    @Test
    void participantAgeRangeCanSelectOnlyUnknownValues() throws IOException {
        Map<String, Object> query = buildQuery(Map.of(
                        "age_at_diagnosis", List.of(365, 730),
                        "age_at_diagnosis_unknownAges", List.of("only")),
                Set.of("age_at_diagnosis"), Set.of(), "participants_table");

        assertContains(query, terms(
                "sample_diagnosis_genetic_analysis_file_filters.age_at_diagnosis", -999));
    }

    /** Verifies every files-table age field uses its correct combined nested path. */
    @Test
    void fileAgeRangesUseTheirExpectedCombinedPaths() throws IOException {
        for (AgeRoute route : fileAgeRoutes()) {
            Map<String, Object> query = buildAgeQuery(route, null);

            assertContains(query, range(route.expectedPath()));
            assertContains(query, Map.of("term", Map.of(route.expectedPath(), -999)));
        }
    }

    /** Verifies blank unknown-age controls retain the default include-unknown behavior. */
    @Test
    void fileAgeRangeIgnoresBlankUnknownAgeControls() throws IOException {
        for (AgeRoute route : fileAgeRoutes()) {
            for (Object unknownControl : blankUnknownControls()) {
                Map<String, Object> params = new HashMap<>();
                params.put(route.field(), List.of(365, 730));
                params.put(route.field() + "_unknownAges", unknownControl);

                Map<String, Object> query = buildQuery(
                        params, Set.of(route.field()), Set.of(), route.indexType());

                assertContains(query, Map.of("term", Map.of(route.expectedPath(), -999)));
            }
        }
    }

    /** Verifies an unrecognized files-table unknown-age mode leaves the normal range in place. */
    @Test
    void fileAgeRangeIgnoresUnrecognizedUnknownAgeMode() throws IOException {
        Map<String, Object> query = buildAgeQuery(fileAgeRoutes().get(0), "include");

        assertContains(query, range(fileAgeRoutes().get(0).expectedPath()));
    }

    /** Verifies every files-table age field can exclude missing and unknown values. */
    @Test
    void fileAgeRangesCanExcludeUnknownValues() throws IOException {
        for (AgeRoute route : fileAgeRoutes()) {
            Map<String, Object> query = buildAgeQuery(route, "exclude");

            assertContains(query, excludesUnknown(fileUnknownExclusionPath(route)));
        }
    }

    /** Verifies every files-table age field can select only the unknown sentinel. */
    @Test
    void fileAgeRangesCanSelectOnlyUnknownValues() throws IOException {
        for (AgeRoute route : fileAgeRoutes()) {
            Map<String, Object> query = buildAgeQuery(route, "only");

            assertContains(query, terms(route.expectedPath(), -999));
        }
    }

    /** Verifies a non-age files-table range remains a root-level range filter. */
    @Test
    void fileRangeUsesRootFieldWhenItHasNoSpecialRoute() throws IOException {
        Map<String, Object> query = buildQuery(
                Map.of("file_size", List.of(100, 200)),
                Set.of("file_size"), Set.of(), "files_table");

        assertContains(query, Map.of("range", Map.of(
                "file_size", Map.of("gte", 100, "lte", 200))));
    }

    /** Verifies a files-table range with fewer than two bounds is ignored. */
    @Test
    void fileRangeWithTooFewBoundsIsIgnored() throws IOException {
        Map<String, Object> query = buildQuery(
                Map.of("file_size", List.of(100)),
                Set.of("file_size"), Set.of(), "files_table");

        assertEquals(Map.of("match_all", Map.of()), query.get("query"));
    }

    /** Verifies a files-table range can contain only an upper bound. */
    @Test
    void fileRangeCanUseOnlyUpperBound() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("file_size", Arrays.asList(null, 200));

        Map<String, Object> query = buildQuery(
                params, Set.of("file_size"), Set.of(), "files_table");

        assertContains(query, Map.of("range", Map.of("file_size", Map.of("lte", 200))));
    }

    /** Verifies a files-table range can contain only a lower bound. */
    @Test
    void fileRangeCanUseOnlyLowerBound() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("file_size", Arrays.asList(100, null));

        Map<String, Object> query = buildQuery(
                params, Set.of("file_size"), Set.of(), "files_table");

        assertContains(query, Map.of("range", Map.of("file_size", Map.of("gte", 100))));
    }

    /** Verifies files-table imports become study-and-participant clauses and ignore malformed entries. */
    @Test
    void fileImportDataBuildsStructuredSelectionClauses() throws IOException {
        Map<String, Object> query = buildQuery(Map.of("import_data", List.of(
                        "{\"study_id\":\"STUDY-1\",\"participant_id\":[\"P-1\",\"P-2\"]}",
                        "not-json")),
                Set.of(), Set.of(), "files_table");

        assertContains(query, Map.of("term", Map.of("study_id", "STUDY-1")));
        assertContains(query, Map.of("terms", Map.of("participant_id", List.of("P-1", "P-2"))));
    }

    /** Verifies age ranges are routed correctly for every root index layout. */
    @Test
    void rootIndexAgeRangesUseTheirExpectedPaths() throws IOException {
        for (AgeRoute route : rootAgeRoutes()) {
            Map<String, Object> query = buildAgeQuery(route, null);

            assertContains(query, range(route.expectedPath()));
            if (includesUnknownByDefault(route)) {
                assertContains(query, Map.of("term", Map.of(route.expectedPath(), -999)));
            }
        }
    }

    /** Verifies every root-index age layout can exclude missing and unknown values. */
    @Test
    void rootIndexAgeRangesCanExcludeUnknownValues() throws IOException {
        for (AgeRoute route : rootAgeRoutes()) {
            Map<String, Object> query = buildAgeQuery(route, "exclude");

            assertContains(query, excludesUnknown(route.expectedPath()));
        }
    }

    /** Verifies every root-index age layout can select only the unknown sentinel. */
    @Test
    void rootIndexAgeRangesCanSelectOnlyUnknownValues() throws IOException {
        for (AgeRoute route : rootAgeRoutes()) {
            Map<String, Object> query = buildAgeQuery(route, "only");

            assertContains(query, terms(route.expectedPath(), -999));
        }
    }

    /** Verifies blank controls retain include-unknown behavior for every non-files age route. */
    @Test
    void rootAgeRangesIgnoreBlankUnknownAgeControls() throws IOException {
        for (AgeRoute route : rootAgeRoutes()) {
            for (Object unknownControl : blankUnknownControls()) {
                Map<String, Object> params = new HashMap<>();
                params.put(route.field(), List.of(365, 730));
                params.put(route.field() + "_unknownAges", unknownControl);

                Map<String, Object> query = buildQuery(
                        params, Set.of(route.field()), Set.of(), route.indexType());

                assertContains(query, range(route.expectedPath()));
            }
        }
    }

    /** Verifies an unrecognized root-index unknown-age mode leaves the normal range in place. */
    @Test
    void rootAgeRangeIgnoresUnrecognizedUnknownAgeMode() throws IOException {
        AgeRoute route = rootAgeRoutes().get(0);

        Map<String, Object> query = buildAgeQuery(route, "include");

        assertContains(query, range(route.expectedPath()));
    }

    /** Verifies root-index ranges support short, upper-only, and lower-only bounds. */
    @Test
    void rootRangeHandlesIncompleteBounds() throws IOException {
        assertEquals(Map.of("match_all", Map.of()), buildQuery(
                Map.of("custom_range", List.of(100)),
                Set.of("custom_range"), Set.of(), "participants_table").get("query"));

        Map<String, Object> upperParams = new HashMap<>();
        upperParams.put("custom_range", Arrays.asList(null, 200));
        assertContains(buildQuery(upperParams, Set.of("custom_range"), Set.of(), "participants_table"),
                Map.of("range", Map.of("custom_range", Map.of("lte", 200))));

        Map<String, Object> lowerParams = new HashMap<>();
        lowerParams.put("custom_range", Arrays.asList(100, null));
        assertContains(buildQuery(lowerParams, Set.of("custom_range"), Set.of(), "participants_table"),
                Map.of("range", Map.of("custom_range", Map.of("gte", 100))));
    }

    /** Verifies unknown controls on non-age ranges do not enter age-specific routing. */
    @Test
    void nonAgeRangesIgnoreUnknownAgeControls() throws IOException {
        Map<String, Object> params = Map.of(
                "custom_range", List.of(100, 200),
                "custom_range_unknownAges", List.of("only"));

        assertContains(buildQuery(params, Set.of("custom_range"), Set.of(), "participants_table"),
                Map.of("range", Map.of("custom_range", Map.of("gte", 100, "lte", 200))));
        assertContains(buildQuery(params, Set.of("custom_range"), Set.of(), "files_table"),
                Map.of("range", Map.of("custom_range", Map.of("gte", 100, "lte", 200))));
    }

    /** Verifies a range with neither bound is rejected for root and file queries. */
    @Test
    void missingRangeBoundsAreRejected() {
        Map<String, Object> params = new HashMap<>();
        params.put("age_at_diagnosis", Arrays.asList(null, null));

        assertThrows(IOException.class, () -> buildQuery(
                params, Set.of("age_at_diagnosis"), Set.of(), "participants_table"));
        assertThrows(IOException.class, () -> buildQuery(
                params, Set.of("age_at_diagnosis"), Set.of(), "files_table"));
    }

    /** Verifies import selections become study-and-participant clauses and malformed entries are ignored. */
    @Test
    void importDataBuildsStructuredSelectionClauses() throws IOException {
        Map<String, Object> query = buildQuery(Map.of("import_data", List.of(
                        "{\"study_id\":\"STUDY-1\",\"participant_id\":[\"P-1\",\"P-2\"]}",
                        "not-json")),
                Set.of(), Set.of(), "participants_table");

        assertContains(query, Map.of("term", Map.of("study_id", "STUDY-1")));
        assertContains(query, Map.of("terms", Map.of("participant_id", List.of("P-1", "P-2"))));
    }

    /** Verifies empty, blank, and singleton imports follow the import-list guard contract. */
    @Test
    void importDataHandlesEveryListShape() throws IOException {
        for (String indexType : List.of("participants_table", "files_table")) {
            assertEquals(Map.of("match_all", Map.of()), buildQuery(
                    Map.of("import_data", List.of()), Set.of(), Set.of(), indexType).get("query"));
            assertEquals(Map.of("match_all", Map.of()), buildQuery(
                    Map.of("import_data", List.of("")), Set.of(), Set.of(), indexType).get("query"));

            Map<String, Object> singleton = buildQuery(Map.of("import_data", List.of(
                            "{\"study_id\":\"STUDY-1\",\"participant_id\":[\"P-1\"]}")),
                    Set.of(), Set.of(), indexType);
            assertContains(singleton, Map.of("term", Map.of("study_id", "STUDY-1")));
        }
    }

    /** Verifies empty, blank, singleton, and multi-valued terms follow the value-list guard. */
    @Test
    void termFiltersHandleEveryListShape() throws IOException {
        for (String indexType : List.of("participants_table", "files_table")) {
            assertEquals(Map.of("match_all", Map.of()), buildQuery(
                    Map.of("custom_term", List.of()), Set.of(), Set.of(), indexType).get("query"));
            assertEquals(Map.of("match_all", Map.of()), buildQuery(
                    Map.of("custom_term", List.of("")), Set.of(), Set.of(), indexType).get("query"));
            assertContains(buildQuery(Map.of("custom_term", List.of("one")),
                            Set.of(), Set.of(), indexType),
                    terms("custom_term", "one"));
            assertContains(buildQuery(Map.of("custom_term", List.of("one", "two")),
                            Set.of(), Set.of(), indexType),
                    terms("custom_term", "one", "two"));
        }
    }

    /** Verifies terms remain at the root of indexes that own those fields. */
    @Test
    void directIndexTermsRemainAtTheRoot() throws IOException {
        Map<String, String> routes = Map.of(
                "survivals_table", "last_known_survival_status",
                "treatments_table", "treatment_type",
                "treatment_responses_table", "response",
                "diagnoses_table", "diagnosis",
                "genetic_analyses_table", "gene_symbol");

        for (Map.Entry<String, String> route : routes.entrySet()) {
            Map<String, Object> query = buildQuery(
                    Map.of(route.getValue(), List.of("value")),
                    Set.of(), Set.of(), route.getKey());
            assertContains(query, terms(route.getValue(), "value"));
        }
    }

    /** Verifies range routing falls back when an index matches but the age field does not. */
    @Test
    void indexSpecificRangesFallBackForOtherAgeFields() throws IOException {
        for (AgeRoute route : List.of(
                new AgeRoute("samples_table", "participant_age_at_collection", "participant_age_at_collection"),
                new AgeRoute("diagnoses_table", "age_at_response",
                        "treatment_response_filters.age_at_response"),
                new AgeRoute("genetic_analyses_table", "age_at_response",
                        "treatment_response_filters.age_at_response"),
                new AgeRoute("participants_table", "age_at_treatment_end",
                        "treatment_filters.age_at_treatment_end"))) {
            assertContains(buildAgeQuery(route, null), range(route.expectedPath()));
        }
    }

    /** Verifies exclude and only controls use the fallback route for other index-specific ages. */
    @Test
    void indexSpecificUnknownAgeModesUseFallbackRoutes() throws IOException {
        for (String mode : List.of("exclude", "only")) {
            AgeRoute sampleFallback = new AgeRoute(
                    "samples_table", "participant_age_at_collection", "participant_age_at_collection");
            Map<String, Object> sampleQuery = buildAgeQuery(sampleFallback, mode);
            assertContains(sampleQuery, mode.equals("exclude")
                    ? excludesUnknown(sampleFallback.expectedPath())
                    : terms(sampleFallback.expectedPath(), -999));

            AgeRoute geneticFallback = new AgeRoute(
                    "genetic_analyses_table", "age_at_response",
                    "treatment_response_filters.age_at_response");
            Map<String, Object> geneticQuery = buildAgeQuery(geneticFallback, mode);
            assertContains(geneticQuery, mode.equals("exclude")
                    ? excludesUnknown(geneticFallback.expectedPath())
                    : terms(geneticFallback.expectedPath(), -999));

            AgeRoute treatmentFallback = new AgeRoute(
                    "participants_table", "age_at_treatment_end",
                    "treatment_filters.age_at_treatment_end");
            Map<String, Object> treatmentQuery = buildAgeQuery(treatmentFallback, mode);
            assertContains(treatmentQuery, mode.equals("exclude")
                    ? excludesUnknown(treatmentFallback.expectedPath())
                    : terms(treatmentFallback.expectedPath(), -999));
        }
    }

    /** Verifies excluded and all-record arguments do not create filter clauses. */
    @Test
    void excludedAndEmptyArgumentsDoNotCreateFilters() throws IOException {
        Map<String, Object> query = buildQuery(Map.of(
                        "race", List.of(""),
                        "page_size", List.of("25")),
                Set.of(), Set.of("page_size"), "participants_table");

        assertEquals(Map.of("match_all", Map.of()), query.get("query"));
    }

    private Map<String, Object> buildQuery(
            Map<String, Object> params,
            Set<String> rangeParams,
            Set<String> excludedParams,
            String indexType) throws IOException {
        return service.buildFacetFilterQuery(
                params, rangeParams, excludedParams, Set.of(), "", indexType);
    }

    private Map<String, Object> buildAgeQuery(AgeRoute route, String unknownMode)
            throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put(route.field(), List.of(365, 730));
        if (unknownMode != null) {
            params.put(route.field() + "_unknownAges", List.of(unknownMode));
        }
        return buildQuery(params, Set.of(route.field()), Set.of(), route.indexType());
    }

    private static List<AgeRoute> fileAgeRoutes() {
        return List.of(
                new AgeRoute("files_table", "age_at_diagnosis",
                        "combined_filters.sample_diagnosis_genetic_analysis_filters.age_at_diagnosis"),
                new AgeRoute("files_table", "participant_age_at_collection",
                        "combined_filters.sample_diagnosis_genetic_analysis_filters.participant_age_at_collection"),
                new AgeRoute("files_table", "age_at_treatment_start",
                        "combined_filters.treatment_filters.age_at_treatment_start"),
                new AgeRoute("files_table", "age_at_treatment_end",
                        "combined_filters.treatment_filters.age_at_treatment_end"),
                new AgeRoute("files_table", "age_at_response",
                        "combined_filters.treatment_response_filters.age_at_response"),
                new AgeRoute("files_table", "age_at_last_known_survival_status",
                        "combined_filters.survival_filters.age_at_last_known_survival_status"));
    }

    private static List<AgeRoute> rootAgeRoutes() {
        return List.of(
                new AgeRoute("participants_table", "age_at_diagnosis",
                        "sample_diagnosis_genetic_analysis_file_filters.age_at_diagnosis"),
                new AgeRoute("study_participants_faceted", "participant_age_at_collection",
                        "sample_diagnosis_genetic_analysis_file_filters.participant_age_at_collection"),
                new AgeRoute("samples_table", "age_at_diagnosis", "diagnosis_filters.age_at_diagnosis"),
                new AgeRoute("diagnoses_table", "age_at_diagnosis", "age_at_diagnosis"),
                new AgeRoute("diagnoses_table", "participant_age_at_collection",
                        "sample_genetic_analysis_file_filters.participant_age_at_collection"),
                new AgeRoute("genetic_analyses_table", "participant_age_at_collection",
                        "sample_diagnosis_file_filters.participant_age_at_collection"),
                new AgeRoute("participants_table", "age_at_treatment_start",
                        "treatment_filters.age_at_treatment_start"),
                new AgeRoute("treatments_table", "age_at_treatment_end", "age_at_treatment_end"),
                new AgeRoute("participants_table", "age_at_response", "treatment_response_filters.age_at_response"),
                new AgeRoute("treatment_responses_table", "age_at_response", "age_at_response"),
                new AgeRoute("participants_table", "age_at_last_known_survival_status",
                        "survival_filters.age_at_last_known_survival_status"),
                new AgeRoute("survivals_table", "age_at_last_known_survival_status",
                        "age_at_last_known_survival_status"));
    }

    private static List<Object> blankUnknownControls() {
        return Arrays.asList(null, List.of(), List.of(""));
    }

    private static String fileUnknownExclusionPath(AgeRoute route) {
        if (route.field().equals("age_at_diagnosis")
                || route.field().equals("participant_age_at_collection")) {
            return "combined_filters.sample_diagnosis_filters." + route.field();
        }
        return route.expectedPath();
    }

    private static boolean includesUnknownByDefault(AgeRoute route) {
        return !route.indexType().equals("treatments_table")
                && !route.indexType().equals("treatment_responses_table")
                && !route.indexType().equals("survivals_table");
    }

    private static Map<String, Object> range(String field) {
        return Map.of("range", Map.of(field, Map.of("gte", 365, "lte", 730)));
    }

    private static Map<String, Object> excludesUnknown(String field) {
        return Map.of("bool", Map.of(
                "must", List.of(Map.of("exists", Map.of("field", field))),
                "must_not", List.of(Map.of("term", Map.of(field, -999)))));
    }

    private static Map<String, Object> terms(String field, Object... values) {
        return Map.of("terms", Map.of(field, List.of(values)));
    }

    private static void assertContains(Object query, Object expectedClause) {
        assertTrue(containsDeep(query, expectedClause),
                () -> "Expected clause " + expectedClause + " in " + query);
    }

    private static boolean containsDeep(Object value, Object expected) {
        if (expected.equals(value)) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(child -> containsDeep(child, expected));
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                if (containsDeep(child, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> nestedPaths(Object value) {
        Set<String> paths = new HashSet<>();
        collectNestedPaths(value, paths);
        return paths;
    }

    private static void collectNestedPaths(Object value, Set<String> paths) {
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("nested");
            if (nested instanceof Map<?, ?> nestedMap && nestedMap.get("path") instanceof String path) {
                paths.add(path);
            }
            map.values().forEach(child -> collectNestedPaths(child, paths));
        } else if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                collectNestedPaths(child, paths);
            }
        }
    }

    private record AgeRoute(String indexType, String field, String expectedPath) {
    }
}
