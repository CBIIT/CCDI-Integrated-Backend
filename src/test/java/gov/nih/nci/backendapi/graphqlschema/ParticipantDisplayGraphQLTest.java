package gov.nih.nci.backendapi.graphqlschema;

import com.google.gson.JsonObject;
import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.CPIFetcherService;
import gov.nih.nci.bento_ri.service.InventoryESService;
import graphql.GraphQL;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.Request;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParticipantDisplayGraphQLTest {
    private static final List<String> DISPLAY_LISTS = List.of(
        "consent_group_id", "consent_group_name", "diagnosis_category",
        "tumor_spatial_extent", "beam_radiation_type"
    );
    private static final List<String> DISPLAY_STRINGS = List.of(
        "race",
        "treatment_type",
        "age_at_treatment_start",
        "age_at_treatment_end",
        "chemotherapy_agent",
        "radiation_therapy_type",
        "surgery_type",
        "other_treatment_type",
        "response",
        "age_at_response",
        "diagnosis",
        "age_at_diagnosis",
        "diagnosis_anatomic_site",
        "disease_phase",
        "diagnosis_classification_system",
        "diagnosis_comment",
        "tumor_classification",
        "toronto_childhood_cancer_staging",
        "tumor_grade",
        "tumor_stage_clinical_t",
        "tumor_stage_clinical_n",
        "tumor_stage_clinical_m",
        "diagnosis_basis"
    );

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"first; second"})
    void participantDisplayReturnsMappedStringsWithoutGraphQLErrors(String displayValue) throws Exception {
        InventoryESService inventory = mock(InventoryESService.class);
        CPIFetcherService cpi = mock(CPIFetcherService.class);
        PrivateESDataFetcher fetcher = new PrivateESDataFetcher(inventory);
        Field cpiField = PrivateESDataFetcher.class.getDeclaredField("cpiFetcherService");
        cpiField.setAccessible(true);
        cpiField.set(fetcher, cpi);
        when(cpi.fetchAssociatedParticipantIds(any())).thenReturn(List.of());

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "participant-guid");
        source.put("participant_id", "P1");
        source.put("study_id", "S1");
        DISPLAY_LISTS.forEach(field -> source.put(field, List.of("first", "second")));
        Map<String, String> mappedStrings = new LinkedHashMap<>();
        DISPLAY_STRINGS.forEach(field -> {
            source.put(field, List.of("original", "list"));
            mappedStrings.put(field, displayValue);
        });
        mappedStrings.forEach((field, value) -> source.put(field + "_str", value));
        source.put("age_at_diagnosis", List.of(10, -999));
        source.put("age_at_response", List.of(30, -999));
        source.put("event_free_survival_status", "Event-free");
        source.put("age_at_event_free_survival_status", 50);
        source.put("cause_of_death", "Not Reported");
        // Doc values always arrive as arrays; null fields are omitted by OpenSearch.
        Map<String, Object> fields = new LinkedHashMap<>();
        source.forEach((field, value) -> {
            if (value != null) {
                fields.put(field, value instanceof List ? value : List.of(value));
            }
        });
        JsonObject response = InventoryESServiceTestSupport.GSON.toJsonTree(Map.of(
            "hits", Map.of("hits", List.of(Map.of("fields", fields)))
        )).getAsJsonObject();
        when(inventory.buildFacetFilterQuery(anyMap(), anySet(), anySet(), anySet(),
                eq("nested_filters"), eq("participants_table")))
            .thenReturn(new HashMap<>());

        InventoryESService collector = InventoryESServiceTestSupport.newService();
        try {
            when(inventory.collectDocValuesPage(any(Request.class), anyMap(), any(String[][].class),
                    anySet(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    var projected = collector.collectDocValuesPage(response, call.getArgument(2), call.getArgument(3), 1);
                    mappedStrings.forEach((field, value) ->
                        assertEquals(value, projected.get(0).get(field), field));
                    return projected;
                });
            GraphQL graphQL = GraphQL.newGraphQL(
                PrivateESDataFetcherRuntimeWiringTest.executablePrivateSchema(
                    PrivateESDataFetcherRuntimeWiringTest.privateEsRegistry(), fetcher.buildRuntimeWiring())
            ).build();
            var result = graphQL.execute("{ participantOverview(first: 1) { "
                + String.join(" ", DISPLAY_LISTS)
                + " " + String.join(" ", DISPLAY_STRINGS)
                + " event_free_survival_status"
                + " age_at_event_free_survival_status cause_of_death } }");
            assertEquals(List.of(), result.getErrors(), result.getErrors().toString());
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            var row = ((List<Map<String, Object>>) data.get("participantOverview")).get(0);
            DISPLAY_LISTS.stream()
                .forEach(field -> assertEquals(List.of("first", "second"), row.get(field), field));
            mappedStrings.forEach((field, value) -> assertEquals(value, row.get(field), field));
            assertEquals("50", row.get("age_at_event_free_survival_status"));
            assertEquals("Event-free", row.get("event_free_survival_status"));
            assertEquals("Not Reported", row.get("cause_of_death"));
        } finally {
            InventoryESServiceTestSupport.closeClient(collector);
        }
    }

    @Test
    void retiredTreatmentAgentFilterIsAbsentFromPublicAndPrivateEsSchemas() {
        for (String resource : List.of("ccdi-portal-private-es.graphql", "ccdi-portal-public-es.graphql")) {
            var registry = new SchemaParser().parse(getClass().getResourceAsStream("/graphql/" + resource));
            var query = (ObjectTypeDefinition) registry.getType("QueryType").orElseThrow();
            query.getFieldDefinitions().forEach(field ->
                assertFalse(field.getInputValueDefinitions().stream()
                    .anyMatch(input -> input.getName().equals("treatment_agent")), field.getName()));
            var counts = (ObjectTypeDefinition) registry.getType("SearchParticipantsResult").orElseThrow();
            assertFalse(counts.getFieldDefinitions().stream()
                .anyMatch(field -> field.getName().equals("filterParticipantCountByTreatmentAgent")));
        }
    }
}
