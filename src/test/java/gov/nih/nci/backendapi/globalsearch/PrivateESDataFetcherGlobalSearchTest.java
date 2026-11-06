package gov.nih.nci.backendapi.globalsearch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento_ri.model.FormattedCPIResponse;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.CPIFetcherService;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherGlobalSearchTest {

    private static final Gson GSON = new Gson();

    @Mock
    private InventoryESService inventoryESService;

    /**
     * Verifies that global search combines participant, study, sample, file, model metadata,
     * and About-page results while applying the display-field enrichments expected by GraphQL.
     */
    @Test
    void returnsResultsFromEveryGlobalSearchCategory() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String endpoint = request.getEndpoint();
            if (endpoint.endsWith("/_count")) {
                return countResponse(1);
            }
            if ("/studies_table/_search".equals(endpoint)) {
                return searchResponse("""
                        {
                          "_source": {
                            "study_id": "STUDY-1",
                            "consent_codes": [["GRU", "HMB"], "GRU"]
                          }
                        }
                        """);
            }
            if ("/about_page/_search".equals(endpoint)) {
                return searchResponse("""
                        {
                          "_source": {
                            "page": "/about",
                            "title": "About CCDI",
                            "content": [{"paragraph": "CCDI connects participant data."}]
                          },
                          "highlight": {
                            "content.paragraph": ["CCDI connects $participant$ data."]
                          }
                        }
                        """);
            }
            return emptySearchResponse();
        });
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> switch (((Request) invocation.getArgument(0)).getEndpoint()) {
            case "/participants_table/_search" -> List.of(mutableMap(
                    "participant_id", "PARTICIPANT-1",
                    "study_id", "STUDY-1",
                    "diagnosis_str", "",
                    "_sample_diagnosis_filters", List.of(
                            mutableMap(
                                    "diagnosis", List.of("Neuroblastoma", "Neuroblastoma"),
                                    "diagnosis_category", "Solid tumor",
                                    "age_at_diagnosis", List.of("5", "-999")
                            ),
                            mutableMap("diagnosis", "Osteosarcoma")
                    ),
                    "_treatment_filters", List.of(mutableMap(
                            "treatment_type", "Chemotherapy",
                            "treatment_agent", List.of("Cyclophosphamide", "-1")
                    )),
                    "_survival_filters", List.of(mutableMap(
                            "last_known_survival_status", "Alive"
                    ))
            ));
            case "/studies_table/_search" -> List.of(mutableMap(
                    "study_id", "STUDY-1",
                    "study_name", "Example study",
                    "consent_codes", List.of(List.of("GRU", "HMB"), "GRU", "-1")
            ));
            case "/samples_table/_search" -> List.of(mutableMap(
                    "sample_id", "SAMPLE-1",
                    "participant_id", "PARTICIPANT-1",
                    "study_id", "STUDY-1",
                    "_diagnosis_filters", List.of(
                            mutableMap(
                                    "diagnosis", List.of("Neuroblastoma", "Neuroblastoma"),
                                    "diagnosis_category", "Solid tumor"
                            ),
                            mutableMap("diagnosis", "Osteosarcoma")
                    )
            ));
            case "/files_table/_search" -> List.of(mutableMap(
                    "id", "FILE-1",
                    "file_name", "participant-data.bam"
            ));
            case "/model_nodes/_search" -> List.of(mutableMap(
                    "node", "participant",
                    "highlight", "participant"
            ));
            case "/model_properties/_search" -> List.of(mutableMap(
                    "node", "participant",
                    "property", "participant_id",
                    "property_description", "Unique participant identifier",
                    "property_type", "string",
                    "property_required", "true"
            ));
            case "/model_values/_search" -> List.of(mutableMap(
                    "node", "participant",
                    "property", "sex_at_birth",
                    "value", "Male"
            ));
            default -> List.of();
        });

        Map<String, Object> result = invokeGlobalSearch("participant");

        assertEquals(1, result.get("participant_count"));
        assertEquals(1, result.get("study_count"));
        assertEquals(1, result.get("sample_count"));
        assertEquals(1, result.get("file_count"));
        assertEquals(3, result.get("model_count"));
        assertEquals(1, result.get("about_count"));

        Map<String, Object> participant = firstResult(result, "participants");
        assertEquals("subject", participant.get("category_type"));
        assertEquals("Neuroblastoma; Osteosarcoma", participant.get("diagnosis_str"));
        assertEquals("Solid tumor", participant.get("diagnosis_category_str"));
        assertEquals("5", participant.get("age_at_diagnosis_str"));
        assertEquals("Chemotherapy", participant.get("treatment_type_str"));
        assertEquals("Cyclophosphamide", participant.get("treatment_agent_str"));
        assertEquals("Alive", participant.get("last_known_survival_status_str"));
        assertEquals("GRU; HMB", participant.get("consent_codes"));
        assertFalse(participant.containsKey("_sample_diagnosis_filters"));
        assertFalse(participant.containsKey("_treatment_filters"));
        assertFalse(participant.containsKey("_survival_filters"));

        Map<String, Object> study = firstResult(result, "studies");
        assertEquals("study", study.get("category_type"));
        assertEquals("GRU; HMB", study.get("consent_codes"));

        Map<String, Object> sample = firstResult(result, "samples");
        assertEquals("sample", sample.get("category_type"));
        assertEquals("Neuroblastoma; Osteosarcoma", sample.get("diagnosis_str"));
        assertEquals("Solid tumor", sample.get("diagnosis_category_str"));
        assertFalse(sample.containsKey("_diagnosis_filters"));

        assertEquals("file", firstResult(result, "files").get("category_type"));

        List<Map<String, Object>> model = results(result, "model");
        assertEquals(List.of("node", "property", "value"), model.stream()
                .map(hit -> hit.get("category_type"))
                .toList());

        Map<String, Object> about = firstResult(result, "about_page");
        assertEquals("about", about.get("category_type"));
        assertEquals("about_page", about.get("type"));
        assertEquals("/about", about.get("page"));
        assertEquals("About CCDI", about.get("title"));
        assertEquals(List.of("CCDI connects $participant$ data."), about.get("text"));
    }

    /**
     * Verifies that model metadata fields use match-phrase-prefix queries compatible with their
     * search-as-you-type mappings.
     */
    @Test
    void usesPhrasePrefixQueriesForModelMetadata() throws Exception {
        Map<String, Object> category = Map.of(
                "search_field", List.of("node"),
                "category_type", "node"
        );

        Map<String, Object> query = invokePrivate(
                "getGlobalSearchQuery",
                new Class<?>[]{String.class, Map.class},
                "participant",
                category
        );

        String json = GSON.toJson(query);
        assertTrue(json.contains("match_phrase_prefix"));
        assertTrue(json.contains("\"node\""));
        assertTrue(json.contains("participant"));
    }

    /**
     * Verifies that ordinary fields use case-insensitive prefix and escaped wildcard clauses and
     * that file searches require a real file identifier.
     */
    @Test
    void buildsSafeKeywordQueriesAndRequiresFileIds() throws Exception {
        Map<String, Object> category = Map.of(
                "search_field", List.of("file_name_gs"),
                "category_type", "file"
        );

        Map<String, Object> query = invokePrivate(
                "getGlobalSearchQuery",
                new Class<?>[]{String.class, Map.class},
                "bam*?",
                category
        );

        Map<String, Object> bool = nestedMap(nestedMap(query, "query"), "bool");
        assertEquals(Map.of("exists", Map.of("field", "file_id")), bool.get("must"));
        assertEquals(1, bool.get("minimum_should_match"));
        List<Map<String, Object>> clauses = mapList(bool.get("should"));
        Map<String, Object> prefix = nestedMap(nestedMap(clauses.get(0), "prefix"), "file_name_gs");
        Map<String, Object> wildcard = nestedMap(nestedMap(clauses.get(1), "wildcard"), "file_name_gs");
        assertEquals("bam*?", prefix.get("value"));
        assertEquals(true, prefix.get("case_insensitive"));
        assertEquals("*bam\\*\\?*", wildcard.get("value"));
        assertEquals(true, wildcard.get("case_insensitive"));
    }

    /**
     * Verifies that blank input deliberately returns no hits instead of becoming a match-all query.
     */
    @Test
    void returnsNoHitsForBlankSearchInput() throws Exception {
        Map<String, Object> category = Map.of(
                "search_field", List.of("study_name_gs"),
                "category_type", "study"
        );

        Map<String, Object> query = invokePrivate(
                "getGlobalSearchQuery",
                new Class<?>[]{String.class, Map.class},
                "   ",
                category
        );

        Map<String, Object> bool = nestedMap(nestedMap(query, "query"), "bool");
        assertEquals(Map.of("match_all", Map.of()), bool.get("must_not"));
        assertFalse(bool.containsKey("should"));
    }

    /**
     * Verifies that a null search input is normalized to an empty value and returns no hits.
     */
    @Test
    void returnsNoHitsForNullSearchInput() throws Exception {
        Map<String, Object> category = Map.of(
                "search_field", List.of("study_name_gs"),
                "category_type", "study"
        );

        Map<String, Object> query = invokePrivate(
                "getGlobalSearchQuery",
                new Class<?>[]{String.class, Map.class},
                null,
                category
        );

        Map<String, Object> bool = nestedMap(nestedMap(query, "query"), "bool");
        assertEquals(Map.of("match_all", Map.of()), bool.get("must_not"));
        assertFalse(bool.containsKey("should"));
    }

    /**
     * Verifies that highlight configuration is added for every searchable field without mutating
     * the caller's original query map.
     */
    @Test
    void addsHighlightFieldsWithoutMutatingTheQuery() throws Exception {
        Map<String, Object> original = new HashMap<>(Map.of("query", Map.of("match_all", Map.of())));
        Map<String, Object> category = Map.of(
                "search_field", List.of("study_id", "study_name"),
                "category_type", "study"
        );

        Map<String, Object> highlighted = invokePrivate(
                "addHighlight",
                new Class<?>[]{Map.class, Map.class},
                original,
                category
        );

        assertNotSame(original, highlighted);
        assertFalse(original.containsKey("highlight"));
        Map<String, Object> highlight = nestedMap(highlighted, "highlight");
        assertEquals(Map.of("study_id", Map.of(), "study_name", Map.of()), highlight.get("fields"));
        assertEquals("", highlight.get("pre_tags"));
        assertEquals("", highlight.get("post_tags"));
        assertEquals(1, highlight.get("fragment_size"));
    }

    /**
     * Verifies highlighted About text, matching source paragraphs, and first-paragraph fallback
     * for title-only matches.
     */
    @Test
    void returnsAboutPageHighlightsAndSourceFallbacks() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenReturn(searchResponse(
                """
                        {
                          "_source": {
                            "page": "/highlighted",
                            "title": "Highlighted",
                            "content": [{"paragraph": "Unused source text"}]
                          },
                          "highlight": {"content.paragraph": ["A $participant$ highlight"]}
                        }
                        """,
                """
                        {
                          "_source": {
                            "page": "/matching-source",
                            "title": "Matching source",
                            "content": [
                              {"paragraph": "This participant paragraph matches."},
                              {"paragraph": "This paragraph does not."}
                            ]
                          }
                        }
                        """,
                """
                        {
                          "_source": {
                            "page": "/title-only",
                            "title": "Participant title",
                            "content": [
                              {"paragraph": "The first paragraph is the fallback."},
                              {"paragraph": "The second paragraph is ignored."}
                            ]
                          }
                        }
                        """
        ));

        List<Map<String, Object>> results = invokePrivate(
                "searchAboutPage",
                new Class<?>[]{String.class},
                "participant"
        );

        assertEquals(3, results.size());
        assertEquals(List.of("A $participant$ highlight"), results.get(0).get("text"));
        assertEquals(List.of("This participant paragraph matches."), results.get(1).get("text"));
        assertEquals(List.of("The first paragraph is the fallback."), results.get(2).get("text"));
        assertTrue(results.stream().allMatch(hit -> "about".equals(hit.get("category_type"))));
        assertTrue(results.stream().allMatch(hit -> "about_page".equals(hit.get("type"))));
    }

    /**
     * Verifies that the optional About-page index can be unavailable without breaking global search.
     */
    @Test
    void returnsNoAboutResultsWhenTheIndexIsUnavailable() throws Exception {
        when(inventoryESService.send(any(Request.class)))
                .thenThrow(new IOException("no such index [about_page]"));

        List<Map<String, Object>> results = invokePrivate(
                "searchAboutPage",
                new Class<?>[]{String.class},
                "participant"
        );

        assertTrue(results.isEmpty());
    }

    /**
     * Verifies that nested clinical filters are flattened, deduplicated, stripped of sentinel
     * values, and removed after participant and sample display fields are prepared.
     */
    @Test
    void aggregatesNestedClinicalValuesForDisplay() throws Exception {
        Map<String, Object> participant = mutableMap(
                "diagnosis_str", "Curated diagnosis",
                "_sample_diagnosis_filters", List.of(
                        mutableMap(
                                "diagnosis", List.of("Nested diagnosis", "Nested diagnosis", "null"),
                                "diagnosis_category", List.of("Cancer", "null"),
                                "age_at_diagnosis", List.of("12", "-999", "-1")
                        ),
                        "not a map"
                ),
                "_treatment_filters", List.of(mutableMap(
                        "treatment_type", List.of("Radiation", "Radiation", ""),
                        "treatment_agent", "Agent A"
                )),
                "_survival_filters", List.of(mutableMap(
                        "last_known_survival_status", "Alive"
                ))
        );
        Map<String, Object> sample = mutableMap(
                "_diagnosis_filters", List.of(mutableMap(
                        "diagnosis", List.of("Sample diagnosis", "Sample diagnosis"),
                        "diagnosis_category", List.of("Solid tumor", "-1")
                ))
        );

        invokePrivate(
                "enrichGlobalSearchParticipantsFromNestedFilters",
                new Class<?>[]{List.class},
                List.of(participant)
        );
        invokePrivate(
                "enrichGlobalSearchSamplesFromNestedFilters",
                new Class<?>[]{List.class},
                List.of(sample)
        );

        assertEquals("Curated diagnosis", participant.get("diagnosis_str"));
        assertEquals("Cancer", participant.get("diagnosis_category_str"));
        assertEquals("12", participant.get("age_at_diagnosis_str"));
        assertEquals("Radiation", participant.get("treatment_type_str"));
        assertEquals("Agent A", participant.get("treatment_agent_str"));
        assertEquals("Alive", participant.get("last_known_survival_status_str"));
        assertFalse(participant.containsKey("_sample_diagnosis_filters"));
        assertEquals("Sample diagnosis", sample.get("diagnosis_str"));
        assertEquals("Solid tumor", sample.get("diagnosis_category_str"));
        assertFalse(sample.containsKey("_diagnosis_filters"));
    }

    /**
     * Verifies that unavailable model indexes are treated as optional while the remaining global
     * search response is still returned.
     */
    @Test
    void continuesWhenModelIndexesAreUnavailable() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String endpoint = request.getEndpoint();
            if (endpoint.contains("model_")) {
                throw new IOException("no such index [model_nodes]");
            }
            if (endpoint.endsWith("/_count")) {
                return countResponse(0);
            }
            return emptySearchResponse();
        });
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenReturn(List.of());

        Map<String, Object> result = invokeGlobalSearch("participant");

        assertEquals(0, result.get("model_count"));
        assertTrue(((List<?>) result.get("model")).isEmpty());
        assertEquals(0, result.get("participant_count"));
        assertEquals(0, result.get("about_count"));
    }

    /**
     * Verifies that a count failure for a required application index is propagated instead of
     * being handled like an optional model-index failure.
     */
    @Test
    void propagatesApplicationCountFailures() throws Exception {
        when(inventoryESService.send(any(Request.class)))
                .thenThrow(new IOException("participant count failed"));

        IOException exception = assertThrows(
                IOException.class,
                () -> invokeGlobalSearch("participant")
        );

        assertEquals("participant count failed", exception.getMessage());
    }

    /**
     * Verifies that model metadata remains optional when its count succeeds but collecting its
     * matching documents fails.
     */
    @Test
    void continuesWhenModelResultCollectionFails() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            if (endpoint.endsWith("/_count")) {
                return countResponse(endpoint.contains("model_") ? 1 : 0);
            }
            return emptySearchResponse();
        });
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            if (endpoint.contains("model_")) {
                throw new IOException("model result collection failed");
            }
            return List.of();
        });

        Map<String, Object> result = invokeGlobalSearch("participant");

        assertEquals(3, result.get("model_count"));
        assertTrue(results(result, "model").isEmpty());
        assertEquals(0, result.get("about_count"));
    }

    /**
     * Verifies that a result-collection failure for a required application index is propagated to
     * the caller instead of being treated like an optional model-index failure.
     */
    @Test
    void propagatesApplicationResultCollectionFailures() throws Exception {
        when(inventoryESService.send(any(Request.class))).thenReturn(countResponse(1));
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenThrow(new IOException("participant result collection failed"));

        IOException exception = assertThrows(
                IOException.class,
                () -> invokeGlobalSearch("participant")
        );

        assertEquals("participant result collection failed", exception.getMessage());
    }

    /**
     * Verifies that participant identifiers are sent to CPI and returned CPI data is attached
     * without making a real CPI request.
     */
    @Test
    void enrichesParticipantsWithMockedCpiResults() throws Exception {
        stubEmptyGlobalSearchResponses();
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            if ("/participants_table/_search".equals(endpoint)) {
                return List.of(mutableMap(
                        "participant_id", "PARTICIPANT-1",
                        "study_id", "STUDY-1"
                ));
            }
            return List.of();
        });
        CPIFetcherService cpiFetcherService = mock(CPIFetcherService.class);
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenReturn(List.of(
                new FormattedCPIResponse("PARTICIPANT-1", "STUDY-1", List.of())
        ));
        PrivateESDataFetcher dataFetcher = newDataFetcher();
        setPrivateField(dataFetcher, "cpiFetcherService", cpiFetcherService);

        Map<String, Object> result = invokeGlobalSearch(dataFetcher, "participant");

        Map<String, Object> participant = firstResult(result, "participants");
        assertEquals(List.of(), participant.get("cpi_data"));
        verify(cpiFetcherService).fetchAssociatedParticipantIds(argThat(requests ->
                requests.size() == 1
                        && "PARTICIPANT-1".equals(requests.get(0).getParticipantId())
                        && "STUDY-1".equals(requests.get(0).getStudyId())
        ));
    }

    /**
     * Verifies that an empty CPI response leaves the ordinary participant result intact.
     */
    @Test
    void returnsParticipantsWhenCpiFindsNoAssociations() throws Exception {
        stubEmptyGlobalSearchResponses();
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            return "/participants_table/_search".equals(endpoint)
                    ? List.of(mutableMap("participant_id", "PARTICIPANT-1", "study_id", "STUDY-1"))
                    : List.of();
        });
        CPIFetcherService cpiFetcherService = mock(CPIFetcherService.class);
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenReturn(List.of());
        PrivateESDataFetcher dataFetcher = newDataFetcher();
        setPrivateField(dataFetcher, "cpiFetcherService", cpiFetcherService);

        Map<String, Object> result = invokeGlobalSearch(dataFetcher, "participant");

        Map<String, Object> participant = firstResult(result, "participants");
        assertEquals("PARTICIPANT-1", participant.get("participant_id"));
        assertFalse(participant.containsKey("cpi_data"));
    }

    /**
     * Verifies that a null CPI response is treated like an empty response and does not prevent the
     * participant result from being returned.
     */
    @Test
    void returnsParticipantsWhenCpiResponseIsNull() throws Exception {
        stubEmptyGlobalSearchResponses();
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            return "/participants_table/_search".equals(endpoint)
                    ? List.of(mutableMap("participant_id", "PARTICIPANT-1", "study_id", "STUDY-1"))
                    : List.of();
        });
        CPIFetcherService cpiFetcherService = mock(CPIFetcherService.class);
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenReturn(null);
        PrivateESDataFetcher dataFetcher = newDataFetcher();
        setPrivateField(dataFetcher, "cpiFetcherService", cpiFetcherService);

        Map<String, Object> result = invokeGlobalSearch(dataFetcher, "participant");

        Map<String, Object> participant = firstResult(result, "participants");
        assertEquals("PARTICIPANT-1", participant.get("participant_id"));
        assertFalse(participant.containsKey("cpi_data"));
    }

    /**
     * Verifies that a CPI service failure does not prevent ordinary participant search results
     * from being returned.
     */
    @Test
    void continuesWhenCpiEnrichmentFails() throws Exception {
        stubEmptyGlobalSearchResponses();
        when(inventoryESService.collectPage(
                any(Request.class),
                ArgumentMatchers.<Map<String, Object>>any(),
                any(String[][].class),
                anyInt(),
                anyInt()
        )).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            return "/participants_table/_search".equals(endpoint)
                    ? List.of(mutableMap("participant_id", "PARTICIPANT-1", "study_id", "STUDY-1"))
                    : List.of();
        });
        CPIFetcherService cpiFetcherService = mock(CPIFetcherService.class);
        when(cpiFetcherService.fetchAssociatedParticipantIds(any()))
                .thenThrow(new IOException("CPI unavailable"));
        PrivateESDataFetcher dataFetcher = newDataFetcher();
        setPrivateField(dataFetcher, "cpiFetcherService", cpiFetcherService);

        Map<String, Object> result = invokeGlobalSearch(dataFetcher, "participant");

        assertEquals("PARTICIPANT-1", firstResult(result, "participants").get("participant_id"));
        verify(cpiFetcherService).fetchAssociatedParticipantIds(any());
    }

    /**
     * Verifies consent lookup handling for malformed hits, missing values, JSON objects, and an
     * OpenSearch failure without relying on a live studies index.
     */
    @Test
    void handlesConsentLookupEdgeCases() throws Exception {
        when(inventoryESService.send(any(Request.class)))
                .thenReturn(searchResponse(
                        "{}",
                        "{\"_source\": {}}",
                        "{\"_source\": {\"study_id\": null}}",
                        "{\"_source\": {\"study_id\": \"NO-CONSENT\"}}",
                        "{\"_source\": {\"study_id\": \"OBJECT-CONSENT\", \"consent_codes\": {\"code\": \"GRU\"}}}"
                ))
                .thenThrow(new IOException("studies index unavailable"));

        Map<String, String> consentCodes = invokePrivate(
                "lookupConsentCodesByStudyIds",
                new Class<?>[]{Set.class},
                Set.of("NO-CONSENT", "OBJECT-CONSENT")
        );
        Map<String, String> unavailableResult = invokePrivate(
                "lookupConsentCodesByStudyIds",
                new Class<?>[]{Set.class},
                Set.of("STUDY-1")
        );

        assertTrue(consentCodes.containsKey("NO-CONSENT"));
        assertNull(consentCodes.get("NO-CONSENT"));
        assertEquals("{\"code\":\"GRU\"}", consentCodes.get("OBJECT-CONSENT"));
        assertTrue(unavailableResult.isEmpty());
    }

    /**
     * Verifies the defensive return paths and alternate value shapes used by the global-search
     * enrichment helpers.
     */
    @Test
    void handlesEmptyAndAlternateEnrichmentValues() throws Exception {
        PrivateESDataFetcher dataFetcher = newDataFetcher();

        invokePrivate(dataFetcher,
                "enrichGlobalSearchParticipantsFromNestedFilters",
                new Class<?>[]{List.class},
                (Object) null
        );
        invokePrivate(dataFetcher,
                "enrichGlobalSearchParticipantsFromNestedFilters",
                new Class<?>[]{List.class},
                List.of()
        );
        invokePrivate(dataFetcher,
                "enrichGlobalSearchSamplesFromNestedFilters",
                new Class<?>[]{List.class},
                (Object) null
        );
        invokePrivate(dataFetcher,
                "enrichGlobalSearchSamplesFromNestedFilters",
                new Class<?>[]{List.class},
                List.of()
        );

        Map<String, Object> existingConsent = mutableMap(
                "consent_codes", List.of("GRU", "HMB", "GRU")
        );
        Map<String, Object> missingStudy = mutableMap("participant_id", "PARTICIPANT-2");
        Map<String, Object> blankStudy = mutableMap(
                "participant_id", "PARTICIPANT-3",
                "study_id", "   "
        );
        invokePrivate(dataFetcher,
                "enrichGlobalSearchParticipantsWithConsentCodes",
                new Class<?>[]{List.class},
                List.of(existingConsent, missingStudy, blankStudy)
        );
        assertEquals("GRU; HMB", existingConsent.get("consent_codes"));
        assertTrue(missingStudy.containsKey("consent_codes"));
        assertNull(missingStudy.get("consent_codes"));
        assertNull(blankStudy.get("consent_codes"));

        invokePrivate(dataFetcher,
                "enrichGlobalSearchParticipantsWithConsentCodes",
                new Class<?>[]{List.class},
                (Object) null
        );
        invokePrivate(dataFetcher,
                "enrichGlobalSearchParticipantsWithConsentCodes",
                new Class<?>[]{List.class},
                List.of()
        );
        invokePrivate(dataFetcher,
                "normalizeGlobalSearchConsentCodes",
                new Class<?>[]{List.class},
                (Object) null
        );
        invokePrivate(dataFetcher,
                "normalizeGlobalSearchConsentCodes",
                new Class<?>[]{List.class},
                List.of()
        );
        assertTrue(((Map<?, ?>) invokePrivate(dataFetcher,
                "lookupConsentCodesByStudyIds",
                new Class<?>[]{Set.class},
                Set.of()
        )).isEmpty());
        assertTrue(((Map<?, ?>) invokePrivate(dataFetcher,
                "lookupConsentCodesByStudyIds",
                new Class<?>[]{Set.class},
                (Object) null
        )).isEmpty());

        assertNull(invokePrivate(dataFetcher,
                "parseJsonElement",
                new Class<?>[]{com.google.gson.JsonElement.class},
                (Object) null
        ));
        assertNull(invokePrivate(dataFetcher,
                "parseJsonElement",
                new Class<?>[]{com.google.gson.JsonElement.class},
                JsonNull.INSTANCE
        ));
        assertEquals("{\"code\":\"GRU\"}", invokePrivate(dataFetcher,
                "parseJsonElement",
                new Class<?>[]{com.google.gson.JsonElement.class},
                JsonParser.parseString("{\"code\": \"GRU\"}")
        ));
        assertNull(invokePrivate(dataFetcher,
                "formatConsentCodesValue",
                new Class<?>[]{Object.class},
                List.of("", "null", "-999", "-1")
        ));
        assertTrue(((List<?>) invokePrivate(dataFetcher,
                "asListOfMaps",
                new Class<?>[]{Object.class},
                "not a list"
        )).isEmpty());
        assertNull(invokePrivate(dataFetcher,
                "aggregateNestedFilterValues",
                new Class<?>[]{List.class, String.class},
                List.of(),
                "diagnosis"
        ));
        assertNull(invokePrivate(dataFetcher,
                "aggregateNestedFilterValues",
                new Class<?>[]{List.class, String.class},
                null,
                "diagnosis"
        ));
        assertNull(invokePrivate(dataFetcher,
                "aggregateNestedFilterValues",
                new Class<?>[]{List.class, String.class},
                java.util.Collections.singletonList(null),
                "diagnosis"
        ));
        assertNull(invokePrivate(dataFetcher,
                "aggregateNestedFilterValues",
                new Class<?>[]{List.class, String.class},
                List.of(mutableMap("diagnosis", List.of("-999", "-1", "null", ""))),
                "diagnosis"
        ));

        Map<String, Object> blankTarget = mutableMap("diagnosis_str", "null");
        invokePrivate(dataFetcher,
                "putIfBlank",
                new Class<?>[]{Map.class, String.class, String.class},
                blankTarget,
                "diagnosis_str",
                "Replacement diagnosis"
        );
        assertEquals("Replacement diagnosis", blankTarget.get("diagnosis_str"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeGlobalSearch(String input) throws Exception {
        return invokeGlobalSearch(newDataFetcher(), input);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeGlobalSearch(PrivateESDataFetcher dataFetcher, String input)
            throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("input", input);
        params.put("first", 10);
        params.put("offset", 0);
        return invokePrivate(dataFetcher, "globalSearch", new Class<?>[]{Map.class}, params);
    }

    private <T> T invokePrivate(String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        return invokePrivate(newDataFetcher(), methodName, parameterTypes, arguments);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivate(
            PrivateESDataFetcher dataFetcher,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return (T) method.invoke(dataFetcher, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private PrivateESDataFetcher newDataFetcher() throws IOException {
        return new PrivateESDataFetcher(inventoryESService);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void stubEmptyGlobalSearchResponses() throws IOException {
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            String endpoint = ((Request) invocation.getArgument(0)).getEndpoint();
            return endpoint.endsWith("/_count") ? countResponse(0) : emptySearchResponse();
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    private static Map<String, Object> firstResult(Map<String, Object> result, String key) {
        return results(result, key).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static JsonObject countResponse(int count) {
        JsonObject response = new JsonObject();
        response.addProperty("count", count);
        return response;
    }

    private static JsonObject emptySearchResponse() {
        JsonObject hits = new JsonObject();
        hits.add("hits", new JsonArray());
        JsonObject response = new JsonObject();
        response.add("hits", hits);
        return response;
    }

    private static JsonObject searchResponse(String... hitJson) {
        JsonArray hits = new JsonArray();
        for (String json : hitJson) {
            hits.add(JsonParser.parseString(json));
        }
        JsonObject hitsObject = new JsonObject();
        hitsObject.add("hits", hits);
        JsonObject response = new JsonObject();
        response.add("hits", hitsObject);
        return response;
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
