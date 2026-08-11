package gov.nih.nci.bento_ri.model;

import gov.nih.nci.bento.constants.Const;
import gov.nih.nci.bento.model.AbstractPrivateESDataFetcher;
import gov.nih.nci.bento.model.search.yaml.YamlQueryFactory;
import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento_ri.service.InventoryESService;
import gov.nih.nci.bento_ri.service.CPIFetcherService;
import graphql.schema.idl.RuntimeWiring;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.Request;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.io.InputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

@Component
public class PrivateESDataFetcher extends AbstractPrivateESDataFetcher {
    private static final Logger logger = LogManager.getLogger(PrivateESDataFetcher.class);
    private final YamlQueryFactory yamlQueryFactory;
    private InventoryESService inventoryESService;
    @Autowired
    private CPIFetcherService cpiFetcherService;
    @Autowired
    private Cache<String, Object> caffeineCache;

    final String CARDINALITY_AGG_NAME = "cardinality_agg_name";
    final String CARDINALITY_INDEX_NAME = "cardinality_index_name";
    final String AGG_NAME = "agg_name";
    final String AGG_ENDPOINT = "agg_endpoint";
    final String WIDGET_QUERY = "widget_count_name";
    final String FILTER_COUNT_QUERY = "filter_count_name";
    final String ADDITIONAL_UPDATE = "additional_update";

    private Map<String, List<Map<String, Object>>> facetFilters;
    private Map<String, Map<String, String>> cohortChartProperties;

    // parameters used in queries
    final String PAGE_SIZE = "first";
    final String OFFSET = "offset";
    final String ORDER_BY = "order_by";
    final String SORT_DIRECTION = "sort_direction";

    /** Maximum number of threads for async CPI batches in idsLists (C3DC-aligned). */
    final int THREAD_POOL_SIZE = 8;

    final String COHORTS_END_POINT = "/cohorts/_search";
    final String COHORT_MANIFEST_END_POINT = "/diagnoses_cohort_manifest/_search";
    final String PARTICIPANTS_END_POINT = "/participants_table/_search";
    final String SURVIVALS_END_POINT = "/survivals_table/_search";
    final String KM_PLOT_DATA_END_POINT = "/km_plot_data/_search";
    final String KM_PLOT_DATA_INDEX = "km_plot_data";
    final String KM_PLOT_DATA_IS_VALID = "is_valid";
    final String TREATMENTS_END_POINT = "/treatments_table/_search";
    final String TREATMENT_RESPONSES_END_POINT = "/treatment_responses_table/_search";
    final String DIAGNOSIS_END_POINT = "/diagnoses_table/_search";
    final String GENETIC_ANALYSES_END_POINT = "/genetic_analyses_table/_search";
    final String STUDIES_END_POINT = "/studies_table/_search";
    final String STUDIES_FOR_COHORTS_END_POINT = "/studies_for_cohorts/_search";
    final String SAMPLES_END_POINT = "/samples_table/_search";
    final String FILES_END_POINT = "/files_table/_search";
    // Matches indices.yaml index_name for type: about_file (searchablePagesContent.yaml)
    final String GS_ABOUT_END_POINT = "/about_page/_search";
    final String NODES_END_POINT = "/model_nodes/_search";
    final String PROPERTIES_END_POINT = "/model_properties/_search";
    final String VALUES_END_POINT = "/model_values/_search";

    final String PARTICIPANTS_COUNT_END_POINT = "/participants_table/_count";
    final String SURVIVALS_COUNT_END_POINT = "/survivals_table/_count";
    final String TREATMENTS_COUNT_END_POINT = "/treatments_table/_count";
    final String TREATMENT_RESPONSES_COUNT_END_POINT = "/treatment_responses_table/_count";
    final String DIAGNOSIS_COUNT_END_POINT = "/diagnoses_table/_count";
    final String GENETIC_ANALYSES_COUNT_END_POINT = "/genetic_analyses_table/_count";
    final String STUDIES_COUNT_END_POINT = "/studies_table/_count";
    final String SAMPLES_COUNT_END_POINT = "/samples_table/_count";
    final String FILES_COUNT_END_POINT = "/files_table/_count";
    final String NODES_COUNT_END_POINT = "/model_nodes/_count";
    final String PROPERTIES_COUNT_END_POINT = "/model_properties/_count";
    final String VALUES_COUNT_END_POINT = "/model_values/_count";
    final Map<String, String> ENDPOINTS = Map.ofEntries( // Used to access endpoints when iterating over a list of Opensearch indices
        Map.entry("diagnoses_table", DIAGNOSIS_END_POINT),
        Map.entry("files_table", FILES_END_POINT),
        Map.entry("genetic_analyses_table", GENETIC_ANALYSES_END_POINT),
        Map.entry("km_plot_data", KM_PLOT_DATA_END_POINT),
        Map.entry("participants_table", PARTICIPANTS_END_POINT),
        Map.entry("samples_table", SAMPLES_END_POINT),
        Map.entry("survivals_table", SURVIVALS_END_POINT),
        Map.entry("treatments_table", TREATMENTS_END_POINT),
        Map.entry("treatment_responses_table", TREATMENT_RESPONSES_END_POINT)
    );

    final String GS_END_POINT = "endpoint";
    final String GS_COUNT_ENDPOINT = "count_endpoint";
    final String GS_RESULT_FIELD = "result_field";
    final String GS_COUNT_RESULT_FIELD = "count_result_field";
    final String GS_SEARCH_FIELD = "search_field";
    final String GS_COLLECT_FIELDS = "collect_fields";
    final String GS_SORT_FIELD = "sort_field";
    final String GS_CATEGORY_TYPE = "category_type";
    final String GS_ABOUT = "about";
    final String GS_HIGHLIGHT_FIELDS = "highlight_fields";
    final String GS_HIGHLIGHT_DELIMITER = "$";
    
    final Set<String> RANGE_PARAMS = Set.of("age_at_diagnosis", "participant_age_at_collection","age_at_treatment_start", "age_at_treatment_end", "age_at_response", "age_at_last_known_survival_status");

    final Set<String> INCLUDE_PARAMS  = Set.of("race", "data_category");

    // Cohort Chart bucket limits
    final int COHORT_CHART_BUCKET_LIMIT_HIGH = 20;
    final int COHORT_CHART_BUCKET_LIMIT_LOW = 5;

    //default supporting data
    final Map<String, String> DEFAULT_IDC_DATA = Map.of(
        "phs002790", new Gson().toJson(Map.of("collection_id", "ccdi_mci",
        "cancer_type", "Various", 
        "date_updated", "2025-09-12", 
        "description", "The Molecular Characterization Initiative (MCI) is a component of the National Cancer Institute’s (NCI) Childhood Cancer Data Initiative (CCDI). It offers state-of-the-art molecular testing at no cost to newly diagnosed children, adolescents, and young adults (AYAs) with central nervous system (CNS) tumors, soft tissue sarcomas (STS), certain rare childhood cancers (RAR), and certain neuroblastomas (NBL) treated at a Children’s Oncology Group (COG)–affiliated hospital. The goal of MCI is to enhance the understanding of genetic factors in pediatric cancers and to provide timely, clinically relevant findings to doctors and families to aid in treatment decisions and determine eligibility for certain planned COG clinical trials.</p>\n<p>\nPlease see the <a target=\"_blank\" href=\"https://doi.org/10.5281/zenodo.11099086\" data-toggle=\"modal\" data-target=\"#external-web-warning\" class=\"external-link\" data-toggle=\"modal\" data-target=\"#external-web-warning\">DICOM converted whole slide hematoxylin and eosin stained images from the Molecular Characterization Initiative of the National Cancer Institute's Childhood Cancer Data Initiative\n <i class=\"fa-solid fa-external-link external-link-icon\" aria-hidden=\"true\"></i></a> information page to learn more about the images and any supporting metadata for this collection, and to learn about attribution/citation requirements.</p>\n", 
        "doi", "10.5281/zenodo.11099086", "image_types", "SM", "location", "Various", "species", "Human", "subject_count", "4055", "supporting_data", ""))
    );
    final Map<String, String> DEFAULT_TCIA_DATA = Map.of();

    public PrivateESDataFetcher(InventoryESService esService) throws IOException {
        super(esService);
        inventoryESService = esService;
        yamlQueryFactory = new YamlQueryFactory(esService);

        // Load facet filters
        try {
            String facetFiltersPath = Const.YAML_QUERY.SUB_FOLDER + "facet_filters.yaml";
            ClassPathResource facetFiltersResource = new ClassPathResource(facetFiltersPath);
            try (InputStream facetFilterFileStream = facetFiltersResource.getInputStream()) {
                Yaml facetFilterYaml = new Yaml();
                this.facetFilters = facetFilterYaml.load(facetFilterFileStream);
            }

            String cohortChartPropertiesPath = Const.YAML_QUERY.SUB_FOLDER + "cohort_chart_properties.yaml";
            ClassPathResource cohortChartPropertiesResource = new ClassPathResource(cohortChartPropertiesPath);
            try (InputStream cohortChartPropertiesFileStream = cohortChartPropertiesResource.getInputStream()) {
                Yaml cohortChartPropertiesYaml = new Yaml();
                this.cohortChartProperties = cohortChartPropertiesYaml.load(cohortChartPropertiesFileStream);
            }
        } catch (IOException e) {
            logger.error("Error reading facet or cohort chart configuration: " + e);
            throw new IOException(e.toString());
        }
    }

    @Override
    public RuntimeWiring buildRuntimeWiring() throws IOException {
        return RuntimeWiring.newRuntimeWiring()
                .type(newTypeWiring("QueryType")
                        .dataFetchers(yamlQueryFactory.createYamlQueries(Const.ES_ACCESS_TYPE.PRIVATE))
                        .dataFetcher("idsLists", env -> {
                            Map<String, Object> args = new HashMap<>(env.getArguments());
                            args.putIfAbsent("cpi_batch_size", 2500);
                            args.putIfAbsent("use_cache", true);
                            return idsLists(args);
                        })
                        .dataFetcher("searchParticipants", env -> {
                            Map<String, Object> args = env.getArguments();
                            return searchParticipants(args);
                        })
                        .dataFetcher("cohortManifest", env -> {
                            Map<String, Object> args = env.getArguments();
                            return cohortManifest(args);
                        })
                        .dataFetcher("cohortMetadata", env -> {
                            Map<String, Object> args = env.getArguments();
                            return cohortMetadata(args);
                        })
                        .dataFetcher("cohortCharts", env -> {
                            Map<String, Object> args = env.getArguments();
                            return cohortCharts(args);
                        })
                        .dataFetcher("kMPlot", env -> {
                            Map<String, Object> args = env.getArguments();
                            return kMPlot(args);
                        })
                        .dataFetcher("riskTableData", env -> {
                            Map<String, Object> args = env.getArguments();
                            return riskTableData(args);
                        })
                        .dataFetcher("studyDetails", env -> {
                            Map<String, Object> args = env.getArguments();
                            return studyDetails(args);
                        })
                        .dataFetcher("studiesListing", env -> {
                            Map<String, Object> args = env.getArguments();
                            return studiesListing(args);
                        })
                        .dataFetcher("participantOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return participantOverview(args);
                        })
                        .dataFetcher("diagnosisOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return diagnosisOverview(args);
                        })
                        .dataFetcher("geneticAnalysisOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return geneticAnalysisOverview(args);
                        })
                        .dataFetcher("survivalOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return survivalOverview(args);
                        })
                        .dataFetcher("treatmentOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return treatmentOverview(args);
                        })
                        .dataFetcher("treatmentResponseOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return treatmentResponseOverview(args);
                        })
                        .dataFetcher("studyOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return studyOverview(args);
                        })
                        .dataFetcher("sampleOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return sampleOverview(args);
                        })
                        .dataFetcher("fileOverview", env -> {
                            Map<String, Object> args = env.getArguments();
                            return fileOverview(args);
                        })
                        .dataFetcher("numberOfDiseases", env -> {
                            Map<String, Object> args = env.getArguments();
                            return numberOfDiseases(args);
                        })
                        .dataFetcher("numberOfParticipants", env -> {
                            Map<String, Object> args = env.getArguments();
                            return numberOfParticipants(args);
                        })
                        .dataFetcher("numberOfStudies", env -> {
                            Map<String, Object> args = env.getArguments();
                            return numberOfStudies(args);
                        })
                        .dataFetcher("fileIDsFromList", env -> {
                            Map<String, Object> args = env.getArguments();
                            return fileIDsFromList(args);
                        })
                        .dataFetcher("numberOfMCICount", env -> {
                            Map<String, Object> args = env.getArguments();
                            return getParticipantsCount();
                        })
                        .dataFetcher("findParticipantIdsInList", env -> {
                            Map<String, Object> args = env.getArguments();
                            return findParticipantIdsInList(args);
                        })
                        .dataFetcher("filesManifestInList", env -> {
                            Map<String, Object> args = env.getArguments();
                            return filesManifestInList(args);
                        })
                        .dataFetcher("globalSearch", env -> {
                            Map<String, Object> args = env.getArguments();
                            return globalSearch(args);
                        })
                        .dataFetcher("getFilenames", env -> {
                            Map<String, Object> args = env.getArguments();
                            return getFilenames(args);
                        })
                )
                .build();
    }

    private Map<String, Object> addHighlight(Map<String, Object> query, Map<String, Object> category) {
        Map<String, Object> result = new HashMap<>(query);
        List<String> searchFields = (List<String>)category.get(GS_SEARCH_FIELD);
        Map<String, Object> highlightClauses = new HashMap<>();
        for (String searchFieldName: searchFields) {
            highlightClauses.put(searchFieldName, Map.of());
        }

        result.put("highlight", Map.of(
                        "fields", highlightClauses,
                        "pre_tags", "",
                        "post_tags", "",
                        "fragment_size", 1
                )
        );
        return result;
    }

    private Map<String, Object> getGlobalSearchQuery(String input, Map<String, Object> category) {
        List<String> searchFields = (List<String>)category.get(GS_SEARCH_FIELD);
        List<Object> searchClauses = new ArrayList<>();
        String normalizedInput = input == null ? "" : input.trim();
        // Prefer denormalized *_gs keyword fields when listed in GS_SEARCH_FIELD.
        // Case-insensitive prefix + contains wildcards keep typing responsive on keyword fields.
        String wildcardValue = "*" + escapeWildcard(normalizedInput) + "*";
        for (String searchFieldName: searchFields) {
            if (normalizedInput.isEmpty()) {
                continue;
            }
            searchClauses.add(Map.of(
                    "prefix", Map.of(searchFieldName, Map.of(
                            "value", normalizedInput,
                            "case_insensitive", true
                    ))
            ));
            searchClauses.add(Map.of(
                    "wildcard", Map.of(searchFieldName, Map.of(
                            "value", wildcardValue,
                            "case_insensitive", true
                    ))
            ));
        }
        Map<String, Object> query = new HashMap<>();
        if (searchClauses.isEmpty()) {
            // No searchable input: return zero hits instead of match_all.
            query.put("query", Map.of("bool", Map.of("must_not", Map.of("match_all", Map.of()))));
            return query;
        }
        String indexType = (String)category.get(GS_CATEGORY_TYPE);
        if (indexType.equals("file")) {
            query.put("query", Map.of("bool", Map.of(
                    "must", Map.of("exists", Map.of("field", "file_id")),
                    "should", searchClauses,
                    "minimum_should_match", 1
            )));
        } else {
            query.put("query", Map.of("bool", Map.of(
                    "should", searchClauses,
                    "minimum_should_match", 1
            )));
        }
        return query;
    }

    private String escapeWildcard(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '*' || c == '?') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private List paginate(List org, int pageSize, int offset) {
        List<Object> result = new ArrayList<>();
        int size = org.size();
        if (offset <= size -1) {
            int end_index = offset + pageSize;
            if (end_index > size) {
                end_index = size;
            }
            result = org.subList(offset, end_index);
        }
        return result;
    }

    private List<Map<String, Object>> searchAboutPage(String input) throws IOException {
        final String ABOUT_CONTENT = "content.paragraph";
        // Search indexed paragraph text and page titles from searchablePagesContent.yaml.
        Map<String, Object> query = Map.of(
                "query", Map.of(
                        "multi_match", Map.of(
                                "query", input == null ? "" : input,
                                "fields", List.of(ABOUT_CONTENT, "title", "page"),
                                "type", "best_fields"
                        )
                ),
                "highlight", Map.of(
                        "fields", Map.of(
                                ABOUT_CONTENT, Map.of(),
                                "title", Map.of()
                        ),
                        "pre_tags", GS_HIGHLIGHT_DELIMITER,
                        "post_tags", GS_HIGHLIGHT_DELIMITER
                ),
                "size", 10000
        );
        Request request = new Request("GET", GS_ABOUT_END_POINT);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject;
        try {
            jsonObject = esService.send(request);
        } catch (IOException e) {
            // About-page index (about_page) may not be present in all environments.
            logger.warn("About-page global search skipped: " + e.getMessage());
            return new ArrayList<>();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (JsonElement hit: jsonObject.get("hits").getAsJsonObject().get("hits").getAsJsonArray()) {
            JsonObject source = hit.getAsJsonObject().get("_source").getAsJsonObject();
            String page = source.has("page") && !source.get("page").isJsonNull()
                    ? source.get("page").getAsString() : "";
            String title = source.has("title") && !source.get("title").isJsonNull()
                    ? source.get("title").getAsString() : "";
            List<String> list = new ArrayList<>();
            JsonObject highlight = hit.getAsJsonObject().has("highlight")
                    ? hit.getAsJsonObject().get("highlight").getAsJsonObject() : null;
            if (highlight != null && highlight.has(ABOUT_CONTENT)) {
                JsonArray arr = highlight.get(ABOUT_CONTENT).getAsJsonArray();
                for (var element: arr) {
                    list.add(element.getAsString());
                }
            }
            // If highlight misses (e.g. title-only hit), fall back to matching paragraphs from _source.
            if (list.isEmpty() && source.has("content") && source.get("content").isJsonArray()) {
                String needle = input == null ? "" : input.trim().toLowerCase();
                for (JsonElement contentEl : source.get("content").getAsJsonArray()) {
                    if (!contentEl.isJsonObject() || !contentEl.getAsJsonObject().has("paragraph")) {
                        continue;
                    }
                    String paragraph = contentEl.getAsJsonObject().get("paragraph").getAsString();
                    if (needle.isEmpty() || paragraph.toLowerCase().contains(needle)) {
                        list.add(paragraph);
                    }
                }
                if (list.isEmpty() && source.get("content").getAsJsonArray().size() > 0) {
                    JsonObject first = source.get("content").getAsJsonArray().get(0).getAsJsonObject();
                    if (first.has("paragraph")) {
                        list.add(first.get("paragraph").getAsString());
                    }
                }
            }
            Map<String, Object> aboutHit = new HashMap<>();
            aboutHit.put(GS_CATEGORY_TYPE, GS_ABOUT);
            aboutHit.put("type", "about_page");
            aboutHit.put("page", page);
            aboutHit.put("title", title);
            aboutHit.put("text", list);
            result.add(aboutHit);
        }

        return result;
    }

    private Map<String, Object> globalSearch(Map<String, Object> params) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String input = (String) params.get("input");
        int size = (int) params.get("first");
        int offset = (int) params.get("offset");
        List<Map<String, Object>> searchCategories = new ArrayList<>();
        searchCategories.add(Map.of(
                GS_END_POINT, PARTICIPANTS_END_POINT,
                GS_COUNT_ENDPOINT, PARTICIPANTS_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "participant_count",
                GS_RESULT_FIELD, "participants",
                // Search denormalized *_gs keyword fields (indices.yaml global_search_* queries).
                // Keep existing collect + nested-filter enrichment for card display / CPI unchanged.
                GS_SEARCH_FIELD, List.of(
                        "participant_id_gs", "study_id_gs", "sex_at_birth_gs", "race_str_gs",
                        "treatment_type_str_gs", "treatment_agent_str_gs",
                        "diagnosis_str_gs", "diagnosis_category_str_gs", "age_at_diagnosis_str_gs",
                        "last_known_survival_status_str_gs",
                        "study_name", "study_acronym", "study_phase", "dbgap_accession"
                ),
                GS_SORT_FIELD, "participant_id",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"id", "id"},
                        new String[]{"participant_id", "participant_id"},
                        new String[]{"diagnosis_str", "diagnosis_str"},
                        new String[]{"diagnosis_category_str", "diagnosis_category_str"},
                        new String[]{"age_at_diagnosis_str", "age_at_diagnosis_str"},
                        new String[]{"treatment_agent_str", "treatment_agent_str"},
                        new String[]{"treatment_type_str", "treatment_type_str"},
                        new String[]{"study_id", "study_id"},
                        new String[]{"race_str", "race_str"},
                        new String[]{"sex_at_birth", "sex_at_birth"},
                        new String[]{"last_known_survival_status_str", "last_known_survival_status_str"},
                        new String[]{"consent_codes", "consent_codes"},
                        // Nested filter arrays used to build display strings after fetch
                        new String[]{"_sample_diagnosis_filters", "sample_diagnosis_genetic_analysis_file_filters"},
                        new String[]{"_treatment_filters", "treatment_filters"},
                        new String[]{"_survival_filters", "survival_filters"}
                },
                GS_CATEGORY_TYPE, "subject"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, STUDIES_END_POINT,
                GS_COUNT_ENDPOINT, STUDIES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "study_count",
                GS_RESULT_FIELD, "studies",
                // study_status_gs is populated from study_phase in indices.yaml
                GS_SEARCH_FIELD, List.of(
                        "study_id_gs", "study_name_gs", "study_status_gs",
                        "study_id", "study_name", "study_phase", "study_acronym",
                        "study_description", "dbgap_accession"
                ),
                GS_SORT_FIELD, "study_id",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"study_id", "study_id"},
                        new String[]{"study_name", "study_name"},
                        new String[]{"study_phase", "study_phase"},
                        new String[]{"num_of_participants", "num_of_participants"},
                        new String[]{"num_of_samples", "num_of_samples"},
                        new String[]{"num_of_files", "num_of_files"},
                        new String[]{"consent_codes", "consent_codes"},
                },
                GS_CATEGORY_TYPE, "study"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, SAMPLES_END_POINT,
                GS_COUNT_ENDPOINT, SAMPLES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "sample_count",
                GS_RESULT_FIELD, "samples",
                // Include *_gs diagnosis fields so sample clinical text is searchable.
                GS_SEARCH_FIELD, List.of(
                        "sample_id_gs", "participant_id_gs", "study_id_gs",
                        "sample_anatomic_site_str_gs", "sample_tumor_status_gs",
                        "diagnosis_str_gs", "diagnosis_category_str_gs", "tumor_classification_gs",
                        "sample_id", "participant_id", "study_id", "sample_anatomic_site_str",
                        "sample_tumor_status", "tumor_spatial_extent", "study_name", "sample_description"
                ),
                GS_SORT_FIELD, "sample_id",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"sample_id", "sample_id"},
                        new String[]{"participant_id", "participant_id"},
                        new String[]{"study_id", "study_id"},
                        new String[]{"sample_anatomic_site_str", "sample_anatomic_site_str"},
                        new String[]{"sample_tumor_status", "sample_tumor_status"},
                        new String[]{"diagnosis_str", "diagnosis_str"},
                        new String[]{"diagnosis_category_str", "diagnosis_category_str"},
                        new String[]{"tumor_spatial_extent", "tumor_spatial_extent"},
                        new String[]{"_diagnosis_filters", "diagnosis_filters"}
                },
                GS_CATEGORY_TYPE, "sample"
        ));
        searchCategories.add(Map.of(
                GS_END_POINT, FILES_END_POINT,
                GS_COUNT_ENDPOINT, FILES_COUNT_END_POINT,
                GS_COUNT_RESULT_FIELD, "file_count",
                GS_RESULT_FIELD, "files",
                GS_SEARCH_FIELD, List.of(
                        "participant_id_gs", "sample_id_gs", "study_id_gs",
                        "file_description_gs", "file_type_gs", "file_name_gs", "data_category_gs",
                        "participant_id", "sample_id", "study_id", "file_description",
                        "file_type", "file_name", "data_category"
                ),
                GS_SORT_FIELD, "file_id",
                GS_COLLECT_FIELDS, new String[][]{
                        new String[]{"id", "id"},
                        new String[]{"participant_id", "participant_id"},
                        new String[]{"sample_id", "sample_id"},
                        new String[]{"study_id", "study_id"},
                        new String[]{"file_name", "file_name"},
                        new String[]{"data_category", "data_category"},
                        new String[]{"file_description", "file_description"},
                        new String[]{"file_type","file_type"},
                        new String[]{"file_size","file_size"}
                },
                GS_CATEGORY_TYPE, "file"
        ));
        // model_nodes / model_properties / model_values are Bento template indexes and are not
        // present in the CCDI OpenSearch cluster. Keep GraphQL contract with empty model results.
        result.put("model", new ArrayList<>());
        result.put("model_count", 0);

        Set<String> combinedCategories = Set.of("model") ;

        for (Map<String, Object> category: searchCategories) {
            String countResultFieldName = (String) category.get(GS_COUNT_RESULT_FIELD);
            String resultFieldName = (String) category.get(GS_RESULT_FIELD);
            String[][] properties = (String[][]) category.get(GS_COLLECT_FIELDS);
            Map<String, Object> query = getGlobalSearchQuery(input, category);

            // Get count
            Request countRequest = new Request("GET", (String) category.get(GS_COUNT_ENDPOINT));
            countRequest.setJsonEntity(gson.toJson(query));
            JsonObject countResult = esService.send(countRequest);
            int oldCount = (int)result.getOrDefault(countResultFieldName, 0);
            result.put(countResultFieldName, countResult.get("count").getAsInt() + oldCount);

            // Get results
            Request request = new Request("GET", (String)category.get(GS_END_POINT));
            String sortFieldName = (String)category.get(GS_SORT_FIELD);
            query.put("sort", Map.of(sortFieldName, "asc"));
            query = addHighlight(query, category);

            if (combinedCategories.contains(resultFieldName)) {
                size = 10000;
                offset = 0;
            }

            List<String> dataFields = new ArrayList<>();
            for (String[] prop: properties) {
                String dataField = prop[1];
                dataFields.add(dataField);
            }
            query.put("_source", Map.of("includes", dataFields));

            request.setJsonEntity(gson.toJson(query));
            List<Map<String, Object>> objects = inventoryESService.collectPage(request, query, properties, size, offset);

            for (var object: objects) {
                object.put(GS_CATEGORY_TYPE, category.get(GS_CATEGORY_TYPE));
            }

            if (resultFieldName.equals("samples") && objects != null && !objects.isEmpty()) {
                enrichGlobalSearchSamplesFromNestedFilters(objects);
            }

            // Add CPI data enrichment for participants
            if (resultFieldName.equals("participants") && objects != null && !objects.isEmpty()) {
                enrichGlobalSearchParticipantsFromNestedFilters(objects);
                enrichGlobalSearchParticipantsWithConsentCodes(objects);
                // Check if CPIFetcherService is properly injected
                if (cpiFetcherService != null) {
                    try {
                        // Extract IDs from the participant objects for CPI fetching
                        List<ParticipantRequest> extracted_ids = extractIDs(objects);
                        
                        // Fetch CPI data
                        List<FormattedCPIResponse> cpi_data = cpiFetcherService.fetchAssociatedParticipantIds(extracted_ids);
                        logger.info("GlobalSearch CPI data received: " + cpi_data.size() + " records");
                        
                        if (cpi_data != null && !cpi_data.isEmpty()) {
                            // Enrich CPI data with additional participant information
                            enrichCPIDataWithParticipantInfo(cpi_data);
                            
                            // Update the participant objects with enriched CPI data
                            updateParticipantListWithEnrichedCPIData(objects, cpi_data);
                            
                            logger.info("GlobalSearch participants enriched with CPI data");
                        }
                    } catch (Exception e) {
                        logger.error("Error enriching GlobalSearch participants with CPI data", e);
                        // Continue processing even if CPI enrichment fails
                    }
                } else {
                    logger.warn("CPIFetcherService is not properly injected. CPI integration will be skipped for GlobalSearch.");
                }
            }

            if (resultFieldName.equals("studies") && objects != null && !objects.isEmpty()) {
                normalizeGlobalSearchConsentCodes(objects);
            }

            List<Map<String, Object>> existingObjects = (List<Map<String, Object>>)result.getOrDefault(resultFieldName, null);
            if (existingObjects != null) {
                existingObjects.addAll(objects);
                result.put(resultFieldName, existingObjects);
            } else {
                result.put(resultFieldName, objects);
            }

        }

        List<Map<String, Object>> about_results = searchAboutPage(input);
        int about_count = about_results.size();
        result.put("about_count", about_count);
        result.put("about_page", paginate(about_results, size, offset));
        int old_size = (int) params.get("first");
        int old_offset = (int) params.get("offset");
        for (String category: combinedCategories) {
            List<Object> pagedCategory = paginate((List)result.get(category), old_size, old_offset);
            result.put(category, pagedCategory);
        }

        return result;
    }

    /**
     * Integrated participants_table stores diagnosis/treatment/survival values in nested filter
     * arrays rather than root-level *_str fields used by WebService. Aggregate unique nested
     * values into semicolon-delimited display strings for global search cards.
     */
    @SuppressWarnings("unchecked")
    private void enrichGlobalSearchParticipantsFromNestedFilters(List<Map<String, Object>> participants) {
        if (participants == null || participants.isEmpty()) {
            return;
        }
        for (Map<String, Object> participant : participants) {
            List<Map<String, Object>> diagnosisFilters =
                    asListOfMaps(participant.remove("_sample_diagnosis_filters"));
            List<Map<String, Object>> treatmentFilters =
                    asListOfMaps(participant.remove("_treatment_filters"));
            List<Map<String, Object>> survivalFilters =
                    asListOfMaps(participant.remove("_survival_filters"));

            putIfBlank(participant, "diagnosis_str",
                    aggregateNestedFilterValues(diagnosisFilters, "diagnosis"));
            putIfBlank(participant, "diagnosis_category_str",
                    aggregateNestedFilterValues(diagnosisFilters, "diagnosis_category"));
            putIfBlank(participant, "age_at_diagnosis_str",
                    aggregateNestedFilterValues(diagnosisFilters, "age_at_diagnosis"));
            putIfBlank(participant, "treatment_type_str",
                    aggregateNestedFilterValues(treatmentFilters, "treatment_type"));
            putIfBlank(participant, "treatment_agent_str",
                    aggregateNestedFilterValues(treatmentFilters, "treatment_agent"));
            putIfBlank(participant, "last_known_survival_status_str",
                    aggregateNestedFilterValues(survivalFilters, "last_known_survival_status"));
        }
    }

    /**
     * samples_table stores diagnosis values under diagnosis_filters rather than root *_str fields.
     */
    @SuppressWarnings("unchecked")
    private void enrichGlobalSearchSamplesFromNestedFilters(List<Map<String, Object>> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        for (Map<String, Object> sample : samples) {
            List<Map<String, Object>> diagnosisFilters =
                    asListOfMaps(sample.remove("_diagnosis_filters"));
            putIfBlank(sample, "diagnosis_str",
                    aggregateNestedFilterValues(diagnosisFilters, "diagnosis"));
            putIfBlank(sample, "diagnosis_category_str",
                    aggregateNestedFilterValues(diagnosisFilters, "diagnosis_category"));
        }
    }

    /**
     * participants_table does not store consent_codes (unlike WebService). Look up codes from
     * studies_table by study_id and return them as a GraphQL String.
     */
    private void enrichGlobalSearchParticipantsWithConsentCodes(List<Map<String, Object>> participants) {
        if (participants == null || participants.isEmpty()) {
            return;
        }
        LinkedHashSet<String> studyIds = new LinkedHashSet<>();
        for (Map<String, Object> participant : participants) {
            Object studyId = participant.get("study_id");
            if (studyId != null && !studyId.toString().trim().isEmpty()) {
                studyIds.add(studyId.toString().trim());
            }
        }
        Map<String, String> consentByStudyId = lookupConsentCodesByStudyIds(studyIds);
        for (Map<String, Object> participant : participants) {
            Object existing = participant.get("consent_codes");
            String formattedExisting = formatConsentCodesValue(existing);
            if (formattedExisting != null) {
                participant.put("consent_codes", formattedExisting);
                continue;
            }
            Object studyId = participant.get("study_id");
            if (studyId == null) {
                participant.put("consent_codes", null);
                continue;
            }
            participant.put("consent_codes", consentByStudyId.get(studyId.toString().trim()));
        }
    }

    private void normalizeGlobalSearchConsentCodes(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (Map<String, Object> record : records) {
            record.put("consent_codes", formatConsentCodesValue(record.get("consent_codes")));
        }
    }

    private Map<String, String> lookupConsentCodesByStudyIds(Set<String> studyIds) {
        Map<String, String> consentByStudyId = new HashMap<>();
        if (studyIds == null || studyIds.isEmpty()) {
            return consentByStudyId;
        }
        try {
            Map<String, Object> query = new HashMap<>();
            query.put("size", studyIds.size());
            query.put("query", Map.of("terms", Map.of("study_id", new ArrayList<>(studyIds))));
            query.put("_source", Map.of("includes", List.of("study_id", "consent_codes")));
            Request request = new Request("GET", STUDIES_END_POINT);
            request.setJsonEntity(gson.toJson(query));
            JsonObject jsonObject = inventoryESService.send(request);
            JsonArray hits = jsonObject.getAsJsonObject("hits").getAsJsonArray("hits");
            for (JsonElement hit : hits) {
                JsonObject source = hit.getAsJsonObject().getAsJsonObject("_source");
                if (source == null || !source.has("study_id") || source.get("study_id").isJsonNull()) {
                    continue;
                }
                String studyId = source.get("study_id").getAsString();
                Object consentCodes = source.has("consent_codes")
                        ? parseJsonElement(source.get("consent_codes"))
                        : null;
                consentByStudyId.put(studyId, formatConsentCodesValue(consentCodes));
            }
        } catch (Exception e) {
            logger.warn("Unable to look up consent_codes from studies_table for global search: " + e.getMessage());
        }
        return consentByStudyId;
    }

    private Object parseJsonElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                values.add(parseJsonElement(entry));
            }
            return values;
        }
        if (element.isJsonObject()) {
            return element.toString();
        }
        return element.getAsString();
    }

    private String formatConsentCodesValue(Object value) {
        if (value == null) {
            return null;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addNestedDisplayValues(values, value);
        return values.isEmpty() ? null : String.join("; ", values);
    }

    private void putIfBlank(Map<String, Object> target, String key, String value) {
        Object existing = target.get(key);
        if (existing != null && !existing.toString().trim().isEmpty()
                && !"null".equalsIgnoreCase(existing.toString().trim())) {
            return;
        }
        target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?>)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map<?, ?>) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    private String aggregateNestedFilterValues(List<Map<String, Object>> filters, String fieldName) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Map<String, Object> filter : filters) {
            if (filter == null) {
                continue;
            }
            addNestedDisplayValues(values, filter.get(fieldName));
        }
        return values.isEmpty() ? null : String.join("; ", values);
    }

    private void addNestedDisplayValues(LinkedHashSet<String> values, Object fieldValue) {
        if (fieldValue == null) {
            return;
        }
        if (fieldValue instanceof List<?>) {
            for (Object item : (List<?>) fieldValue) {
                addNestedDisplayValues(values, item);
            }
            return;
        }
        String formatted = fieldValue.toString().trim();
        if (formatted.isEmpty() || "null".equalsIgnoreCase(formatted)) {
            return;
        }
        // Unknown coded ages / sentinel values used across CCDI indexes
        if ("-999".equals(formatted) || "-1".equals(formatted)) {
            return;
        }
        values.add(formatted);
    }

    private List<Map<String, Object>> subjectCountBy(String category, Map<String, Object> params, String endpoint, String cardinalityAggName, String indexType) throws IOException {
        return subjectCountBy(category, params, endpoint, Map.of(), cardinalityAggName, indexType);
    }

    private List<Map<String, Object>> subjectCountBy(String category, Map<String, Object> params, String endpoint, Map<String, Object> additionalParams, String cardinalityAggName, String indexType) throws IOException {
        List<String> only_includes;
        List<String> valueSet = INCLUDE_PARAMS.contains(category) ? (List<String>)params.get(category) : List.of();
        if (valueSet.size() > 0 && !(valueSet.size() == 1 && valueSet.get(0).equals(""))){
            only_includes = valueSet;
        } else {
            only_includes = List.of();
        }

        // Exact unique participant counts via nested + reverse_nested on participants_table.
        if (cardinalityAggName != null && !RANGE_PARAMS.contains(category)) {
            return exactParticipantTermCounts(category, params, Set.of(PAGE_SIZE), indexType, only_includes);
        }

        Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE), Set.of(), "nested_filters", indexType);
        return getGroupCount(category, query, endpoint, null, only_includes);
    }

    private List<Map<String, Object>> subjectCountByRange(String category, Map<String, Object> params, String endpoint, String cardinalityAggName, String indexType) throws IOException {
        return subjectCountByRange(category, params, endpoint, Map.of(), cardinalityAggName, indexType);
    }

    private List<Map<String, Object>> subjectCountByRange(String category, Map<String, Object> params, String endpoint, Map<String, Object> additionalParams, String cardinalityAggName, String indexType) throws IOException {
        // Exact unique participant range-bin counts via nested + reverse_nested on participants_table.
        if (cardinalityAggName != null) {
            String nestedProperty = getParticipantsFacetNestedPath(indexType);
            Map<String, Object> queryParticipants = inventoryESService.buildFacetFilterQuery(
                    params, RANGE_PARAMS, Set.of(PAGE_SIZE), Set.of(), "nested_filters", "participants_table");
            queryParticipants = inventoryESService.addCustomRangeAggregations(queryParticipants, "facetAgg", category, nestedProperty);
            Request request = new Request("GET", PARTICIPANTS_END_POINT);
            request.setJsonEntity(gson.toJson(queryParticipants));
            JsonObject jsonObject = inventoryESService.send(request);
            return collectCustomRangeBuckets(jsonObject, "facetAgg");
        }

        Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE), Set.of(), "nested_filters", indexType);
        return getGroupCountByRange(category, query, endpoint, null);
    }

    private List<Map<String, Object>> filterSubjectCountBy(String category, Map<String, Object> params, String endpoint, String cardinalityAggName, String indexType) throws IOException {
        return filterSubjectCountBy(category, params, endpoint, Map.of(), cardinalityAggName, indexType);
    }

    private List<Map<String, Object>> filterSubjectCountBy(String category, Map<String, Object> params, String endpoint, Map<String, Object> additionalParams, String cardinalityAggName, String indexType) throws IOException {
        // Exact unique participant facet counts via nested + reverse_nested (no cardinality).
        if (cardinalityAggName != null && !RANGE_PARAMS.contains(category)) {
            return exactParticipantTermCounts(category, params, Set.of(PAGE_SIZE, category), indexType, List.of());
        }

        // File counts / root participant fields: plain terms doc_count is already exact.
        Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE, category), Set.of(), "nested_filters", indexType);
        return getGroupCount(category, query, endpoint, null, List.of());
    }

    private List<Map<String, Object>> exactParticipantTermCounts(
            String category,
            Map<String, Object> params,
            Set<String> excludedParams,
            String indexType,
            List<String> only_includes) throws IOException {
        if ("studies_for_cohorts".equals(indexType)) {
            return exactStudyPropertyParticipantCounts(category, params);
        }

        // Genetic facets: gene keys come from genetic_analyses_table (keyword arrays → individual
        // genes). Participant counts come from participants_table reverse_nested so they match
        // searchParticipants totals (not unique pids on genetic_analyses_table, which over-count).
        if ("genetic_analyses_table".equals(indexType)) {
            return exactGeneticFacetParticipantCounts(category, params, excludedParams, only_includes);
        }

        boolean useCohortsIndex = "cohorts".equals(indexType);
        String nestedProperty = useCohortsIndex
                ? cohortChartProperties.get(category).getOrDefault("nestedPath", "")
                : getParticipantsFacetNestedPath(indexType);
        String participantIndex = useCohortsIndex ? "cohorts" : "participants_table";
        String participantEndpoint = useCohortsIndex ? COHORTS_END_POINT : PARTICIPANTS_END_POINT;
        Map<String, Object> queryParticipants = inventoryESService.buildFacetFilterQuery(
                params, RANGE_PARAMS, excludedParams, Set.of(), "nested_filters", participantIndex);
        queryParticipants = inventoryESService.addCustomAggregations(
                queryParticipants, "facetAgg", category, nestedProperty, only_includes);
        Request request = new Request("GET", participantEndpoint);
        request.setJsonEntity(gson.toJson(queryParticipants));
        JsonObject jsonObject = inventoryESService.send(request);
        return collectCustomTermBuckets(jsonObject, "facetAgg");
    }

    /**
     * Study metadata is stored once per study, while cohort membership is expressed as participant
     * GUIDs. Count the selected participants per study in cohorts, then apply those exact weights to
     * each value read from studies_for_cohorts.
     */
    private List<Map<String, Object>> exactStudyPropertyParticipantCounts(
            String category,
            Map<String, Object> params) throws IOException {
        Map<String, Object> participantQuery = inventoryESService.buildFacetFilterQuery(
                params, RANGE_PARAMS, Set.of(PAGE_SIZE, category), Set.of(), "nested_filters", "cohorts");
        participantQuery = inventoryESService.addCustomAggregations(
                participantQuery, "facetAgg", "study_guid", "", List.of());
        Request participantRequest = new Request("GET", COHORTS_END_POINT);
        participantRequest.setJsonEntity(gson.toJson(participantQuery));

        Map<String, Integer> participantsByStudy = new HashMap<>();
        for (Map<String, Object> bucket : collectCustomTermBuckets(
                inventoryESService.send(participantRequest), "facetAgg")) {
            participantsByStudy.put((String) bucket.get("group"), (Integer) bucket.get("subjects"));
        }
        if (participantsByStudy.isEmpty()) {
            return List.of();
        }

        String nestedPath = cohortChartProperties.get(category).getOrDefault("nestedPath", "");
        String sourceField = nestedPath.isEmpty() ? category : nestedPath + "." + category;
        Map<String, Object> studyQuery = new HashMap<>();
        studyQuery.put("size", participantsByStudy.size());
        studyQuery.put("query", Map.of("terms", Map.of("guid", participantsByStudy.keySet())));
        studyQuery.put("_source", List.of("guid", sourceField));
        Request studyRequest = new Request("GET", STUDIES_FOR_COHORTS_END_POINT);
        studyRequest.setJsonEntity(gson.toJson(studyQuery));
        JsonArray hits = inventoryESService.send(studyRequest)
                .getAsJsonObject("hits")
                .getAsJsonArray("hits");

        Map<String, Integer> participantsByValue = new HashMap<>();
        for (JsonElement hitElement : hits) {
            JsonObject source = hitElement.getAsJsonObject().getAsJsonObject("_source");
            if (source == null || !source.has("guid")) {
                continue;
            }
            int participantCount = participantsByStudy.getOrDefault(source.get("guid").getAsString(), 0);
            Set<String> studyValues = new LinkedHashSet<>();
            collectJsonValuesAtPath(source, sourceField.split("\\."), 0, studyValues);
            for (String value : studyValues) {
                participantsByValue.merge(value, participantCount, Integer::sum);
            }
        }

        return new ArrayList<>(participantsByValue.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "group", entry.getKey(),
                        "subjects", entry.getValue()))
                .sorted((first, second) -> Integer.compare(
                        (Integer) second.get("subjects"),
                        (Integer) first.get("subjects")))
                .toList());
    }

    private void collectJsonValuesAtPath(
            JsonElement element,
            String[] path,
            int pathIndex,
            Set<String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectJsonValuesAtPath(item, path, pathIndex, values);
            }
            return;
        }
        if (pathIndex == path.length) {
            if (element.isJsonPrimitive()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            return;
        }
        if (!element.isJsonObject() || !element.getAsJsonObject().has(path[pathIndex])) {
            return;
        }
        collectJsonValuesAtPath(
                element.getAsJsonObject().get(path[pathIndex]),
                path,
                pathIndex + 1,
                values);
    }

    /**
     * Gene Symbol / genetic facets: individual gene labels from genetic_analyses_table,
     * exact participant counts from participants_table nested + reverse_nested.
     */
    private List<Map<String, Object>> exactGeneticFacetParticipantCounts(
            String category,
            Map<String, Object> params,
            Set<String> excludedParams,
            List<String> only_includes) throws IOException {
        // 1) Individual gene keys (genetic_analyses_table expands keyword arrays).
        Map<String, Object> geneKeyParams = new HashMap<>(params);
        Object participantIds = geneKeyParams.remove("id");
        if (participantIds != null) {
            geneKeyParams.put("pid", participantIds);
        }
        Map<String, Object> geneKeyQuery = inventoryESService.buildFacetFilterQuery(
                geneKeyParams, RANGE_PARAMS, excludedParams, Set.of(), "nested_filters", "genetic_analyses_table");
        geneKeyQuery = inventoryESService.addCustomAggregations(
                geneKeyQuery, "facetAgg", category, "", only_includes);
        Request geneKeyRequest = new Request("GET", GENETIC_ANALYSES_END_POINT);
        geneKeyRequest.setJsonEntity(gson.toJson(geneKeyQuery));
        JsonObject geneKeyResponse = inventoryESService.send(geneKeyRequest);
        Set<String> geneKeys = new LinkedHashSet<>();
        JsonObject geneKeyAggs = geneKeyResponse.getAsJsonObject("aggregations").getAsJsonObject("facetAgg");
        JsonArray geneKeyBuckets = geneKeyAggs.getAsJsonArray("buckets");
        for (JsonElement bucketEl : geneKeyBuckets) {
            JsonObject bucketObj = bucketEl.getAsJsonObject();
            if (!bucketObj.has("key") || bucketObj.get("key").isJsonNull()) {
                continue;
            }
            String key = bucketObj.get("key").getAsString();
            if (key == null || key.isEmpty() || isStringifiedListValue(key)) {
                continue;
            }
            geneKeys.add(key);
        }
        if (only_includes != null && !only_includes.isEmpty()) {
            geneKeys.retainAll(only_includes);
        }

        // 2) Exact participant counts aligned with searchParticipants (nested + reverse_nested).
        String nestedProperty = "sample_diagnosis_genetic_analysis_file_filters";
        Map<String, Object> participantQuery = inventoryESService.buildFacetFilterQuery(
                params, RANGE_PARAMS, excludedParams, Set.of(), "nested_filters", "participants_table");
        participantQuery = inventoryESService.addCustomAggregations(
                participantQuery, "facetAgg", category, nestedProperty, List.of());
        Request participantRequest = new Request("GET", PARTICIPANTS_END_POINT);
        participantRequest.setJsonEntity(gson.toJson(participantQuery));
        JsonObject participantResponse = inventoryESService.send(participantRequest);

        Map<String, Integer> participantCounts = new HashMap<>();
        for (Map<String, Object> bucket : collectCustomTermBuckets(participantResponse, "facetAgg")) {
            String key = (String) bucket.get("group");
            if (isStringifiedListValue(key)) {
                continue;
            }
            participantCounts.put(key, (Integer) bucket.get("subjects"));
        }

        // 3) Emit one row per individual gene with the participants_table count (0 if not present).
        List<Map<String, Object>> data = new ArrayList<>();
        for (String gene : geneKeys) {
            int subjects = participantCounts.getOrDefault(gene, 0);
            if (subjects > 0) {
                data.add(Map.of("group", gene, "subjects", subjects));
            }
        }
        // Sort by count desc so UI default ordering stays sensible.
        data.sort((a, b) -> Integer.compare((Integer) b.get("subjects"), (Integer) a.get("subjects")));
        return data;
    }

    private boolean isStringifiedListValue(String key) {
        if (key == null) {
            return false;
        }
        String trimmed = key.trim();
        return trimmed.startsWith("[") || trimmed.startsWith("{");
    }

    private String getParticipantsFacetNestedPath(String indexType) {
        // participants_table exact facets are stored under different nested paths depending on which "table" the facet belongs to.
        if (indexType.equals("survivals_table")) {
            return "survival_filters";
        } else if (indexType.equals("treatments_table")) {
            return "treatment_filters";
        } else if (indexType.equals("treatment_responses_table")) {
            return "treatment_response_filters";
        } else if (indexType.equals("samples_table")
                || indexType.equals("diagnoses_table")
                || indexType.equals("genetic_analyses_table")
                || indexType.equals("files_table")) {
            return "sample_diagnosis_genetic_analysis_file_filters";
        }
        return "";
    }

    private List<Map<String, Object>> collectCustomTermBuckets(JsonObject jsonObject, String aggName) {
        List<Map<String, Object>> data = new ArrayList<>();

        JsonObject aggs = jsonObject.getAsJsonObject("aggregations").getAsJsonObject(aggName);
        JsonArray buckets = aggs.getAsJsonObject("agg_buckets") != null ? aggs.getAsJsonObject("agg_buckets").getAsJsonArray("buckets") : aggs.getAsJsonArray("buckets");
        for (JsonElement bucketEl : buckets) {
            JsonObject bucketObj = bucketEl.getAsJsonObject();
            if (!bucketObj.has("key") || bucketObj.get("key").isJsonNull()) {
                continue;
            }
            String key = bucketObj.get("key").getAsString();
            if (key == null || key.isEmpty()) {
                continue;
            }
            int subjects;
            if (bucketObj.has("top_reverse_nested")) {
                subjects = bucketObj.getAsJsonObject("top_reverse_nested").get("doc_count").getAsInt();
            } else {
                subjects = bucketObj.get("doc_count").getAsInt();
            }
            data.add(Map.of("group", key, "subjects", subjects));
        }

        return data;
    }

    private List<Map<String, Object>> collectCustomRangeBuckets(JsonObject jsonObject, String aggName) {
        List<Map<String, Object>> data = new ArrayList<>();

        JsonObject aggs = jsonObject.getAsJsonObject("aggregations").getAsJsonObject(aggName);
        JsonArray buckets = aggs.getAsJsonObject("agg_buckets") != null ? aggs.getAsJsonObject("agg_buckets").getAsJsonArray("buckets") : aggs.getAsJsonArray("buckets");
        for (JsonElement bucketEl : buckets) {
            JsonObject bucketObj = bucketEl.getAsJsonObject();
            String key;
            if (bucketObj.has("key") && !bucketObj.get("key").isJsonNull()) {
                key = bucketObj.get("key").getAsString();
            } else if (bucketObj.has("key_as_string") && !bucketObj.get("key_as_string").isJsonNull()) {
                key = bucketObj.get("key_as_string").getAsString();
            } else {
                continue;
            }

            int subjects;
            if (bucketObj.has("top_reverse_nested")) {
                subjects = bucketObj.getAsJsonObject("top_reverse_nested").get("doc_count").getAsInt();
            } else if (bucketObj.has("doc_count")) {
                subjects = bucketObj.get("doc_count").getAsInt();
            } else {
                subjects = 0;
            }
            data.add(Map.of("group", key, "subjects", subjects));
        }

        return data;
    }

    private JsonArray getNodeCount(String category, Map<String, Object> query, String endpoint) throws IOException {
        query = inventoryESService.addNodeCountAggregations(query, category);
        Request request = new Request("GET", endpoint);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = inventoryESService.send(request);
        Map<String, JsonArray> aggs = inventoryESService.collectNodeCountAggs(jsonObject, category);
        JsonArray buckets = aggs.get(category);

        return buckets;
    }

    private List<Map<String, Object>> getGroupCountByRange(String category, Map<String, Object> query, String endpoint, String cardinalityAggName) throws IOException {
        query = inventoryESService.addRangeCountAggregations(query, category, cardinalityAggName);
        Request request = new Request("GET", endpoint);
        // System.out.println(gson.toJson(query));
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = inventoryESService.send(request);
        Map<String, JsonArray> aggs = inventoryESService.collectRangCountAggs(jsonObject, category);
        JsonArray buckets = aggs.get(category);

        return getGroupCountHelper(buckets, cardinalityAggName);
    }

    private List<Map<String, Object>> getGroupCount(String category, Map<String, Object> query, String endpoint, String cardinalityAggName, List<String> only_includes) throws IOException {
        if (RANGE_PARAMS.contains(category)) {
            query = inventoryESService.addRangeAggregations(query, category, only_includes);
            Request request = new Request("GET", endpoint);
            // System.out.println(gson.toJson(query));
            request.setJsonEntity(gson.toJson(query));
            JsonObject jsonObject = inventoryESService.send(request);
            Map<String, JsonObject> aggs = inventoryESService.collectRangAggs(jsonObject, category);
            JsonObject ranges = aggs.get(category);

            return getRangeGroupCountHelper(ranges);
        } else {
            String[] AGG_NAMES = new String[] {category};
            query = inventoryESService.addAggregations(query, AGG_NAMES, cardinalityAggName, only_includes);
            Request request = new Request("GET", endpoint);
            request.setJsonEntity(gson.toJson(query));
            JsonObject jsonObject = inventoryESService.send(request);
            Map<String, JsonArray> aggs = inventoryESService.collectTermAggs(jsonObject, AGG_NAMES);
            JsonArray buckets = aggs.get(category);

            return getGroupCountHelper(buckets, cardinalityAggName);
        }
        
    }

    private List<Map<String, Object>> getRangeGroupCountHelper(JsonObject ranges) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        if (ranges.get("count").getAsInt() == 0) {
            data.add(Map.of("lowerBound", 0,
                    "subjects", 0,
                    "upperBound", 0
            ));
        } else {
            data.add(Map.of("lowerBound", ranges.get("min").getAsInt(),
                    "subjects", ranges.get("count").getAsInt(),
                    "upperBound", ranges.get("max").getAsInt()
            ));
        }
        return data;
    }

    private List<Map<String, Object>> getBooleanGroupCountHelper(JsonObject filters) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, JsonElement> group: filters.entrySet()) {
            int count = group.getValue().getAsJsonObject().get("parent").getAsJsonObject().get("doc_count").getAsInt();
            if (count > 0) {
                data.add(Map.of("group", group.getKey(),
                    "subjects", count
                ));
            }
        }
        return data;
    }

    private List<Map<String, Object>> getGroupCountHelper(JsonArray buckets, String cardinalityAggName) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        for (JsonElement group: buckets) {
            JsonObject g = group.getAsJsonObject();
            if (g.get("key").isJsonNull() || g.get("key").getAsString().equals("")) {
                continue;
            }
            String groupKey = g.get("key").getAsString();
            int subjects;
            if (cardinalityAggName != null && g.has("exact_count") && !g.get("exact_count").isJsonNull()) {
                JsonObject exact = g.getAsJsonObject("exact_count");
                if (exact.has("value") && !exact.get("value").isJsonNull()) {
                    // scripted_metric exact distinct count
                    subjects = exact.get("value").getAsInt();
                } else if (exact.has("buckets")) {
                    // legacy terms-subagg shape (avoided due to max_buckets)
                    subjects = exact.getAsJsonArray("buckets").size();
                } else {
                    subjects = g.has("doc_count") ? g.get("doc_count").getAsInt() : 0;
                }
            } else if (cardinalityAggName != null && g.has("cardinality_count") && !g.get("cardinality_count").isJsonNull()) {
                // Legacy fallback if an older aggregation shape is still returned.
                int cardinality = g.getAsJsonObject("cardinality_count").get("value").getAsInt();
                int docCount = g.has("doc_count") ? g.get("doc_count").getAsInt() : 0;
                subjects = cardinality > 0 || docCount == 0 ? cardinality : docCount;
            } else {
                subjects = g.get("doc_count").getAsInt();
            }
            data.add(Map.of("group", groupKey, "subjects", subjects));
        }
        return data;
    }

    private List<Map<String, Object>> parseStudyProfileCounts(Object rawValue) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (rawValue == null) {
            return data;
        }

        List<?> values;
        if (rawValue instanceof List) {
            values = (List<?>) rawValue;
        } else if (rawValue instanceof String) {
            String rawString = ((String) rawValue).trim();
            if (rawString.isEmpty()) {
                return data;
            }
            values = List.of(rawString.split(";"));
        } else {
            return data;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^(.+?) \\((\\d+)\\)$");
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String entry = value.toString().trim();
            if (entry.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher matcher = pattern.matcher(entry);
            if (matcher.matches()) {
                data.add(Map.of(
                        "group", matcher.group(1).trim(),
                        "subjects", Integer.parseInt(matcher.group(2))));
            } else {
                data.add(Map.of("group", entry, "subjects", 0));
            }
        }
        return data;
    }

    private Map<String, List<Object>> idsLists(Map<String, Object> params) throws IOException {
        String cacheKey = "idsLists".concat(generateCacheKey(params));
        Object cachedResultsRaw = null;
        boolean useCache = (boolean) params.get("use_cache");

        List<Object> allAssociatedIds = new ArrayList<>();
        List<Object> allParticipantIds = new ArrayList<>();
        List<Map<String, Object>> allParticipants;
        ExecutorService executorService;
        List<Future<List<Map<String, Object>>>> cpiFutures = new ArrayList<>();
        int maxParticipantsPerCPIRequest = (int) params.get("cpi_batch_size");
        int numCpiRequests = 0;
        int participantCount = 0;
        Map<String, List<Object>> results = null;

        final String[][] participantProperties = new String[][]{
            new String[]{"id", "id"},
            new String[]{"study_id", "study_id"},
            new String[]{"participant_id", "participant_id"}
        };
        Map<String, Object> participantParams = new HashMap<>(Map.of(
            OFFSET, 0,
            ORDER_BY, "participant_id",
            SORT_DIRECTION, "asc"
        ));

        if (useCache) {
            cachedResultsRaw = caffeineCache.asMap().get(cacheKey);

            if (cachedResultsRaw instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, List<Object>> castedCachedResults = (Map<String, List<Object>>) cachedResultsRaw;
                results = castedCachedResults;
            }
        }

        if (results != null) {
            logger.info("hit cache!");
            return results;
        }

        participantParams.put(PAGE_SIZE, ESService.MAX_ES_SIZE);

        Map<String, String> idsMapping = Map.of(
            "participant_id", "participant_id",
            "study_id", "study_id",
            "id", "id"
        );

        allParticipants = overview(
            PARTICIPANTS_END_POINT,
            participantParams,
            participantProperties,
            "participant_id",
            idsMapping,
            Set.of(),
            "nested_filters",
            "participants_table"
        );

        participantCount = allParticipants.size();

        if (participantCount == 0) {
            logger.error("No participants found!");
            return Map.of("participantIds", List.of(), "associatedIds", List.of());
        }

        numCpiRequests = (int) Math.ceil((double) participantCount / maxParticipantsPerCPIRequest);

        executorService = Executors.newFixedThreadPool(Math.min(numCpiRequests, THREAD_POOL_SIZE));

        try {
            for (int i = 0; i < numCpiRequests; i++) {
                int fromIndex = i * maxParticipantsPerCPIRequest;
                int toIndex = Math.min((i + 1) * maxParticipantsPerCPIRequest, participantCount);
                List<Map<String, Object>> participants = allParticipants.subList(fromIndex, toIndex);

                Future<List<Map<String, Object>>> future = executorService.submit(() -> {
                    insertCPIDataIntoParticipants(participants);
                    return participants;
                });

                cpiFutures.add(future);
            }

            for (Future<List<Map<String, Object>>> future : cpiFutures) {
                List<Object> associatedIds = new ArrayList<>();
                List<Object> participantIds = new ArrayList<>();
                List<Map<String, Object>> participants;

                try {
                    participants = future.get();
                } catch (Exception e) {
                    logger.error("Error processing batch in async CPI requests", e);
                    continue;
                }

                for (Map<String, Object> participant : participants) {
                    List<Map<String, Object>> cpiEntries;
                    Object cpiEntriesRaw = participant.get("cpi_data");

                    participantIds.add(participant.get("participant_id"));

                    if (cpiEntriesRaw instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> castedCpiEntries = (List<Map<String, Object>>) cpiEntriesRaw;
                        cpiEntries = castedCpiEntries;
                    } else {
                        continue;
                    }

                    for (Map<String, Object> cpiEntry : cpiEntries) {
                        associatedIds.add(Map.of(
                            "associated_id", cpiEntry.get("associated_id"),
                            "participant_id", participant.get("participant_id")
                        ));
                    }
                }
                allParticipantIds.addAll(participantIds);
                allAssociatedIds.addAll(associatedIds);
            }
        } catch (Exception e) {
            logger.error("Error processing batches in async CPI requests", e);
        } finally {
            executorService.shutdown();
        }

        results = new HashMap<>(Map.of(
            "participantIds", allParticipantIds,
            "associatedIds", allAssociatedIds
        ));

        caffeineCache.put(cacheKey, results);

        return results;
    }

    // for CCDI Hub home page "CCDI stats At a Glance"
    private Integer getParticipantsCount() throws IOException {

        Map<String, Object> params = new HashMap<>();
        List study_ids=new ArrayList();
        study_ids.add("phs002790");
        params.put("study_id",study_ids);


        Map<String, Object> query_participants = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "participants_table");

        Request participantsCountRequest = new Request("GET", PARTICIPANTS_END_POINT);

        participantsCountRequest.setJsonEntity(gson.toJson(query_participants));
        JsonObject participantsCountResult = inventoryESService.send(participantsCountRequest);
        int numberOfParticipants = participantsCountResult.getAsJsonObject("hits").getAsJsonObject("total").get("value").getAsInt();

        return numberOfParticipants;

    }

    private Map<String, Object> searchParticipants(Map<String, Object> params) throws IOException {
        List<String> importData = (List<String>) params.get("import_data");
        String cacheKey = "no_cache";
        Map<String, Object> data = null;
        if (importData == null || importData.size() == 0 || importData.get(0).equals("")) {
            cacheKey = generateCacheKey(params);
            data = (Map<String, Object>)caffeineCache.asMap().get(cacheKey);
        }
        
        if (data != null) {
            logger.info("hit cache!");
            return data;
        }
        // logger.info("cache miss... querying for data.");
        data = new HashMap<>();
        Map<String, Object> query_participants = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "participants_table");
        // System.out.println(gson.toJson(query_participants));
        Map<String, Object> newQuery_participants = new HashMap<>(query_participants);
        newQuery_participants.put("size", 0);
        newQuery_participants.put("track_total_hits", 10000000);
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("file_count", Map.of("sum", Map.of("field", "file_count")));
        newQuery_participants.put("aggs", fields);
        Request participantsCountRequest = new Request("GET", PARTICIPANTS_END_POINT);
        // System.out.println(gson.toJson(newQuery_participants));
        participantsCountRequest.setJsonEntity(gson.toJson(newQuery_participants));
        JsonObject participantsCountResult = inventoryESService.send(participantsCountRequest);
        int numberOfParticipants = participantsCountResult.getAsJsonObject("hits").getAsJsonObject("total").get("value").getAsInt();
        int participants_file_count = participantsCountResult.getAsJsonObject("aggregations").getAsJsonObject("file_count").get("value").getAsInt();
        Map<String, Object> query_diagnosis = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "diagnoses_table");
        // System.out.println(gson.toJson(query_diagnosis));
        Request diagnosisCountRequest = new Request("GET", DIAGNOSIS_COUNT_END_POINT);
        diagnosisCountRequest.setJsonEntity(gson.toJson(query_diagnosis));
        JsonObject diagnosisCountResult = inventoryESService.send(diagnosisCountRequest);
        int numberOfDiagnosis = diagnosisCountResult.get("count").getAsInt();
        Map<String, Object> query_genetic_analyses = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "genetic_analyses_table");
        Request geneticAnalysesCountRequest = new Request("GET", GENETIC_ANALYSES_COUNT_END_POINT);
        geneticAnalysesCountRequest.setJsonEntity(gson.toJson(query_genetic_analyses));
        JsonObject geneticAnalysesCountResult = inventoryESService.send(geneticAnalysesCountRequest);
        int numberOfGeneticAnalyses = geneticAnalysesCountResult.get("count").getAsInt();
        Map<String, Object> query_treatments = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "treatments_table");
        Request treatmentsCountRequest = new Request("GET", TREATMENTS_COUNT_END_POINT);
        treatmentsCountRequest.setJsonEntity(gson.toJson(query_treatments));
        JsonObject treatmentsCountResult = inventoryESService.send(treatmentsCountRequest);
        int numberOfTreatments = treatmentsCountResult.get("count").getAsInt();
        Map<String, Object> query_treatment_responses = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "treatment_responses_table");
        Request treatmentResponsesCountRequest = new Request("GET", TREATMENT_RESPONSES_COUNT_END_POINT);
        treatmentResponsesCountRequest.setJsonEntity(gson.toJson(query_treatment_responses));
        JsonObject treatmentResponsesCountResult = inventoryESService.send(treatmentResponsesCountRequest);
        int numberOfTreatmentResponses = treatmentResponsesCountResult.get("count").getAsInt();
        Map<String, Object> query_survivals = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "survivals_table");
        Request survivalsCountRequest = new Request("GET", SURVIVALS_COUNT_END_POINT);
        survivalsCountRequest.setJsonEntity(gson.toJson(query_survivals));
        JsonObject survivalsCountResult = inventoryESService.send(survivalsCountRequest);
        int numberOfSurvivals = survivalsCountResult.get("count").getAsInt();
        Map<String, Object> query_samples = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "samples_table");
        Request samplesCountRequest = new Request("GET", SAMPLES_COUNT_END_POINT);
        samplesCountRequest.setJsonEntity(gson.toJson(query_samples));
        JsonObject samplesCountResult = inventoryESService.send(samplesCountRequest);
        int numberOfSamples = samplesCountResult.get("count").getAsInt();
        
        Map<String, Object> query_studies = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "participants_table");
        int numberOfStudies = getNodeCount("study_id", query_studies, PARTICIPANTS_END_POINT).size();

        Request filesCountRequest = new Request("GET", FILES_COUNT_END_POINT);
        Map<String, Object> query_files = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "files_table");
        filesCountRequest.setJsonEntity(gson.toJson(query_files));
        JsonObject filesCountResult = inventoryESService.send(filesCountRequest);
        int numberOfFiles = filesCountResult.get("count").getAsInt();

        data.put("numberOfStudies", numberOfStudies);
        data.put("numberOfParticipants", numberOfParticipants);
        data.put("numberOfDiagnosis", numberOfDiagnosis);
        data.put("numberOfGeneticAnalyses", numberOfGeneticAnalyses);
        data.put("numberOfTreatments", numberOfTreatments);
        data.put("numberOfTreatmentResponses", numberOfTreatmentResponses);
        data.put("numberOfSurvivals", numberOfSurvivals);
        data.put("numberOfSamples", numberOfSamples);
        data.put("numberOfFiles", numberOfFiles);
        data.put("participantsFileCount", participants_file_count);

        // Facet filter/widget counts: run in parallel. Unique-participant facets use nested +
        // reverse_nested (exact, no cardinality); file/root facets use plain terms doc_count.
        List<Map<String, Object>> facetJobs = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : facetFilters.entrySet()) {
            String index = entry.getKey();
            String endpoint = ENDPOINTS.get(index);
            for (Map<String, Object> filter : entry.getValue()) {
                Map<String, Object> job = new HashMap<>(filter);
                job.put("_index", index);
                job.put("_endpoint", endpoint);
                facetJobs.add(job);
            }
        }

        ExecutorService facetExecutor = Executors.newFixedThreadPool(Math.min(THREAD_POOL_SIZE, Math.max(1, facetJobs.size())));
        try {
            List<Future<Map<String, Object>>> facetFutures = new ArrayList<>();
            for (Map<String, Object> job : facetJobs) {
                facetFutures.add(facetExecutor.submit(() -> computeFacetCounts(job, params)));
            }
            for (Future<Map<String, Object>> future : facetFutures) {
                Map<String, Object> facetResult = future.get();
                data.putAll(facetResult);
            }
        } catch (Exception e) {
            throw new IOException("Failed while computing facet counts in parallel", e);
        } finally {
            facetExecutor.shutdown();
        }

        if (!cacheKey.equals("no_cache")) {
            caffeineCache.put(cacheKey, data);
        }
        return data;
    }

    /**
     * Compute filter (+ optional widget) counts for one facet definition.
     * Participant-unique facets (cardinality_agg_name set) use reverse_nested on participants_table.
     */
    private Map<String, Object> computeFacetCounts(Map<String, Object> filter, Map<String, Object> params) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String index = (String) filter.get("_index");
        String endpoint = (String) filter.get("_endpoint");
        String cardinalityAggName = (String) filter.get(CARDINALITY_AGG_NAME);
        String cardinalityIndexName = filter.containsKey(CARDINALITY_INDEX_NAME) ? (String) filter.get(CARDINALITY_INDEX_NAME) : null;
        String field = (String) filter.get(AGG_NAME);
        String filterCountQueryName = (String) filter.get(FILTER_COUNT_QUERY);
        boolean isRangeParam = RANGE_PARAMS.contains(field);
        String widgetQueryName = (String) filter.get(WIDGET_QUERY);

        List<Map<String, Object>> filterCounts = filterSubjectCountBy(field, params, endpoint, cardinalityAggName, index);
        List<Map<String, Object>> widgetCounts = filterCounts;

        if (isRangeParam) {
            result.put(filterCountQueryName, filterCounts.get(0));
        } else {
            result.put(filterCountQueryName, filterCounts);
        }

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) params.get(field);

        if (widgetQueryName != null) {
            if (isRangeParam) {
                String queryIndex = cardinalityIndexName != null ? cardinalityIndexName : index;
                String queryEndpoint = ENDPOINTS.get(queryIndex);
                if (queryEndpoint == null) {
                    throw new IOException("No OpenSearch endpoint mapping found for index: " + queryIndex);
                }
                widgetCounts = subjectCountByRange(field, params, queryEndpoint, cardinalityAggName, queryIndex);
            } else if (params.containsKey(field) && values != null && values.size() > 0) {
                widgetCounts = subjectCountBy(field, params, endpoint, cardinalityAggName, index);
            }
            result.put(widgetQueryName, widgetCounts);
        }

        // additional_update was only needed to correct cardinality estimates.
        // reverse_nested participant counts are already exact — skip those recounts.
        return result;
    }

    private List<Map<String, Object>> participantOverview(Map<String, Object> params) throws IOException {
        // System.out.println(params);
        final String[][] PROPERTIES = new String[][]{
            new String[]{"id", "id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
                new String[]{"study_id", "study_id"},
            new String[]{"race", "race_str"},
            new String[]{"sex_at_birth", "sex_at_birth"},
            new String[]{"synonym_id", "alternate_participant_id"},
            new String[]{"files", "files"},
            new String[]{"diagnosis", "diagnosis_str"},
            new String[]{"anatomic_site", "diagnosis_anatomic_site_str"},
            new String[]{"diagnosis_category", "diagnosis_category_str"},
            new String[]{"age_at_diagnosis", "age_at_diagnosis_str"},
            new String[]{"treatment_agent", "treatment_agent_str"},
            new String[]{"treatment_type", "treatment_type_str"},
            new String[]{"age_at_treatment_start", "age_at_treatment_start_str"},
            new String[]{"first_event", "first_event_str"},
            new String[]{"last_known_survival_status", "last_known_survival_status_str"},
            new String[]{"age_at_last_known_survival_status", "age_at_last_known_survival_status_str"},
        };

        String defaultSort = "participant_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("id", "id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_id", "study_id"),
                Map.entry("race", "race_str"),
                Map.entry("sex_at_birth", "sex_at_birth"),
                Map.entry("synonym_id", "alternate_participant_id"),
                Map.entry("diagnosis", "diagnosis_str"),
                Map.entry("diagnosis_category", "diagnosis_category_str"),
                Map.entry("anatomic_site", "diagnosis_anatomic_site_str"),
                Map.entry("age_at_diagnosis", "age_at_diagnosis_str"),
                Map.entry("treatment_agent", "treatment_agent_str"),
                Map.entry("treatment_type", "treatment_type_str"),
                Map.entry("age_at_treatment_start", "age_at_treatment_start_str"),
                Map.entry("first_event", "first_event_str"),
                Map.entry("last_known_survival_status", "last_known_survival_status_str"),
                Map.entry("age_at_last_known_survival_status", "age_at_last_known_survival_status_str")
        );
        
        // Get the participant list from overview
        List<Map<String, Object>> participant_list = overview(PARTICIPANTS_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "participants_table");

        insertCPIDataIntoParticipants(participant_list);

        // System.out.println("Participant list size after enrichment: " + gson.toJson(participant_list));
        return participant_list;
    }
    
    /**
     * Helper function to extract participant_id and study_id from participant list
     * @param participant_list List of participant objects
     * @return List of ParticipantRequest objects containing participant_id and study_id
     */
    private List<ParticipantRequest> extractIDs(List<Map<String, Object>> participant_list) {
        List<ParticipantRequest> ids = new ArrayList<>();
        
        for (Map<String, Object> participant : participant_list) {
            // Extract participant_id
            Object participantId = participant.get("participant_id");
            String participantIdStr = participantId != null ? participantId.toString() : "";
            
            // Extract study_id
            Object studyId = participant.get("study_id");
            String studyIdStr = studyId != null ? studyId.toString() : "";
            
            // Create ParticipantRequest object
            ParticipantRequest participantRequest = new ParticipantRequest(participantIdStr, studyIdStr);
            ids.add(participantRequest);
        }
        
        return ids;
    }

    /**
     * Puts CPI data into participants (C3DC-aligned).
     */
    private void insertCPIDataIntoParticipants(List<Map<String, Object>> participants) {
        insertCPIDataIntoParticipants(participants, null);
    }

    /**
     * Puts CPI data into participants.
     *
     * @param participants list of participant maps
     * @param synPropName  optional field name to store CPI payload (e.g. synonyms); null uses {@code cpi_data}
     */
    private void insertCPIDataIntoParticipants(List<Map<String, Object>> participants, String synPropName) {
        List<ParticipantRequest> cpiIDs = extractIDs(participants);

        if (cpiFetcherService == null) {
            logger.warn("CPIFetcherService is not properly injected. CPI integration will be skipped.");
            return;
        }

        try {
            List<FormattedCPIResponse> cpiData = cpiFetcherService.fetchAssociatedParticipantIds(cpiIDs);
            logger.info("CPI data received: " + cpiData.size() + " records");

            if (cpiData != null && !cpiData.isEmpty()) {
                enrichCPIDataWithParticipantInfo(cpiData);

                if (synPropName == null) {
                    updateParticipantListWithEnrichedCPIData(participants, cpiData);
                } else {
                    updateParticipantListWithEnrichedCPIData(participants, cpiData, synPropName);
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching CPI data", e);
        }
    }

    /**
     * Enriches CPI data with additional participant information using batch queries for improved performance
     */
    private void enrichCPIDataWithParticipantInfo(List<FormattedCPIResponse> cpiData) throws IOException {
        if (cpiData == null || cpiData.isEmpty()) {
            return;
        }

        // System.out.println("Starting CPI data enrichment for " + cpiData.size() + " records");

        // Step 1: Filter out records that don't have cpiData and collect those that do
        List<FormattedCPIResponse> recordsWithCpiData = new ArrayList<>();
        for (FormattedCPIResponse cpiEntry : cpiData) {
            if (hasCpiData(cpiEntry)) {
                recordsWithCpiData.add(cpiEntry);
            }
        }

        // System.out.println("Found " + recordsWithCpiData.size() + " records with cpiData to enrich");

        if (recordsWithCpiData.isEmpty()) {
            // System.out.println("No records with cpiData to enrich, skipping enrichment");
            return;
        }

        // Step 2: Build HashMap mapping study_id to participant_ids
        Map<String, Set<String>> studyToParticipantsMap = buildStudyToParticipantsMap(recordsWithCpiData);
        // System.out.println("Built study-to-participants mapping with " + studyToParticipantsMap.size() + " studies");

        // Step 3: Generate and execute batch OpenSearch query
        List<Map<String, Object>> batchQueryResults = executeBatchQuery(studyToParticipantsMap);
        // System.out.println("Batch query returned " + batchQueryResults.size() + " results");

        // Step 4: Enrich CPI data with batch query results
        enrichCpiDataWithBatchResults(recordsWithCpiData, batchQueryResults);

        // System.out.println("CPI data enrichment completed");
    }

    /**
     * Checks if a FormattedCPIResponse has cpiData
     */
    private boolean hasCpiData(FormattedCPIResponse cpiEntry) {
        try {
            java.lang.reflect.Field cpiDataField = cpiEntry.getClass().getDeclaredField("cpiData");
            cpiDataField.setAccessible(true);
            Object cpiDataValue = cpiDataField.get(cpiEntry);
            
            if (cpiDataValue instanceof List) {
                List<?> cpiDataList = (List<?>) cpiDataValue;
                return !cpiDataList.isEmpty();
            }
            return false;
        } catch (Exception e) {
            logger.debug("Error checking if record has cpiData: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds a HashMap mapping study_id (repository_of_synonym_id) to participant_ids (associated_id)
     */
    private Map<String, Set<String>> buildStudyToParticipantsMap(List<FormattedCPIResponse> recordsWithCpiData) {
        Map<String, Set<String>> studyToParticipantsMap = new HashMap<>();

        for (FormattedCPIResponse cpiEntry : recordsWithCpiData) {
            try {
                java.lang.reflect.Field cpiDataField = cpiEntry.getClass().getDeclaredField("cpiData");
                cpiDataField.setAccessible(true);
                Object cpiDataValue = cpiDataField.get(cpiEntry);

                if (cpiDataValue instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> cpiDataArray = (List<Object>) cpiDataValue;

                    for (Object cpiDataItem : cpiDataArray) {
                        Map<String, Object> cpiDataMap = convertToMap(cpiDataItem);
                        if (cpiDataMap != null) {
                            String studyId = extractStringValue(cpiDataMap, "repository_of_synonym_id");
                            String participantId = extractStringValue(cpiDataMap, "associated_id");

                            if (studyId != null && participantId != null) {
                                studyToParticipantsMap.computeIfAbsent(studyId, k -> new HashSet<>()).add(participantId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error building study-to-participants map for CPI entry: " + e.getMessage(), e);
            }
        }

        return studyToParticipantsMap;
    }

    /**
     * Converts an object to a Map representation
     */
    private Map<String, Object> convertToMap(Object obj) {
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            return map;
        } else {
            try {
                String jsonString = gson.toJson(obj);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = gson.fromJson(jsonString, Map.class);
                return map;
            } catch (Exception e) {
                logger.debug("Error converting object to Map: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Extracts string value from a map, handling both single values and arrays
     */
    private String extractStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (!list.isEmpty() && list.get(0) != null) {
                return list.get(0).toString();
            }
        } else {
            return value.toString();
        }
        return null;
    }


    private Map<String, Object> studyDetails(Map<String, Object> params) throws IOException {
        Map<String, Object> study;
        String studyId = (String) params.get("study_id");
        List<Map<String, Object>> studies;

        final String[][] PROPERTIES = new String[][]{
            // Demographics
            new String[]{"id", "id"},
            new String[]{"study_id", "study_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_name", "study_name"},
            new String[]{"study_description", "study_description"},
            new String[]{"pubmed_ids", "pubmed_ids"},
            new String[]{"num_of_participants", "num_of_participants"},
            new String[]{"num_of_samples", "num_of_samples"},
            new String[]{"num_of_files", "num_of_files"},
            new String[]{"consent_codes", "consent_codes"},
            new String[]{"diagnosis_anatomic_site", "diagnosis_anatomic_site"},
        };

        String defaultSort = "dbgap_accession"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
            Map.entry("study_id", "study_id"),
            Map.entry("study_name", "study_name"),
            Map.entry("study_description", "study_description"),
            Map.entry("pubmed_ids", "pubmed_ids"),
            Map.entry("num_of_participants", "num_of_participants"),
            Map.entry("num_of_samples", "num_of_samples"),
            Map.entry("num_of_files", "num_of_files"),
            Map.entry("consent_codes", "consent_codes")
        );

        Map<String, Object> study_params = Map.ofEntries(
            Map.entry("study_id", List.of(studyId)),
            Map.entry(ORDER_BY, "study_id"),
            Map.entry(SORT_DIRECTION, "ASC"),
            Map.entry(PAGE_SIZE, 1),
            Map.entry(OFFSET, 0)
        );

        studies = overview(STUDIES_END_POINT, study_params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "studies_table");

        // studies = overview(STUDIES_END_POINT, study_params, PROPERTIES, "dbgap_accession", mapping, "studies");

        study = studies.get(0);
        study.put("anatomic_site", parseStudyProfileCounts(study.remove("diagnosis_anatomic_site")));

        // Get study level statistics
        Map<String, Object> query_params = Map.ofEntries(
            Map.entry("study_id", List.of(studyId))
        );
        Map<String, Object> data = new HashMap<>();
        final List<Map<String, Object>> PARTICIPANT_TERM_AGGS = new ArrayList<>();
        PARTICIPANT_TERM_AGGS.add(Map.of(
                CARDINALITY_AGG_NAME, "pid",
                AGG_NAME, "diagnosis",
                FILTER_COUNT_QUERY, "diagnoses",
                AGG_ENDPOINT, DIAGNOSIS_END_POINT
        ));
        //data_category mapped to data_category
        PARTICIPANT_TERM_AGGS.add(Map.of(
                CARDINALITY_AGG_NAME, "pid",
                AGG_NAME, "data_category",
                FILTER_COUNT_QUERY, "data_categories",
                ADDITIONAL_UPDATE, Map.of("Pathology Imaging", 1000, "Sequencing", 500, "Clinical", 1500),
                AGG_ENDPOINT, FILES_END_POINT
        ));
        for (var agg: PARTICIPANT_TERM_AGGS) {
            String field = (String)agg.get(AGG_NAME);
            Map<String, Integer> additionalUpdate = (Map<String, Integer>)agg.get(ADDITIONAL_UPDATE);
            String filterCountQueryName = (String)agg.get(FILTER_COUNT_QUERY);
            String endpoint = (String)agg.get(AGG_ENDPOINT);
            String indexType = endpoint.replace("/", "").replace("_search", "");
            String cardinalityAggName = (String)agg.get(CARDINALITY_AGG_NAME);
            // System.out.println(cardinalityAggName);
            List<Map<String, Object>> filterCount = filterSubjectCountBy(field, query_params, endpoint, cardinalityAggName, indexType);
            if(RANGE_PARAMS.contains(field)) {
                study.put(filterCountQueryName, filterCount.get(0));
            } else {
                study.put(filterCountQueryName, filterCount);
            }

            if (additionalUpdate != null) {
                List<Map<String, Object>> filterCount_2_update = (List<Map<String, Object>>)study.get(filterCountQueryName);
                List<String> facetValues_need_update = new ArrayList<String>();
                //check if the count for each of the group within the filterCount is smaller than the marked number
                for (Map<String, Object> map : filterCount_2_update) {
                    String group = (String)map.get("group");
                    if (additionalUpdate.containsKey(group)) {
                        int count = (Integer)map.get("subjects");
                        int marked = (Integer)additionalUpdate.get(group);
                        if (count > marked) {
                            //need to perform query
                            facetValues_need_update.add(group);
                        }
                    }
                }
                //if any facet value is above the number, perform the query
                if (facetValues_need_update.size() > 0) {
                    Map<String, Object> query_4_update = inventoryESService.buildFacetFilterQuery(query_params, RANGE_PARAMS, Set.of(field), Set.of(), "nested_filters", "participants_table");
                    String prop = field;
                    query_4_update = inventoryESService.addCustomAggregations(query_4_update, "facetAgg", prop, "sample_diagnosis_genetic_analysis_file_filters");
                    Request request = new Request("GET", PARTICIPANTS_END_POINT);
                    request.setJsonEntity(gson.toJson(query_4_update));
                    JsonObject jsonObject = inventoryESService.send(request);
                    Map<String, Integer> updated_values = inventoryESService.collectCustomTerms(jsonObject, "facetAgg");
                    //update the facet value one more time
                    List<Map<String, Object>> filterCount_new = new ArrayList<Map<String, Object>>();
                    for (Map<String, Object> map : filterCount_2_update) {
                        String group = (String)map.get("group");
                        int count = (Integer)map.get("subjects");
                        // System.out.println(count);
                        if (facetValues_need_update.indexOf(group) >= 0) {
                            count = updated_values.get(group);
                            // System.out.println("-->"+ count);
                        }
                        filterCount_new.add(Map.of("group", group, "subjects", count));
                    }
                    study.put(filterCountQueryName, filterCount_new);
                }
            }
        }

        // todo: querying idc_tcia index for the supporting data
        // if error, return default idc, tcia data
        String idcData = DEFAULT_IDC_DATA.get(studyId);
        String tciaData = DEFAULT_TCIA_DATA.get(studyId);
        if (idcData == null && tciaData == null) {
            study.put("supporting_data", new ArrayList<>());
        } else {
            //formatting the following code please:
            ArrayList<Map<String, Object>> supportingData = new ArrayList<>();
            if (idcData != null) {
                supportingData.add(Map.of("data_category", "IDC", "data_object", idcData));
            }
            if (tciaData != null) {
                supportingData.add(Map.of("data_category", "TCIA", "data_object", tciaData));
            }
            study.put("supporting_data", supportingData);
        }
        return study;
    }

    private List<Map<String, Object>> studiesListing(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            // Demographics
            new String[]{"id", "id"},
            new String[]{"study_id", "study_id"},
            new String[]{"study_name", "study_name"},
            new String[]{"num_of_participants", "num_of_participants"},
            new String[]{"num_of_samples", "num_of_samples"},
            new String[]{"num_of_diagnoses", "num_of_diagnoses"},
            new String[]{"sex_at_birth", "sex_at_birth"},
            new String[]{"num_of_files", "num_of_files"},
            new String[]{"num_of_study_files", "num_of_study_files"},
            new String[]{"num_of_participant_files", "num_of_participant_files"},
            new String[]{"num_of_sample_files", "num_of_sample_files"},
            new String[]{"num_of_publications", "num_of_publications"},
        };

        String defaultSort = "dbgap_accession"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
            Map.entry("study_id", "study_id"),
            Map.entry("study_name", "study_name"),
            Map.entry("num_of_participants", "num_of_participants"),
            Map.entry("num_of_samples", "num_of_samples"),
            Map.entry("num_of_diagnoses", "num_of_diagnoses"),
            Map.entry("num_of_files", "num_of_files"),
            Map.entry("num_of_study_files", "num_of_study_files"),
            Map.entry("num_of_participant_files", "num_of_participant_files"),
            Map.entry("num_of_sample_files", "num_of_sample_files"),
            Map.entry("num_of_publications", "num_of_publications")
        );

        return overview(STUDIES_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "studies_table");
    }

    private List<Map<String, Object>> cohortManifest(Map<String, Object> params) throws IOException {
        List<Map<String, Object>> participants;
        final String[][] PROPERTIES = new String[][]{
            new String[]{"id", "id"}, // Participant guid
            new String[]{"participant_id", "participant_id"},
            new String[]{"study_id", "study_id"},
            new String[]{"sex_at_birth", "sex_at_birth"},
            new String[]{"race", "race_str"},
            new String[]{"diagnosis", "diagnosis"},
        };

        String defaultSort = "participant_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
            Map.entry("participant_id", "participant_id"),
            Map.entry("study_id", "study_id"),
            Map.entry("sex_at_birth", "sex_at_birth"),
            Map.entry("race", "race_str"),
            Map.entry("diagnosis", "diagnosis")
        );

        return overview(COHORT_MANIFEST_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "cohorts");
    }

    private List<Map<String, Object>> cohortMetadata(Map<String, Object> params) throws IOException {
        final String[][] PARTICIPANT_PROPERTIES = new String[][]{
            new String[]{"id", "id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"race", "race"},
            new String[]{"sex_at_birth", "sex_at_birth"},
            new String[]{"occupation", "occupation"},
            new String[]{"guid", "guid"},
            new String[]{"crdc_id", "crdc_id"},
            new String[]{"consent_group_guid", "consent_group_guid"},
            new String[]{"study_guid", "study_guid"},
            new String[]{"clinical_measure_files", "clinical_measure_files"},
            new String[]{"diagnoses", "diagnoses"},
            new String[]{"exposures", "exposures"},
            new String[]{"family_relationships", "family_relationships"},
            new String[]{"laboratory_tests", "laboratory_tests"},
            new String[]{"medical_histories", "medical_histories"},
            new String[]{"radiology_files", "radiology_files"},
            new String[]{"survivals", "survivals"},
            new String[]{"synonyms", "synonyms"},
            new String[]{"treatments_chemotherapy", "treatments_chemotherapy"},
            new String[]{"treatments_other", "treatments_other"},
            new String[]{"treatments_radiation", "treatments_radiation"},
            new String[]{"treatment_responses", "treatment_responses"},
            new String[]{"treatments_surgery", "treatments_surgery"},
            new String[]{"samples", "samples"},
        };

        final String[][] STUDY_PROPERTIES = new String[][]{
            new String[]{"guid", "guid"},
            new String[]{"study_id", "study_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_name", "study_name"},
            new String[]{"study_acronym", "study_acronym"},
            new String[]{"study_description", "study_description"},
            new String[]{"external_url", "external_url"},
            new String[]{"experimental_strategy_and_data_subtype", "experimental_strategy_and_data_subtype"},
            new String[]{"study_phase", "study_phase"},
            new String[]{"study_period_start", "study_period_start"},
            new String[]{"study_period_stop", "study_period_stop"},
            new String[]{"study_data_types", "study_data_types"},
            new String[]{"promotion_status", "promotion_status"},
            new String[]{"crdc_id", "crdc_id"},
            new String[]{"clinical_measure_files", "clinical_measure_files"},
            new String[]{"generic_files", "generic_files"},
            new String[]{"publications", "publications"},
            new String[]{"study_admins", "study_admins"},
            new String[]{"study_arms", "study_arms"},
            new String[]{"study_fundings", "study_fundings"},
            new String[]{"study_personnels", "study_personnels"},
            new String[]{"study_statuses", "study_statuses"},
            new String[]{"consent_groups", "consent_groups"},
            new String[]{"cell_lines", "cell_lines"},
        };

        String defaultSort = "participant_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
            Map.entry("participant_id", "participant_id"),
            Map.entry("race", "race"),
            Map.entry("sex_at_birth", "sex_at_birth")
        );

        List<Map<String, Object>> participants = overview(
            COHORTS_END_POINT,
            params,
            PARTICIPANT_PROPERTIES,
            defaultSort,
            mapping,
            Set.of(),
            "nested_filters",
            "cohorts"
        );

        Set<String> studyGuids = participants.stream()
            .map(participant -> participant.get("study_guid"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .filter(studyGuid -> !studyGuid.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        if (studyGuids.isEmpty()) {
            return List.of();
        }

        Map<String, List<Map<String, Object>>> participantsByConsentGroup = new LinkedHashMap<>();
        participants.forEach(participant -> {
            Object consentGroupGuid = participant.get("consent_group_guid");
            if (consentGroupGuid != null) {
                participantsByConsentGroup
                    .computeIfAbsent(consentGroupGuid.toString(), ignored -> new ArrayList<>())
                    .add(participant);
            }
        });

        // Sort survivals by age for each participant before nesting them under consent groups.
        participants.forEach((Map<String, Object> participant) -> {
            Object survivalsObj = participant.get("survivals");
            if (survivalsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> survivals = (List<Map<String, Object>>) survivalsObj;
                survivals.sort((a, b) -> compareNullableNumbers(
                    a.get("age_at_last_known_survival_status"),
                    b.get("age_at_last_known_survival_status")
                ));
            }
        });

        Request studiesRequest = new Request("GET", STUDIES_FOR_COHORTS_END_POINT);
        Map<String, Object> studiesQuery = new HashMap<>();
        studiesQuery.put("query", Map.of("terms", Map.of("guid", studyGuids)));

        List<Map<String, Object>> studies = inventoryESService.collectPage(
            studiesRequest,
            studiesQuery,
            STUDY_PROPERTIES,
            studyGuids.size(),
            0
        );

        Map<String, Map<String, Object>> studiesByGuid = new HashMap<>();
        studies.forEach(study -> {
            Object studyGuid = study.get("guid");
            if (studyGuid != null) {
                studiesByGuid.put(studyGuid.toString(), study);
            }
        });

        List<Map<String, Object>> result = new ArrayList<>();
        for (String studyGuid : studyGuids) {
            Map<String, Object> study = studiesByGuid.get(studyGuid);
            if (study == null) {
                continue;
            }

            Object consentGroupsObj = study.get("consent_groups");
            List<Map<String, Object>> selectedConsentGroups = new ArrayList<>();
            if (consentGroupsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> consentGroups =
                    (List<Map<String, Object>>) consentGroupsObj;
                for (Map<String, Object> consentGroup : consentGroups) {
                    Object consentGroupGuid = consentGroup.get("guid");
                    List<Map<String, Object>> consentGroupParticipants = consentGroupGuid == null
                        ? null
                        : participantsByConsentGroup.get(consentGroupGuid.toString());
                    if (consentGroupParticipants != null && !consentGroupParticipants.isEmpty()) {
                        consentGroup.put("participants", consentGroupParticipants);
                        selectedConsentGroups.add(consentGroup);
                    }
                }
            }

            study.put("consent_groups", selectedConsentGroups);
            result.add(study);
        }
        return result;
    }

    private int compareNullableNumbers(Object first, Object second) {
        if (first == null && second == null) return 0;
        if (first == null) return 1;
        if (second == null) return -1;

        double firstValue = first instanceof Number
            ? ((Number) first).doubleValue()
            : Double.parseDouble(first.toString());
        double secondValue = second instanceof Number
            ? ((Number) second).doubleValue()
            : Double.parseDouble(second.toString());
        return Double.compare(firstValue, secondValue);
    }

    /**
     * Generates chart data for cohort comparison
     * @param params Contains c1, c2, c3 (cohort participant IDs) and charts (properties to chart)
     * @return List of chart results, one per property
     * @throws IOException
     */
    private List<Map<String, Object>> cohortCharts(Map<String, Object> params) throws IOException {
        List<Map<String, Object>> chartConfigs = null;
        List<Map<String, Object>> charts = new ArrayList<Map<String, Object>>();
        Map<String, Object> cohorts = new HashMap<String, Object>();
        List<String> cohortsCombined = new ArrayList<String>();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

        if (params == null || !params.containsKey("charts")) {
            return List.of(); // No charts specified
        }

        if (!(params.containsKey("c1") || params.containsKey("c2") || params.containsKey("c3"))) {
            return List.of(); // No cohorts specified
        }

        // Combine cohorts from c1, c2, c3 into a single list
        for (String key : List.of("c1", "c2", "c3")) {
            if (!params.containsKey(key)) {
                continue;
            }

            Object cohortRaw = params.get(key);
            if (cohortRaw instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> cohort = (List<String>) cohortRaw;

                if (!cohort.isEmpty()) {
                    // Add cohort to combined list
                    cohortsCombined.addAll(cohort);
                    cohorts.put(key, cohort);
                }
            }
        }

        if (cohortsCombined.isEmpty()) {
            return result;
        }

        Object chartConfigsRaw = params.get("charts");
        if (chartConfigsRaw instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> castedChartConfigs = (List<Map<String, Object>>) chartConfigsRaw;
            chartConfigs = castedChartConfigs;
        }

        if (chartConfigs == null || chartConfigs.isEmpty()) {
            return result;
        }

        // Generate charts for each configuration
        for (Map<String, Object> chartConfig : chartConfigs) {
            // Prepare map that represents the entire chart
            String cohortIdProperty; // The index's property used to filter participant IDs listed in the cohort
            String endpoint; // The endpoint of the main index of the property - used for bucket aggregation
            String indexName; // The main index of the property - used for bucket aggregation
            String property = (String) chartConfig.get("property");
            String type = (String) chartConfig.get("type");
            Map<String, Object> chartData = new HashMap<String, Object>();
            chartData.put("property", property);
            int totalNumberOfParticipants = 0;
            List<String> bucketNames;
            List<String> bucketNamesTopFew;
            List<String> bucketNamesTopMany;

            // Obtain details for querying Opensearch
            Map<String, String> propertyConfig = cohortChartProperties.get(property);
            if (propertyConfig == null) {
                logger.warn("Skipping unknown property: " + property);
                continue;
            }
            if ("false".equals(propertyConfig.get("available"))) {
                logger.warn("Skipping cohort chart property that is not available in a participant-linked index: " + property);
                continue;
            }

            String cardinalityAggName = propertyConfig.get("cardinalityAggName");
            //if cardinalityAggName is "", set it to null
            if (cardinalityAggName.equals("")) {
                cardinalityAggName = null;
            }
            endpoint = propertyConfig.get("endpoint");
            indexName = propertyConfig.get("index");
            cohortIdProperty = indexName.equals("participants_table") ? "id" : "pid";
            // Participant-unique facets are counted from participants_table via nested +
            // reverse_nested. Use that same exact-count query to select the most populous
            // buckets so bucket names and counts come from one source of truth.
            if (cardinalityAggName != null) {
                Map<String, Object> combinedCohortParams = Map.of("id", cohortsCombined);
                List<Map<String, Object>> combinedCohortGroupCounts = filterSubjectCountBy(
                        property, combinedCohortParams, endpoint, cardinalityAggName, indexName);
                combinedCohortGroupCounts.sort(
                        Comparator.<Map<String, Object>>comparingInt(
                                groupCount -> -((Number) groupCount.get("subjects")).intValue())
                                .thenComparing(groupCount -> (String) groupCount.get("group")));
                bucketNames = combinedCohortGroupCounts.stream()
                        .map(groupCount -> (String) groupCount.get("group"))
                        .toList();
            } else {
                Map<String, Object> combinedCohortParams = Map.of(cohortIdProperty, cohortsCombined);
                bucketNames = inventoryESService.getBucketNames(
                        property, combinedCohortParams, RANGE_PARAMS, null, indexName, endpoint);
            }

            if (bucketNames.size() > COHORT_CHART_BUCKET_LIMIT_LOW) {
                bucketNamesTopFew = new ArrayList<>(bucketNames.subList(0, COHORT_CHART_BUCKET_LIMIT_LOW));
            } else {
                bucketNamesTopFew = new ArrayList<>(bucketNames);
            }

            if (bucketNames.size() > COHORT_CHART_BUCKET_LIMIT_HIGH) {
                bucketNamesTopMany = new ArrayList<>(bucketNames.subList(0, COHORT_CHART_BUCKET_LIMIT_HIGH));
            } else {
                bucketNamesTopMany = new ArrayList<>(bucketNames);
            }

            // If chart type is percentage, then count the total number of participants
            if ("percentage".equals(type)) {
                Map<String, Object> combinedCohortParticipantParams = Map.of("id", cohortsCombined);  // Changed from participant_pk to id
                Map<String, Object> combinedCohortsQuery = inventoryESService.buildFacetFilterQuery(combinedCohortParticipantParams, RANGE_PARAMS, Set.of(), Set.of(), "", "participants_table");
                totalNumberOfParticipants = inventoryESService.getCount(combinedCohortsQuery, "participants_table");
            }

            // Prepare list of data for each cohort
            List<Map<String, Object>> cohortsData = new ArrayList<Map<String, Object>>();

            // Retrieve data for each cohort
            for (String cohortName : cohorts.keySet()) {
                // Prepare map of data for the cohort
                Map<String, Object> cohortData = new HashMap<String, Object>();
                // Exact participant facet counts query participants_table, whose participant key
                // is id. Non-participant-unique charts retain their source index's key.
                String cohortCountIdProperty = cardinalityAggName != null ? "id" : cohortIdProperty;
                Map<String, Object> cohortParams = Map.of(cohortCountIdProperty, cohorts.get(cohortName));
                cohortData.put("cohort", cohortName);

                // Retrieve data for the cohort
                List<Map<String, Object>> cohortGroupCounts = filterSubjectCountBy(property, cohortParams, endpoint, cardinalityAggName, indexName);
                List<Map<String, Object>> cohortGroupCountsTruncated = new ArrayList<Map<String, Object>>();
                int otherMany = 0;
                int otherFew = 0;

                // Format for efficient retrieval
                Map<String, Object> groupsToSubjects = new HashMap<>();
                for (Map<String, Object> groupCount : cohortGroupCounts) {
                    String group = (String) groupCount.get("group");
                    Object subjects = groupCount.get("subjects");
                    groupsToSubjects.put(group, subjects);
                }

                // Add buckets and their counts to a truncated list of results
                for (String bucketName : bucketNames) {
                    Integer subjects = (Integer) groupsToSubjects.getOrDefault(bucketName, 0);

                    if (bucketNamesTopMany.contains(bucketName)) {
                        cohortGroupCountsTruncated.add(Map.of("group", bucketName, "subjects", (double) subjects));
                    } else {
                        otherMany += subjects;
                    }

                    if (!bucketNamesTopFew.contains(bucketName)) {
                        otherFew += subjects;
                    }
                }

                if (bucketNames.size() > COHORT_CHART_BUCKET_LIMIT_LOW) {
                    cohortGroupCountsTruncated.add(Map.of("group", "OtherFew", "subjects", (double) otherFew));
                }

                if (bucketNames.size() > COHORT_CHART_BUCKET_LIMIT_HIGH) {
                    cohortGroupCountsTruncated.add(Map.of("group", "OtherMany", "subjects", (double) otherMany));
                }

                // If chart type is percentage, then replace counts with percentages
                if ("percentage".equals(type)) {
                    List<Map<String, Object>> cohortGroupPercentages = new ArrayList<Map<String, Object>>();

                    for (Map<String, Object> groupCount : cohortGroupCountsTruncated) {
                        String group = (String) groupCount.get("group");
                        double count = (Double) groupCount.get("subjects");
                        double percentage = totalNumberOfParticipants > 0 ? (count / totalNumberOfParticipants) * 100 : 0.0;

                        cohortGroupPercentages.add(Map.of("group", group, "subjects", percentage));
                    }

                    cohortData.put("participantsByGroup", cohortGroupPercentages);
                } else if ("count".equals(type)) {
                    cohortData.put("participantsByGroup", cohortGroupCountsTruncated);
                }

                // Add cohort data to the list of cohorts
                cohortsData.add(cohortData);
            }

            // Add list of all cohorts' data to the chart
            chartData.put("cohorts", cohortsData);

            // Add chart to the list of charts
            charts.add(chartData);
        }

        return charts;
    }

    private List<Map<String, Object>> kMPlot(Map<String, Object> params) throws IOException {
        List<Map<String, Object>> dataPoints = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> PROPERTIES = List.of(
            Map.ofEntries( // Participant ID
                Map.entry("gqlName", "id"),
                Map.entry("osName", "id")
            ),
            Map.ofEntries( // Difference between participant's highest age_at_diagnosis and highest age_at_last_known_survival_status
                Map.entry("gqlName", "time"),
                Map.entry("osName", "time")
            ),
            Map.ofEntries( // 1 if participant is dead, and 0 if participant is alive
                Map.entry("gqlName", "event"),
                Map.entry("osName", "event")
            )
        );

        String defaultSort = "time"; // Default sort order

        Map<String, Map<String, Object>> mapping = Map.ofEntries(
            Map.entry("id", Map.ofEntries(
                Map.entry("osName", "id"),
                Map.entry("isNested", false)
            )),
            Map.entry("time", Map.ofEntries(
                Map.entry("osName", "time"),
                Map.entry("isNested", false)
            )),
            Map.entry("event", Map.ofEntries(
                Map.entry("osName", "event"),
                Map.entry("isNested", false)
            ))
        );

        if (!(params.containsKey("c1") || params.containsKey("c2") || params.containsKey("c3"))) {
            return List.of(); // No cohorts specified
        }

        // Iterate through "c1", "c2", and "c3" in params
        for (String cohortKey : List.of("c1", "c2", "c3")) {
            List<String> cohort = new ArrayList<String>();
            Object cohortRaw;

            if (!params.containsKey(cohortKey)) {
                continue;
            }

            cohortRaw = params.get(cohortKey);

            if (cohortRaw == null) {
                continue;
            }

            if (cohortRaw instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> castedCohort = (List<String>) cohortRaw;
                cohort = castedCohort;
            }

            if (cohort.isEmpty()) {
                continue;
            }

            List<Object> filters = new ArrayList<>();
            filters.add(Map.of("terms", Map.of("id", cohort)));
            filters.add(kmPlotDataIsValidFilter());

            Map<String, Object> query = new HashMap<>();
            query.put("query", Map.of("bool", Map.of("filter", filters)));
            query.put("sort", mapSortOrderWithMetadata("time", "asc", defaultSort, mapping));

            Request request = new Request("GET", KM_PLOT_DATA_END_POINT);
            List<Map<String, Object>> cohortKMPlotData = inventoryESService.collectPage(
                request,
                query,
                mapProperties(PROPERTIES),
                ESService.MAX_ES_SIZE,
                0
            );

            // Specify cohort for each data point
            cohortKMPlotData.forEach(data -> {
                data.put("group", cohortKey);
                dataPoints.add(data);
            });
        }

        return dataPoints;
    }

    /**
     * Returns data for the risk table
     * At 0 months, we count all participants who are eligible for KM plot data
     * At 6 months, we subtract participants who experienced the event up until then
     * At 12 months, we further subtract participants who experienced the event up until then
     * And so on...
     * @param params
     * @return List of three "tables" - one for each cohort
     * @throws IOException
     */
    private Map<String, Object> riskTableData(Map<String, Object> params) throws IOException {
        Map<String, Object> result = new HashMap<>(Map.of(
            "timeIntervals", List.of("0 Months", "6 Months", "12 Months", "18 Months", "24 Months", "30 Months", "36 Months")
        ));
        ArrayList<Map<String, Object>> cohortsData = new ArrayList<Map<String, Object>>();

        List<Map<String, Object>> cutoffTimes = List.of(
            Map.of(
                "key", "6 Months",
                "from", 0,
                "to", 183
            ),
            Map.of(
                "key", "12 Months",
                "from", 183,
                "to", 365
            ),
            Map.of(
                "key", "18 Months",
                "from", 365,
                "to", 548
            ),
            Map.of(
                "key", "24 Months",
                "from", 548,
                "to", 730
            ),
            Map.of(
                "key", "30 Months",
                "from", 730,
                "to", 913
            ),
            Map.of(
                "key", "36 Months",
                "from", 913,
                "to", 1095
            )
        );

        // Obtain data for each cohort
        for (String cohortName : List.of("c1", "c2", "c3")) { // All three are guaranteed by GraphQL
            List<String> cohort = new ArrayList<String>();
            JsonArray counts;
            int initialCount;
            Map<String, Object> initialCountQuery;
            JsonObject opensearchResponse;
            Map<String, Object> query;
            String queryJson;
            Request request;
            int runningCount;
            List<Map<String, Object>> table = new ArrayList<Map<String, Object>>();
            Object cohortRaw = params.get(cohortName);

            // Obtain cohort (list of Participant primary keys)
            if (cohortRaw instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> castedCohort = (List<String>) cohortRaw;
                cohort = castedCohort;
            }

            // Count all eligible participants in the cohort
            initialCountQuery = Map.of(
                "query", Map.of(
                    "bool", Map.of(
                        "filter", Set.of(
                            kmPlotDataIsValidFilter(),
                            Map.of(
                                "terms", Map.of(
                                    "id", cohort
                                )
                            )
                        )
                    )
                )
            );

            // Obtain initial count
            initialCount = inventoryESService.getCount(initialCountQuery, KM_PLOT_DATA_INDEX);
            runningCount = initialCount; // To be used later for each cutoff time
            table.add(Map.ofEntries(
                Map.entry("group", "0 Months"),
                Map.entry("subjects", initialCount)
            ));

            // Build query
            query = Map.of(
                "size", 0,
                "query", Map.of(
                    "bool", Map.of(
                        "filter", Set.of(
                            kmPlotDataIsValidFilter(),
                            Map.of(
                                "term", Map.of(
                                    "event", 1
                                )
                            ),
                            Map.of(
                                "terms", Map.of(
                                    "id", cohort
                                )
                            )
                        )
                    )
                ),
                "aggs", Map.of(
                    "cutoff_times", Map.of(
                        "range", Map.of(
                            "field", "time",
                            "ranges", cutoffTimes
                        ),
                        "aggs", Map.of(
                            "unique_participants", Map.of(
                                "cardinality", Map.of(
                                    "field", "id"
                                )
                            )
                        )
                    )
                )
            );

            queryJson = gson.toJson(query);
            request = new Request("GET", KM_PLOT_DATA_END_POINT);
            request.setJsonEntity(queryJson);
            opensearchResponse = inventoryESService.send(request);
            counts = inventoryESService.collectRangCountAggs(opensearchResponse, "cutoff_times").get("cutoff_times");

            for (JsonElement item : counts) {
                String key = item.getAsJsonObject().get("key").getAsString();
                int count = item.getAsJsonObject().get("unique_participants").getAsJsonObject().get("value").getAsInt();
                runningCount = runningCount - count;

                table.add(Map.ofEntries(
                    Map.entry("group", key),
                    Map.entry("subjects", runningCount)
                ));
            }

            // Add data to result to return
            cohortsData.add(Map.ofEntries(
                Map.entry("cohort", cohortName),
                Map.entry("survivalData", table)
            ));
        }

        result.put("cohorts", cohortsData);
        return result;
    }

    private List<Map<String, Object>> diagnosisOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"id", "id"},
            new String[]{"pid", "pid"},
            new String[]{"diagnosis_id", "diagnosis_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_id", "study_id"},
            new String[]{"diagnosis", "diagnosis"},
            new String[]{"anatomic_site", "diagnosis_anatomic_site_str"},
            new String[]{"disease_phase", "disease_phase"},
            new String[]{"diagnosis_classification_system", "diagnosis_classification_system"},
            new String[]{"diagnosis_basis", "diagnosis_basis"},
            new String[]{"diagnosis_category", "diagnosis_category"},
            new String[]{"age_at_diagnosis", "age_at_diagnosis"},
            new String[]{"diagnosis_comment", "diagnosis_comment"},
            new String[]{"tumor_spatial_extent", "tumor_spatial_extent"},
            new String[]{"toronto_childhood_cancer_staging", "toronto_childhood_cancer_staging"},
            new String[]{"tumor_grade", "tumor_grade"},
            new String[]{"tumor_stage_clinical_t", "tumor_stage_clinical_t"},
            new String[]{"tumor_stage_clinical_n", "tumor_stage_clinical_n"},
            new String[]{"tumor_stage_clinical_m", "tumor_stage_clinical_m"},
            new String[]{"tumor_stage_clinical_o", "tumor_stage_clinical_o"}
        };

        String defaultSort = "diagnosis_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("diagnosis_id", "diagnosis_id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("sample_id", "sample_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_id", "study_id"),
                Map.entry("diagnosis", "diagnosis"),
                Map.entry("anatomic_site", "diagnosis_anatomic_site_str"),
                Map.entry("disease_phase", "disease_phase"),
                Map.entry("diagnosis_basis", "diagnosis_basis"),
                Map.entry("diagnosis_classification_system", "diagnosis_classification_system"),
                Map.entry("age_at_diagnosis", "age_at_diagnosis"),
                Map.entry("diagnosis_comment", "diagnosis_comment"),
                Map.entry("tumor_spatial_extent", "tumor_spatial_extent"),
                Map.entry("toronto_childhood_cancer_staging", "toronto_childhood_cancer_staging"),
                Map.entry("tumor_grade", "tumor_grade"),
                Map.entry("tumor_stage_clinical_t", "tumor_stage_clinical_t"),
                Map.entry("tumor_stage_clinical_n", "tumor_stage_clinical_n"),
                Map.entry("tumor_stage_clinical_m", "tumor_stage_clinical_m"),
                Map.entry("tumor_stage_clinical_o", "tumor_stage_clinical_o")
        );

        return overview(DIAGNOSIS_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "diagnoses_table");
    }

    private List<Map<String, Object>> geneticAnalysisOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"ga_id", "id"},
            new String[]{"pid", "pid"},
            new String[]{"genetic_analysis_id", "genetic_analysis_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_id", "study_id"},
            new String[]{"alteration", "alteration"},
            new String[]{"fusion_partner_gene", "fusion_partner_gene"},
            new String[]{"gene_symbol", "gene_symbol"},
            new String[]{"reported_significance", "reported_significance"},
            new String[]{"reported_significance_system", "reported_significance_system"},
            new String[]{"status", "status"},
            new String[]{"test", "test"},
            new String[]{"alteration_effect", "alteration_effect"},
            new String[]{"alteration_type", "alteration_type"},
            new String[]{"chromosome", "chromosome"},
            new String[]{"exon", "exon"},
            new String[]{"fusion_partner_exon", "fusion_partner_exon"},
            new String[]{"reference_genome", "reference_genome"},
            new String[]{"cytoband", "cytoband"},
            new String[]{"genomic_source_category", "genomic_source_category"},
            new String[]{"hgvs_coding", "hgvs_coding"},
            new String[]{"hgvs_genome", "hgvs_genome"},
            new String[]{"hgvs_protein", "hgvs_protein"}
        };

        String defaultSort = "genetic_analysis_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("genetic_analysis_id", "genetic_analysis_id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_id", "study_id"),
                Map.entry("alteration", "alteration"),
                Map.entry("fusion_partner_gene", "fusion_partner_gene"),
                Map.entry("gene_symbol", "gene_symbol"),
                Map.entry("reported_significance", "reported_significance"),
                Map.entry("reported_significance_system", "reported_significance_system"),
                Map.entry("status", "status"),
                Map.entry("test", "test"),
                Map.entry("alteration_effect", "alteration_effect"),
                Map.entry("alteration_type", "alteration_type"),
                Map.entry("chromosome", "chromosome"),
                Map.entry("exon", "exon"),
                Map.entry("fusion_partner_exon", "fusion_partner_exon"),
                Map.entry("reference_genome", "reference_genome"),
                Map.entry("cytoband", "cytoband"),
                Map.entry("genomic_source_category", "genomic_source_category"),
                Map.entry("hgvs_coding", "hgvs_coding"),
                Map.entry("hgvs_genome", "hgvs_genome"),
                Map.entry("hgvs_protein", "hgvs_protein")
        );

        return overview(GENETIC_ANALYSES_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "genetic_analyses_table");
    }

    private List<Map<String, Object>> treatmentOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"t_id", "id"},
            new String[]{"pid", "pid"},
            new String[]{"treatment_id", "treatment_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_id", "study_id"},
            new String[]{"treatment_type", "treatment_type"},
            new String[]{"treatment_agent", "treatment_agent"},
            new String[]{"age_at_treatment_start", "age_at_treatment_start"},
            new String[]{"age_at_treatment_end", "age_at_treatment_end"}
        };

        String defaultSort = "treatment_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("treatment_id", "treatment_id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_id", "study_id"),
                Map.entry("treatment_type", "treatment_type"),
                Map.entry("treatment_agent", "treatment_agent"),
                Map.entry("age_at_treatment_start", "age_at_treatment_start"),
                Map.entry("age_at_treatment_end", "age_at_treatment_end")
        );

        return overview(TREATMENTS_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "treatments_table");
    }

    private List<Map<String, Object>> treatmentResponseOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"tr_id", "id"},
            new String[]{"pid", "pid"},
            new String[]{"treatment_response_id", "treatment_response_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_id", "study_id"},
            new String[]{"response", "response"},
            new String[]{"response_category", "response_category"},
            new String[]{"response_system", "response_system"},
            new String[]{"age_at_response", "age_at_response"}
        };

        String defaultSort = "treatment_response_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("treatment_response_id", "treatment_response_id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_id", "study_id"),
                Map.entry("response", "response"),
                Map.entry("response_category", "response_category"),
                Map.entry("response_system", "response_system"),
                Map.entry("age_at_response", "age_at_response")
        );

        return overview(TREATMENT_RESPONSES_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "treatment_responses_table");
    }

    private List<Map<String, Object>> survivalOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"s_id", "id"},
            new String[]{"pid", "pid"},
            new String[]{"survival_id", "survival_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_id", "study_id"},
            new String[]{"age_at_event_free_survival_status", "age_at_event_free_survival_status"},
            new String[]{"age_at_last_known_survival_status", "age_at_last_known_survival_status"},
            new String[]{"cause_of_death", "cause_of_death"},
            new String[]{"event_free_survival_status", "event_free_survival_status"},
            new String[]{"first_event", "first_event"},
            new String[]{"last_known_survival_status", "last_known_survival_status"}
        };

        String defaultSort = "survival_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("survival_id", "survival_id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_id", "study_id"),
                Map.entry("age_at_event_free_survival_status", "age_at_event_free_survival_status"),
                Map.entry("age_at_last_known_survival_status", "age_at_last_known_survival_status"),
                Map.entry("cause_of_death", "cause_of_death"),
                Map.entry("event_free_survival_status", "event_free_survival_status"),
                Map.entry("first_event", "first_event"),
                Map.entry("last_known_survival_status", "last_known_survival_status")
        );

        return overview(SURVIVALS_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "survivals_table");
    }

    private List<Map<String, Object>> studyOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"id", "id"},
            new String[]{"study_id", "study_id"},
            new String[]{"grant_id", "grant_id"},
            new String[]{"dbgap_accession", "dbgap_accession"},
            new String[]{"study_name", "study_name"},
            new String[]{"study_phase", "study_phase"},
            new String[]{"personnel_name", "PIs"},
            new String[]{"num_of_participants", "num_of_participants"},
            new String[]{"diagnosis", "diagnosis_cancer"},
            new String[]{"num_of_samples", "num_of_samples"},
            new String[]{"anatomic_site", "diagnosis_anatomic_site"},
            new String[]{"num_of_files", "num_of_files"},
            new String[]{"file_type", "file_types"},
            new String[]{"pubmed_id", "pubmed_ids"},
            new String[]{"files", "files"}
        };

        String defaultSort = "study_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("study_id", "study_id"),
                Map.entry("pubmed_id", "pubmed_ids"),
                Map.entry("grant_id", "grant_id"),
                Map.entry("dbgap_accession", "dbgap_accession"),
                Map.entry("study_name", "study_name"),
                Map.entry("study_phase", "study_phase"),
                Map.entry("personnel_name", "PIs"),
                Map.entry("num_of_participants", "num_of_participants"),
                Map.entry("num_of_samples", "num_of_samples"),
                Map.entry("num_of_files", "num_of_files")
        );

        Request request = new Request("GET", PARTICIPANTS_END_POINT);
        Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE, OFFSET, ORDER_BY, SORT_DIRECTION), Set.of(), "nested_filters", "participants_table");
        String[] AGG_NAMES = new String[] {"study_id"};
        query = inventoryESService.addAggregations(query, AGG_NAMES);
        // System.out.println(gson.toJson(query));
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = inventoryESService.send(request);
        Map<String, JsonArray> aggs = inventoryESService.collectTermAggs(jsonObject, AGG_NAMES);
        JsonArray buckets = aggs.get("study_id");
        List<String> data = new ArrayList<>();
        for (var bucket: buckets) {
            data.add(bucket.getAsJsonObject().get("key").getAsString());
        }

        String order_by = (String)params.get(ORDER_BY);
        String direction = ((String)params.get(SORT_DIRECTION));
        int pageSize = (int) params.get(PAGE_SIZE);
        int offset = (int) params.get(OFFSET);
        
        Map<String, Object> study_params = new HashMap<>();
        if (data.size() == 0) {
            data.add("-1");
        }
        study_params.put("study_id", data);
        study_params.put(ORDER_BY, order_by);
        study_params.put(SORT_DIRECTION, direction);
        study_params.put(PAGE_SIZE, pageSize);
        study_params.put(OFFSET, offset);
        
        return overview(STUDIES_END_POINT, study_params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "studies_table");
    }

    private List<Map<String, Object>> sampleOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
            new String[]{"id", "id"},
            new String[]{"sample_id", "sample_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"study_id", "study_id"},
            new String[]{"anatomic_site", "sample_anatomic_site_str"},
            new String[]{"participant_age_at_collection", "participant_age_at_collection"},
            new String[]{"laterality", "laterality"},
            new String[]{"sample_description", "sample_description"},
            new String[]{"sample_tumor_status", "sample_tumor_status"},
            new String[]{"percent_tumor", "percent_tumor"},
            new String[]{"percent_necrosis", "percent_necrosis"},
            new String[]{"pdx_id", "pdx_id"},
            new String[]{"cell_line_id", "cell_line_id"},
            new String[]{"tumor_spatial_extent", "tumor_spatial_extent"},
            new String[]{"diagnosis", "diagnosis_str"},
            new String[]{"diagnosis_category", "diagnosis_category_str"},
            new String[]{"files", "files"}
        };

        String defaultSort = "sample_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("sample_id", "sample_id"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("study_id", "study_id"),
                Map.entry("anatomic_site", "sample_anatomic_site_str"),
                Map.entry("participant_age_at_collection", "participant_age_at_collection"),
                Map.entry("laterality", "laterality"),
                Map.entry("sample_description", "sample_description"),
                Map.entry("sample_tumor_status", "sample_tumor_status"),
                Map.entry("percent_tumor", "percent_tumor"),
                Map.entry("percent_necrosis", "percent_necrosis"),
                Map.entry("pdx_id", "pdx_id"),
                Map.entry("cell_line_id", "cell_line_id"),
                Map.entry("tumor_spatial_extent", "tumor_spatial_extent"),
                Map.entry("diagnosis", "diagnosis_str"),
                Map.entry("diagnosis_category", "diagnosis_category_str")
        );

        return overview(SAMPLES_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "samples_table");
    }

    private List<Map<String, Object>> fileOverview(Map<String, Object> params) throws IOException {
        final String[][] PROPERTIES = new String[][]{
                new String[]{"id", "id"},
            new String[]{"file_id", "file_id"},
            new String[]{"guid", "guid"},
            new String[]{"file_name", "file_name"},
            new String[]{"data_category", "data_category"},
            new String[]{"file_description", "file_description"},
            new String[]{"file_type", "file_type"},
            new String[]{"file_size", "file_size"},
            new String[]{"library_selection", "library_selection"},
            new String[]{"library_source_material", "library_source_material"},
            new String[]{"library_source_molecule", "library_source_molecule"},
            new String[]{"library_strategy", "library_strategy"},
            new String[]{"file_mapping_level", "file_mapping_level"},
            new String[]{"file_access", "file_access"},
            new String[]{"anatomic_site", "anatomic_site"},
            new String[]{"participant_age_at_collection", "participant_age_at_collection"},
            new String[]{"sample_tumor_status", "sample_tumor_status"},
            new String[]{"tumor_spatial_extent", "tumor_spatial_extent"},
            new String[]{"sample_description", "sample_description"},
            new String[]{"percent_tumor", "percent_tumor"},
            new String[]{"percent_necrosis", "percent_necrosis"},
            new String[]{"consent_codes", "consent_codes"},
            new String[]{"fixation_embedding_method", "fixation_embedding_method"},
            new String[]{"staining_method", "staining_method"},
            new String[]{"study_id", "study_id"},
            new String[]{"participant_id", "participant_id"},
            new String[]{"sample_id", "sample_id"},
            new String[]{"md5sum", "md5sum"},
                new String[]{"files", "files"}
        };

        String defaultSort = "file_id"; // Default sort order

        Map<String, String> mapping = Map.ofEntries(
                Map.entry("file_id", "file_id"),
                Map.entry("guid", "guid"),
                Map.entry("file_name", "file_name"),
                Map.entry("data_category", "data_category"),
                Map.entry("file_description", "file_description"),
                Map.entry("file_type", "file_type"),
                Map.entry("file_size", "file_size"),
                Map.entry("study_id", "study_id"),
                Map.entry("library_selection", "library_selection.sort"),
                Map.entry("library_source_material", "library_source_material.sort"),
                Map.entry("library_source_molecule", "library_source_molecule.sort"),
                Map.entry("library_strategy", "library_strategy.sort"),
                Map.entry("file_mapping_level", "file_mapping_level"),
                Map.entry("file_access", "file_access"),
                Map.entry("anatomic_site", "anatomic_site"),
                Map.entry("participant_age_at_collection", "participant_age_at_collection"),
                Map.entry("sample_tumor_status", "sample_tumor_status"),
                Map.entry("tumor_spatial_extent", "tumor_spatial_extent"),
                Map.entry("sample_description", "sample_description"),
                Map.entry("percent_tumor", "percent_tumor"),
                Map.entry("percent_necrosis", "percent_necrosis"),
                Map.entry("consent_codes", "consent_codes"),
                Map.entry("fixation_embedding_method", "fixation_embedding_method"),
                Map.entry("staining_method", "staining_method"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("sample_id", "sample_id"),
                Map.entry("md5sum", "md5sum")
        );

        return overview(FILES_END_POINT, params, PROPERTIES, defaultSort, mapping, Set.of(), "nested_filters", "files_table");
    }

    private Map<String, Object> getFilenames(Map<String, Object> params) throws IOException {
        try {
            final String[][] PROPERTIES = new String[][]{
                    new String[]{"id", "id"},
                new String[]{"file_id", "file_id"},
                new String[]{"guid", "guid"},
                new String[]{"file_name", "file_name"},
                new String[]{"data_category", "data_category"},
                new String[]{"file_description", "file_description"},
                new String[]{"file_type", "file_type"},
                new String[]{"file_size", "file_size"},
                new String[]{"library_selection", "library_selection"},
                new String[]{"library_source_material", "library_source_material"},
                new String[]{"library_source_molecule", "library_source_molecule"},
                new String[]{"library_strategy", "library_strategy"},
                new String[]{"file_mapping_level", "file_mapping_level"},
                new String[]{"file_access", "file_access"},
                new String[]{"anatomic_site", "anatomic_site"},
                new String[]{"participant_age_at_collection", "participant_age_at_collection"},
                new String[]{"sample_tumor_status", "sample_tumor_status"},
                new String[]{"tumor_spatial_extent", "tumor_spatial_extent"},
                new String[]{"sample_description", "sample_description"},
                new String[]{"percent_tumor", "percent_tumor"},
                new String[]{"percent_necrosis", "percent_necrosis"},
                new String[]{"consent_codes", "consent_codes"},
                new String[]{"fixation_embedding_method", "fixation_embedding_method"},
                new String[]{"staining_method", "staining_method"},
                new String[]{"study_id", "study_id"},
                new String[]{"participant_id", "participant_id"},
                new String[]{"sample_id", "sample_id"},
                new String[]{"md5sum", "md5sum"},
                    new String[]{"files", "files"}
            };

            String defaultSort = "file_id"; // Default sort order

            Map<String, String> mapping = Map.ofEntries(
                Map.entry("file_id", "file_id"),
                Map.entry("guid", "guid"),
                Map.entry("file_name", "file_name"),
                Map.entry("data_category", "data_category"),
                Map.entry("file_description", "file_description"),
                Map.entry("file_type", "file_type"),
                Map.entry("file_size", "file_size"),
                Map.entry("study_id", "study_id"),
                Map.entry("library_selection", "library_selection.sort"),
                Map.entry("library_source_material", "library_source_material.sort"),
                Map.entry("library_source_molecule", "library_source_molecule.sort"),
                Map.entry("library_strategy", "library_strategy.sort"),
                Map.entry("file_mapping_level", "file_mapping_level"),
                Map.entry("file_access", "file_access"),
                Map.entry("anatomic_site", "anatomic_site"),
                Map.entry("participant_age_at_collection", "participant_age_at_collection"),
                Map.entry("sample_tumor_status", "sample_tumor_status"),
                Map.entry("tumor_spatial_extent", "tumor_spatial_extent"),
                Map.entry("sample_description", "sample_description"),
                Map.entry("percent_tumor", "percent_tumor"),
                Map.entry("percent_necrosis", "percent_necrosis"),
                Map.entry("consent_codes", "consent_codes"),
                Map.entry("fixation_embedding_method", "fixation_embedding_method"),
                Map.entry("staining_method", "staining_method"),
                Map.entry("participant_id", "participant_id"),
                Map.entry("sample_id", "sample_id"),
                Map.entry("md5sum", "md5sum")
            );

            String filename = (String) params.get("filename");
            String order_by = (String) params.get(ORDER_BY);
            if (order_by == null) {
                order_by = "file_id";
            }
            String sortDirectionParam = (String) params.get(SORT_DIRECTION);
            String direction = (sortDirectionParam != null) ? sortDirectionParam.toLowerCase() : "asc";
            Object pageSizeObj = params.get(PAGE_SIZE);
            Object offsetObj = params.get(OFFSET);
            int pageSize = (pageSizeObj != null) ? (int) pageSizeObj : 10;
            int offset = (offsetObj != null) ? (int) offsetObj : 0;

            // Build query with facet filters (same as fileOverview)
            // Exclude "filename" since it's a String, not a List, and we handle it separately with wildcard
            Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE, OFFSET, ORDER_BY, SORT_DIRECTION, "filename"), Set.of(), "nested_filters", "files_table");
            // Create mutable copy since buildFacetFilterQuery may return immutable collections
            query = new HashMap<>(query);
            if (filename != null && !filename.isEmpty()) {
                // Navigate to the appropriate location to add wildcard based on query structure
                // When there ARE facet filters: {"query": {"bool": {"should": [{"bool": {"filter": [...]}}]}}}
                // When there are NO facet filters: {"query": {"match_all": {}}} from buildFacetFilterQuery
                
                try {
                    Map<String, Object> queryMap = (Map<String, Object>) query.get("query");
                    Map<String, Object> boolQuery = queryMap.containsKey("bool")
                            ? (Map<String, Object>) queryMap.get("bool")
                            : null;
                    
                    // Create multi-field wildcard search
                    // Search across: file_name, data_category, file_description, file_type, file_access,
                    // study_id, participant_id, sample_id, guid, md5sum, library_selection, 
                    // library_source_material, library_strategy, library_source_molecule, file_mapping_level
                    List<String> searchFields = List.of(
                        "file_name", "data_category", "file_description", "file_type", "file_access",
                        "study_id", "participant_id", "sample_id", "guid", "md5sum",
                        "library_selection", "library_source_material", "library_strategy",
                        "library_source_molecule", "file_mapping_level",
                        "anatomic_site", "sample_tumor_status", "tumor_spatial_extent", "sample_description",
                        "consent_codes", "fixation_embedding_method", "staining_method"
                    );
                    
                    List<Map<String, Object>> shouldClauses = new ArrayList<>();
                    for (String field : searchFields) {
                        Map<String, Object> wildcardParams = new HashMap<>();
                        wildcardParams.put("value", "*" + filename + "*");
                        wildcardParams.put("case_insensitive", true);
                        
                        Map<String, Object> fieldClause = new HashMap<>();
                        fieldClause.put(field, wildcardParams);
                        
                        Map<String, Object> wildcardClause = new HashMap<>();
                        wildcardClause.put("wildcard", fieldClause);
                        
                        shouldClauses.add(wildcardClause);
                    }
                    
                    // Wrap the should clauses in a bool query (matches if ANY field contains the search text)
                    Map<String, Object> multiFieldSearch = new HashMap<>();
                    multiFieldSearch.put("bool", Map.of("should", shouldClauses, "minimum_should_match", 1));
                    
                    if (queryMap.containsKey("match_all")) {
                        // No facet filters — buildFacetFilterQuery returns match_all instead of a bool query
                        List<Object> mustList = new ArrayList<>();
                        mustList.add(Map.of("exists", Map.of("field", "file_id")));
                        mustList.add(multiFieldSearch);
                        query.put("query", Map.of("bool", Map.of("must", mustList)));
                    } else if (boolQuery != null && boolQuery.containsKey("should")) {
                        // Query has facet filters in a bool.should structure
                        List<Object> shouldList = (List<Object>) boolQuery.get("should");
                        if (shouldList != null && !shouldList.isEmpty()) {
                            // Get the first (and only) element in should array
                            Map<String, Object> shouldBool = (Map<String, Object>) shouldList.get(0);
                            Map<String, Object> innerBool = (Map<String, Object>) shouldBool.get("bool");
                            List<Object> filterList = (List<Object>) innerBool.get("filter");
                            
                            if (filterList != null) {
                                // Create mutable copy of filter list and add multi-field search
                                List<Object> mutableFilterList = new ArrayList<>(filterList);
                                mutableFilterList.add(0, multiFieldSearch);
                                
                                // Rebuild the query structure
                                Map<String, Object> mutableInnerBool = new HashMap<>(innerBool);
                                mutableInnerBool.put("filter", mutableFilterList);
                                
                                Map<String, Object> mutableShouldBool = new HashMap<>(shouldBool);
                                mutableShouldBool.put("bool", mutableInnerBool);
                                
                                List<Object> mutableShouldList = new ArrayList<>();
                                mutableShouldList.add(mutableShouldBool);
                                
                                Map<String, Object> mutableBoolQuery = new HashMap<>(boolQuery);
                                mutableBoolQuery.put("should", mutableShouldList);
                                
                                Map<String, Object> mutableQueryMap = new HashMap<>(queryMap);
                                mutableQueryMap.put("bool", mutableBoolQuery);
                                
                                query.put("query", mutableQueryMap);
                            }
                        }
                    } else if (boolQuery != null) {
                        // Bool query without should — add filename search to must clause
                        Object mustObj = boolQuery.get("must");
                        List<Object> mustList = new ArrayList<>();
                        
                        if (mustObj != null) {
                            if (mustObj instanceof List) {
                                mustList.addAll((List<Object>) mustObj);
                            } else {
                                mustList.add(mustObj);
                            }
                        }
                        
                        mustList.add(multiFieldSearch);
                        
                        Map<String, Object> mutableBoolQuery = new HashMap<>(boolQuery);
                        mutableBoolQuery.put("must", mustList);
                        
                        Map<String, Object> mutableQueryMap = new HashMap<>(queryMap);
                        mutableQueryMap.put("bool", mutableBoolQuery);
                        
                        query.put("query", mutableQueryMap);
                    }
                } catch (Exception e) {
                    logger.error("Error adding wildcard search: " + e.getMessage(), e);
                }
            }

            query.put("sort", mapSortOrder(order_by, direction, defaultSort, mapping));
            query.put("_source", Map.of("includes", Set.of("id","file_id","guid","file_name","data_category","file_description","file_type","file_size","library_selection","library_source_material","library_source_molecule","library_strategy","file_mapping_level","file_access","study_id","participant_id","sample_id","md5sum","files")));

            Request request = new Request("GET", FILES_END_POINT);
            
            // Get total count with the same query but without pagination
            Map<String, Object> countQuery = new HashMap<>(query);
            countQuery.remove("size");
            countQuery.remove("from");
            countQuery.put("size", 0);
            countQuery.put("track_total_hits", true);
            
            Request countRequest = new Request("GET", FILES_END_POINT);
            countRequest.setJsonEntity(gson.toJson(countQuery));
            JsonObject countResult = inventoryESService.send(countRequest);
            
            int totalCount = 0;
            if (countResult != null && countResult.has("hits")) {
                JsonObject hits = countResult.getAsJsonObject("hits");
                if (hits.has("total")) {
                    JsonObject total = hits.getAsJsonObject("total");
                    if (total.has("value")) {
                        totalCount = total.get("value").getAsInt();
                    }
                }
            }
            System.out.println(gson.toJson(query));
            
            // Get paginated results
            List<Map<String, Object>> page = inventoryESService.collectPage(request, query, PROPERTIES, pageSize, offset);
        
            // Return FilenamesResult structure
            Map<String, Object> result = new HashMap<>();
            result.put("files", page);
            result.put("totalCount", totalCount);
            
            return result;
        } catch (Exception e) {
            logger.error("Error in getFilenames: " + e.getMessage(), e);
            throw new IOException("Error in getFilenames: " + e.getMessage(), e);
        }
    }

    // if the nestedProperty is set, this will filter based upon the params against the nested property for the endpoint's index.
    // otherwise, this will filter based upon the params against the top level properties for the index
    private List<Map<String, Object>> overview(String endpoint, Map<String, Object> params, String[][] properties, String defaultSort, Map<String, String> mapping, Set<String> regular_fields, String nestedProperty, String overviewType) throws IOException {
        
        Request request = new Request("GET", endpoint);
        Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE, OFFSET, ORDER_BY, SORT_DIRECTION), regular_fields, nestedProperty, overviewType);
        // System.out.println(query);
        String order_by = (String)params.get(ORDER_BY);
        String direction = ((String)params.get(SORT_DIRECTION)).toLowerCase();
        query.put("sort", mapSortOrder(order_by, direction, defaultSort, mapping));
        // "_source": {"exclude": [ "sample_diagnosis_file_filters"]}
        if (overviewType.equals("participants_table")) {
            query.put("_source", Map.of("exclude", Set.of("sample_diagnosis_genetic_analysis_file_filters", "survival_filters", "treatment_filters", "treatment_response_filters")));
        }
        if (overviewType.equals("studies_table")) {
            query.put("_source", Map.of("exclude", Set.of("files")));
        }
        if (overviewType.equals("treatments_table")) {
            query.put("_source", Map.of("exclude", Set.of("sample_diagnosis_genetic_analysis_file_filters", "survival_filters", "treatment_response_filters")));
        }
        if (overviewType.equals("treatment_responses_table")) {
            query.put("_source", Map.of("exclude", Set.of("sample_diagnosis_genetic_analysis_file_filters", "survival_filters", "treatment_filters")));
        }
        if (overviewType.equals("survivals_table")) {
            query.put("_source", Map.of("exclude", Set.of("sample_diagnosis_genetic_analysis_file_filters", "treatment_filters", "treatment_response_filters")));
        }
        if (overviewType.equals("diagnoses_table")) {
            query.put("_source", Map.of("exclude", Set.of("sample_genetic_analysis_file_filters", "survival_filters", "treatment_filters", "treatment_response_filters")));
        }
        if (overviewType.equals("genetic_analyses_table")) {
            query.put("_source", Map.of("exclude", Set.of("sample_diagnosis_file_filters", "survival_filters", "treatment_filters", "treatment_response_filters")));
        }
        if (overviewType.equals("samples_table")) {
            query.put("_source", Map.of("exclude", Set.of("diagnosis_filters", "genetic_analysis_filters", "file_filters", "survival_filters", "treatment_filters", "treatment_response_filters")));
        }
        if (overviewType.equals("files_table")) {
            query.put("_source", Map.of("includes", Set.of(
                    "id", "file_id", "guid", "file_name", "data_category", "file_description", "file_type", "file_size",
                    "library_selection", "library_source_material", "library_source_molecule", "library_strategy",
                    "file_mapping_level", "file_access", "anatomic_site", "participant_age_at_collection",
                    "sample_tumor_status", "tumor_spatial_extent", "sample_description", "percent_tumor",
                    "percent_necrosis", "consent_codes", "fixation_embedding_method", "staining_method",
                    "study_id", "participant_id", "sample_id", "md5sum", "files")));
            //query.put("_source", Map.of("exclude", Set.of("combined_filters", "participant_filters", "sample_diagnosis_filters", "survival_filters", "treatment_filters", "treatment_response_filters")));
        }
        int pageSize = (int) params.get(PAGE_SIZE);
        int offset = (int) params.get(OFFSET);
        List<Map<String, Object>> page = inventoryESService.collectPage(request, query, properties, pageSize, offset);
        return page;
    }

    /**
     * Returns a list of records that match the given filters
     * @param endpoint The Opensearch endpoint to query
     * @param params The GraphQL variables to filter by
     * @param properties The properties to retrieve
     * @param defaultSort The default sort
     * @param mapping Map of how to sort each field
     * @param overviewType The type of records retrieved
     * @return
     * @throws IOException
     */
    private List<Map<String, Object>> overview(String endpoint, Map<String, Object> params, List<Map<String, Object>> properties, String defaultSort, Map<String, Map<String, Object>> mapping, String overviewType) throws IOException {
        Request request = new Request("GET", endpoint);
        Map<String, Object> query = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(PAGE_SIZE, OFFSET, ORDER_BY, SORT_DIRECTION), Set.of(), "", overviewType);
        String order_by = (String)params.get(ORDER_BY);
        String direction = ((String)params.get(SORT_DIRECTION)).toLowerCase();
        query.put("sort", mapSortOrderWithMetadata(order_by, direction, defaultSort, mapping));
        int pageSize = (int) params.get(PAGE_SIZE);
        int offset = (int) params.get(OFFSET);
        List<Map<String, Object>> page = inventoryESService.collectPage(request, query, mapProperties(properties), pageSize, offset);
        return page;
    }

    private Map<String, Object> kmPlotDataIsValidFilter() {
        return Map.of("term", Map.of(KM_PLOT_DATA_IS_VALID, true));
    }

    private List<Map<String, Object>> findParticipantIdsInList(Map<String, Object> params) throws IOException {
        final String[][] properties = new String[][]{
                new String[]{"participant_id", "participant_id"},
                new String[]{"study_id", "study_id"}
        };

        Map<String, Object> query = esService.buildListQuery(params, Set.of(), false);
        Request request = new Request("GET",PARTICIPANTS_END_POINT);

        return esService.collectPage(request, query, properties, ESService.MAX_ES_SIZE, 0);
    }

    private Integer numberOfDiseases(Map<String, Object> params) throws IOException {
        Map<String, Object> query_diseases = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "diagnoses_table");
        int numDiseases = getNodeCount("diagnosis", query_diseases, DIAGNOSIS_END_POINT).size();
        return numDiseases;
    }

    private Integer numberOfParticipants(Map<String, Object> params) throws IOException {
        Map<String, Object> query_participants = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "participants_table");
        int numParticipants = getNodeCount("id", query_participants, PARTICIPANTS_END_POINT).size();
        return numParticipants;
    }
    
    private Integer numberOfStudies(Map<String, Object> params) throws IOException {
        Map<String, Object> query_studies = inventoryESService.buildFacetFilterQuery(params, RANGE_PARAMS, Set.of(), Set.of(), "nested_filters", "studies_table");
        int numStudies = getNodeCount("study_id", query_studies, STUDIES_END_POINT).size();
        return numStudies;
    }

    private List<Map<String, Object>> filesManifestInList(Map<String, Object> params) throws IOException {
        final String[][] properties = new String[][]{
                new String[]{"guid", "guid"},
                new String[]{"file_name", "file_name"},
                new String[]{"participant_id", "participant_id"},
                new String[]{"md5sum", "md5sum"}
        };
        Map<String, Object> file_ids = new HashMap<>();
        file_ids.put("id", params.get("id"));

        int pageSize = (int) params.get(PAGE_SIZE);
        int offset = (int) params.get(OFFSET);
        Map<String, Object> query = esService.buildListQuery(file_ids, Set.of(), false);
        query.put("_source", Map.of("includes", Set.of("guid", "file_name", "participant_id", "md5sum")));
        Request request = new Request("GET", FILES_END_POINT);

        return esService.collectPage(request, query, properties, pageSize, offset);
    }

    private Map<String, String> mapSortOrder(String order_by, String direction, String defaultSort, Map<String, String> mapping) {
        String sortDirection = direction;
        if (!sortDirection.equalsIgnoreCase("asc") && !sortDirection.equalsIgnoreCase("desc")) {
            sortDirection = "asc";
        }

        String sortOrder = defaultSort; // Default sort order
        if (mapping.containsKey(order_by)) {
            sortOrder = mapping.get(order_by);
        } else {
            logger.info("Order: \"" + order_by + "\" not recognized, use default order");
        }
        return Map.of(sortOrder, sortDirection);
    }

    private Map<String, Object> mapSortOrderWithMetadata(String order_by, String direction, String defaultSort, Map<String, Map<String, Object>> mapping) {
        String sortDirection = "asc";
        Object sortPredicate;

        // Handle null sort mapping
        if (mapping == null) {
            return Map.of(defaultSort, sortDirection);
        }

        // Handle invalid sort parameters
        if (!mapping.containsKey(order_by)) {
            logger.info("Order: \"" + order_by + "\" not recognized, use default order");
            return Map.of(defaultSort, sortDirection);
        }

        // Only two valid sort directions
        if (direction != null && (direction.equalsIgnoreCase("asc") || direction.equalsIgnoreCase("desc"))) {
            sortDirection = direction.toLowerCase();
        }

        Map<String, Object> mappingDetails = mapping.get(order_by);
        boolean isNested = (Boolean) mappingDetails.get("isNested");
        String propName = (String) mappingDetails.get("osName");

        if (isNested) {
            String nestedPath = (String) mappingDetails.get("path");
            propName = String.join(".", nestedPath, propName);
            sortPredicate = Map.ofEntries(
                Map.entry("nested_path", nestedPath),
                Map.entry("order", sortDirection)
            );
        } else {
            sortPredicate = sortDirection;
        }

        return Map.of(propName, sortPredicate);
    }
    
    private String[][] mapProperties(List<Map<String, Object>> properties) {
        String[][] mappedProperties = new String[properties.size()][2];

        for (int i = 0; i < properties.size(); i++) {
            Map<String, Object> property = properties.get(i);
            mappedProperties[i][0] = (String) property.get("gqlName");
            mappedProperties[i][1] = (String) property.get("osName");
        }
        return mappedProperties;
    }
    
    private List<String> fileIDsFromList(Map<String, Object> params) throws IOException {
        List<String> participantIDsSet = castIdList(params.get("participant_ids"));
        List<String> diagnosisIDsSet = castIdList(params.get("diagnosis_ids"));
        List<String> studyIDsSet = castIdList(params.get("study_ids"));
        List<String> sampleIDsSet = castIdList(params.get("sample_ids"));
        List<String> fileIDsSet = castIdList(params.get("file_ids"));

        // FE passes document GUIDs (participants_table.id). Integrated participants do not
        // embed a files[] array (unlike WebService), so resolve through files_table.pid.
        if (hasUsableIds(participantIDsSet)) {
            return fileIDsFromFilesTableField("pid", participantIDsSet);
        }

        if (hasUsableIds(diagnosisIDsSet)) {
            Map<String, Object> query = inventoryESService.buildGetFileIDsQuery(diagnosisIDsSet);
            Request request = new Request("GET", DIAGNOSIS_END_POINT);
            request.setJsonEntity(gson.toJson(query));
            JsonObject jsonObject = inventoryESService.send(request);
            List<String> embedded = inventoryESService.collectFileIDs(jsonObject);
            if (!embedded.isEmpty()) {
                return embedded;
            }
            // diagnoses_table may not embed files; fall back to participant linkage via pid.
            return fileIDsFromFilesTableField("pid", diagnosisIDsSet);
        }

        if (hasUsableIds(studyIDsSet)) {
            Map<String, Object> query = inventoryESService.buildGetFileIDsQuery(studyIDsSet);
            Request request = new Request("GET", STUDIES_END_POINT);
            request.setJsonEntity(gson.toJson(query));
            JsonObject jsonObject = inventoryESService.send(request);
            List<String> embedded = inventoryESService.collectFileIDs(jsonObject);
            if (!embedded.isEmpty()) {
                return embedded;
            }
            // Fallback when FE passes study_id strings instead of study document GUIDs.
            return fileIDsFromFilesTableField("study_id", studyIDsSet);
        }

        if (hasUsableIds(sampleIDsSet)) {
            // samples_table.files is intentionally empty in indexing; resolve human-readable
            // sample_id values then query files_table.
            List<String> sampleIds = resolveSampleIds(sampleIDsSet);
            if (sampleIds.isEmpty()) {
                return new ArrayList<>();
            }
            return fileIDsFromFilesTableField("sample_id", sampleIds);
        }

        if (hasUsableIds(fileIDsSet)) {
            return fileIDsSet;
        }

        return new ArrayList<>();
    }

    private boolean hasUsableIds(List<String> ids) {
        return ids != null && !ids.isEmpty() && !(ids.size() == 1 && "".equals(ids.get(0)));
    }

    @SuppressWarnings("unchecked")
    private List<String> castIdList(Object raw) {
        if (!(raw instanceof List<?>)) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item != null) {
                ids.add(item.toString());
            }
        }
        return ids;
    }

    private List<String> fileIDsFromFilesTableField(String fieldName, List<String> ids) throws IOException {
        Map<String, Object> query = inventoryESService.buildFilesTableIDsQuery(fieldName, ids);
        Request request = new Request("GET", FILES_END_POINT);
        String[][] properties = new String[][]{
                new String[]{"file_id", "file_id"},
                new String[]{"id", "id"}
        };
        // Use scroll-backed collectPage so we stay under index.max_result_window (10k).
        List<Map<String, Object>> rows = inventoryESService.collectPage(
                request, query, properties, ESService.MAX_ES_SIZE, 0);
        LinkedHashSet<String> fileIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object fileId = row.get("file_id");
            if (fileId != null && !fileId.toString().isBlank()) {
                fileIds.add(fileId.toString());
                continue;
            }
            Object id = row.get("id");
            if (id != null && !id.toString().isBlank()) {
                fileIds.add(id.toString());
            }
        }
        return new ArrayList<>(fileIds);
    }

    private List<String> resolveSampleIds(List<String> sampleDocumentOrSampleIds) throws IOException {
        LinkedHashSet<String> sampleIds = new LinkedHashSet<>();
        // Values may already be sample_id strings; also keep them as lookup candidates.
        for (String value : sampleDocumentOrSampleIds) {
            if (value != null && !value.isBlank()) {
                sampleIds.add(value.trim());
            }
        }
        Map<String, Object> query = new HashMap<>();
        query.put("size", Math.max(sampleDocumentOrSampleIds.size(), 1));
        query.put("query", Map.of("terms", Map.of("id", sampleDocumentOrSampleIds)));
        query.put("_source", Map.of("includes", List.of("id", "sample_id")));
        Request request = new Request("GET", SAMPLES_END_POINT);
        request.setJsonEntity(gson.toJson(query));
        JsonObject jsonObject = inventoryESService.send(request);
        JsonArray hits = jsonObject.getAsJsonObject("hits").getAsJsonArray("hits");
        for (JsonElement hit : hits) {
            JsonObject source = hit.getAsJsonObject().getAsJsonObject("_source");
            if (source == null || !source.has("sample_id") || source.get("sample_id").isJsonNull()) {
                continue;
            }
            JsonElement sampleIdElement = source.get("sample_id");
            if (sampleIdElement.isJsonArray()) {
                for (JsonElement entry : sampleIdElement.getAsJsonArray()) {
                    if (!entry.isJsonNull()) {
                        sampleIds.add(entry.getAsString());
                    }
                }
            } else {
                sampleIds.add(sampleIdElement.getAsString());
            }
        }
        return new ArrayList<>(sampleIds);
    }

    private String generateCacheKey(Map<String, Object> params) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String key: params.keySet()) {
            if (RANGE_PARAMS.contains(key)) {
                List<Integer> bounds = null;
                Object boundsRaw = params.get(key);

                if (boundsRaw instanceof List<?> rawBounds) {
                    List<Integer> casted = new ArrayList<>();
                    for (Object o : rawBounds) {
                        if (o instanceof Number) {
                            casted.add(((Number) o).intValue());
                        } else if (o == null) {
                            casted.add(null);
                        }
                    }
                    bounds = casted;
                }

                if (bounds != null && bounds.size() >= 2) {
                    Integer lower = bounds.get(0);
                    Integer higher = bounds.get(1);
                    if (lower == null && higher == null) {
                        throw new IOException("Lower bound and Upper bound can't be both null!");
                    }
                    keys.add(key.concat(String.valueOf(lower)).concat(String.valueOf(higher)));
                }
            } else {
                List<String> valueSet = null;
                Object valueSetRaw = params.get(key);

                if (valueSetRaw instanceof List<?> rawList) {
                    List<String> asStrings = new ArrayList<>();
                    for (Object o : rawList) {
                        asStrings.add(o != null ? o.toString() : "null");
                    }
                    valueSet = asStrings;
                } else if (valueSetRaw instanceof String s) {
                    valueSet = List.of(s);
                } else if (valueSetRaw instanceof Number n) {
                    valueSet = List.of(n.toString());
                } else if (valueSetRaw instanceof Boolean b) {
                    valueSet = List.of(b.toString());
                }

                if (valueSet != null) {
                    if (valueSet.size() > 0 && !(valueSet.size() == 1 && valueSet.get(0).equals(""))) {
                        keys.add(key.concat(valueSet.toString()));
                    }
                }
            }
        }

        if (keys.isEmpty()) {
            return "all";
        } else {
            return keys.toString();
        }
    }

    /**
     * Executes batch OpenSearch query for all study/participant combinations
     */
    private List<Map<String, Object>> executeBatchQuery(Map<String, Set<String>> studyToParticipantsMap) throws IOException {
        if (studyToParticipantsMap.isEmpty()) {
            return new ArrayList<>();
        }

        // Build the batch query
        Map<String, Object> query = buildBatchQuery(studyToParticipantsMap);
        
        // System.out.println("Executing batch query: " + gson.toJson(query));

        // Execute the query
        Request request = new Request("GET", PARTICIPANTS_END_POINT);
        request.setJsonEntity(gson.toJson(query));
        
        JsonObject response = inventoryESService.send(request);
        JsonArray hits = response.getAsJsonObject("hits").getAsJsonArray("hits");
        
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonElement hit : hits) {
            JsonObject source = hit.getAsJsonObject().getAsJsonObject("_source");
            Map<String, Object> result = new HashMap<>();
            
            if (source.has("id")) {
                result.put("id", source.get("id").getAsString());
            }
            if (source.has("participant_id")) {
                result.put("participant_id", source.get("participant_id").getAsString());
            }
            if (source.has("study_id")) {
                result.put("study_id", source.get("study_id").getAsString());
            }
            
            results.add(result);
        }

        return results;
    }

    /**
     * Builds the batch OpenSearch query based on the study-to-participants mapping
     */
    private Map<String, Object> buildBatchQuery(Map<String, Set<String>> studyToParticipantsMap) {
        List<Object> shouldClauses = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : studyToParticipantsMap.entrySet()) {
            String studyId = entry.getKey();
            Set<String> participantIds = entry.getValue();

            Map<String, Object> boolFilter = Map.of(
                "bool", Map.of(
                    "filter", List.of(
                        Map.of("term", Map.of("study_id", studyId)),
                        Map.of("terms", Map.of("participant_id", new ArrayList<>(participantIds)))
                    )
                )
            );

            shouldClauses.add(boolFilter);
        }

        Map<String, Object> query = Map.of(
            "query", Map.of(
                "bool", Map.of(
                    "should", shouldClauses
                )
            ),
            "size", 10000, // Adjust size as needed
            "_source", List.of("id", "participant_id", "study_id")
        );

        return query;
    }

    /**
     * Enriches CPI data with the results from the batch query
     */
    private void enrichCpiDataWithBatchResults(List<FormattedCPIResponse> recordsWithCpiData, List<Map<String, Object>> batchQueryResults) {
        // Create lookup map for quick access to query results
        Map<String, String> participantStudyToPidMap = new HashMap<>();
        
        for (Map<String, Object> result : batchQueryResults) {
            String participantId = (String) result.get("participant_id");
            String studyId = (String) result.get("study_id");
            String pId = (String) result.get("id");
            
            if (participantId != null && studyId != null && pId != null) {
                String key = participantId + "_" + studyId;
                participantStudyToPidMap.put(key, pId);
            }
        }

        // System.out.println("Created lookup map with " + participantStudyToPidMap.size() + " participant/study combinations");

        // Enrich each CPI data record
        for (FormattedCPIResponse cpiEntry : recordsWithCpiData) {
            enrichSingleCpiEntry(cpiEntry, participantStudyToPidMap);
        }
    }


    /**
     * Enriches a single CPI entry with p_id and data_type
     */
    private void enrichSingleCpiEntry(FormattedCPIResponse cpiEntry, Map<String, String> participantStudyToPidMap) {
        try {
            java.lang.reflect.Field cpiDataField = cpiEntry.getClass().getDeclaredField("cpiData");
            cpiDataField.setAccessible(true);
            Object cpiDataValue = cpiDataField.get(cpiEntry);

            if (cpiDataValue instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> cpiDataArray = (List<Object>) cpiDataValue;

                for (int i = 0; i < cpiDataArray.size(); i++) {
                    Object cpiDataItem = cpiDataArray.get(i);
                    Map<String, Object> cpiDataMap = convertToMap(cpiDataItem);
                    
                    if (cpiDataMap != null) {
                        String participantId = extractStringValue(cpiDataMap, "associated_id");
                        String studyId = extractStringValue(cpiDataMap, "repository_of_synonym_id");
                        
                        if (participantId != null && studyId != null) {
                            String lookupKey = participantId + "_" + studyId;
                            
                            if (participantStudyToPidMap.containsKey(lookupKey)) {
                                // Found match in OpenSearch - set internal data
                                cpiDataMap.put("p_id", participantStudyToPidMap.get(lookupKey));
                                cpiDataMap.put("data_type", "internal");
                                // System.out.println("Enriched CPI data: participant=" + participantId + ", study=" + studyId + ", p_id=" + participantStudyToPidMap.get(lookupKey) + ", data_type=internal");
                            } else {
                                // No match found - set external data
                                cpiDataMap.put("p_id", null);
                                cpiDataMap.put("data_type", "external");
                                // System.out.println("Enriched CPI data: participant=" + participantId + ", study=" + studyId + ", p_id=null, data_type=external");
                            }
                            
                            // If we converted to a new Map, replace the original item
                            if (!(cpiDataItem instanceof Map)) {
                                cpiDataArray.set(i, cpiDataMap);
                            }
                        }
                    }
                }
                
                // Update the cpiData field with enriched array
                cpiDataField.set(cpiEntry, cpiDataArray);
            }
        } catch (Exception e) {
            logger.error("Error enriching single CPI entry: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the participant_list with enriched CPI data by matching participant_id and study_id (C3DC-aligned).
     */
    private void updateParticipantListWithEnrichedCPIData(
            List<Map<String, Object>> participant_list,
            List<FormattedCPIResponse> enriched_cpi_data
    ) {
        updateParticipantListWithEnrichedCPIData(participant_list, enriched_cpi_data, null);
    }

    /**
     * Updates the participant_list with enriched CPI data by matching participant_id and study_id.
     *
     * @param synPropName optional property key on each participant map; if null/empty, uses {@code cpi_data}
     */
    private void updateParticipantListWithEnrichedCPIData(
            List<Map<String, Object>> participant_list,
            List<FormattedCPIResponse> enriched_cpi_data,
            String synPropName
    ) {
        if (participant_list == null || participant_list.isEmpty() || enriched_cpi_data == null || enriched_cpi_data.isEmpty()) {
            return;
        }

        String synonymsPropertyKey = (synPropName != null && !synPropName.isEmpty()) ? synPropName : "cpi_data";

        Map<String, Object> enrichedCPILookup = new HashMap<>();

        for (FormattedCPIResponse cpiResponse : enriched_cpi_data) {
            try {
                Object participantIdObj = getFieldValue(cpiResponse, "participantId");
                Object studyIdObj = getFieldValue(cpiResponse, "studyId");

                String participantId = participantIdObj != null ? participantIdObj.toString() : null;
                String studyId = studyIdObj != null ? studyIdObj.toString() : null;

                if (participantId != null && studyId != null) {
                    String lookupKey = participantId + "_" + studyId;

                    Object enrichedCpiDataArray = getFieldValue(cpiResponse, "cpiData");
                    if (enrichedCpiDataArray != null) {
                        enrichedCPILookup.put(lookupKey, enrichedCpiDataArray);
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing CPI response for lookup map: " + e.getMessage(), e);
            }
        }

        for (Map<String, Object> participant : participant_list) {
            try {
                String participantId = getStringValue(participant, "participant_id");
                String studyId = getStringValue(participant, "study_id");

                if (participantId != null && studyId != null) {
                    String lookupKey = participantId + "_" + studyId;

                    if (enrichedCPILookup.containsKey(lookupKey)) {
                        Object enrichedCpiDataArray = enrichedCPILookup.get(lookupKey);
                        participant.put(synonymsPropertyKey, enrichedCpiDataArray);
                    }
                }
            } catch (Exception e) {
                logger.error("Error updating participant with enriched CPI data: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Helper method to extract field values from FormattedCPIResponse objects using reflection
     */
    private Object getFieldValue(FormattedCPIResponse obj, String fieldName) {
        try {
            java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(obj);
            return value;
        } catch (Exception e) {
            logger.debug("Could not access field '" + fieldName + "' from FormattedCPIResponse: " + e.getMessage());
            return null;
        }
    }

    /**
     * Helper method to safely extract string values from maps
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    @PostConstruct
    public void onStartup() {
        try {
            idsLists(Map.of("cpi_batch_size", 2500, "use_cache", true));
            logger.info("idsLists cache preloaded on application startup");
        } catch (IOException e) {
            logger.error("Failed to preload idsLists cache on startup: " + e.getMessage(), e);
        }
    }
}
