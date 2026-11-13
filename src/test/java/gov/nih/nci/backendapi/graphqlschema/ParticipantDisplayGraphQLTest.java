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
import org.opensearch.client.Request;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParticipantDisplayGraphQLTest {
    private static final List<String> DISPLAY_LISTS = List.of(
        "consent_group_id", "consent_group_name", "diagnosis", "diagnosis_category",
        "diagnosis_anatomic_site", "age_at_diagnosis_str", "disease_phase",
        "diagnosis_classification_system", "diagnosis_basis", "diagnosis_comment",
        "tumor_classification", "toronto_childhood_cancer_staging", "tumor_grade",
        "tumor_stage_clinical_t", "tumor_stage_clinical_n", "tumor_stage_clinical_m",
        "tumor_spatial_extent", "treatment_type_str", "age_at_treatment_start_str",
        "age_at_treatment_end_str", "chemotherapy_agent", "radiation_therapy_type",
        "beam_radiation_type", "surgery_type", "other_treatment_type", "response",
        "age_at_response_str"
    );

    @Test
    void participantDisplayArraysSurviveProjectionAndGraphQLSerialization() throws Exception {
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
        source.put("age_at_diagnosis", List.of(10, 20));
        source.put("age_at_response", List.of(30, 40));
        source.put("event_free_survival_status", "Event-free");
        source.put("age_at_event_free_survival_status", 50);
        source.put("cause_of_death", "Not Reported");
        JsonObject response = InventoryESServiceTestSupport.GSON.toJsonTree(Map.of(
            "hits", Map.of("hits", List.of(Map.of("_source", source)))
        )).getAsJsonObject();
        when(inventory.buildFacetFilterQuery(anyMap(), anySet(), anySet(), anySet(),
                eq("nested_filters"), eq("participants_table")))
            .thenReturn(new HashMap<>());

        InventoryESService collector = InventoryESServiceTestSupport.newService();
        try {
            when(inventory.collectPage(any(Request.class), anyMap(), any(String[][].class),
                    anyInt(), anyInt()))
                .thenAnswer(call -> collector.collectPage(response, call.getArgument(2), 1));
            GraphQL graphQL = GraphQL.newGraphQL(
                PrivateESDataFetcherRuntimeWiringTest.executablePrivateSchema(
                    PrivateESDataFetcherRuntimeWiringTest.privateEsRegistry(), fetcher.buildRuntimeWiring())
            ).build();
            var result = graphQL.execute("{ participantOverview(first: 1) { "
                + String.join(" ", DISPLAY_LISTS)
                + " age_at_diagnosis age_at_response event_free_survival_status"
                + " age_at_event_free_survival_status cause_of_death } }");
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            Map<String, Object> data = result.getData();
            @SuppressWarnings("unchecked")
            var row = ((List<Map<String, Object>>) data.get("participantOverview")).get(0);
            DISPLAY_LISTS.forEach(field -> assertEquals(List.of("first", "second"), row.get(field), field));
            assertEquals(List.of(10, 20), row.get("age_at_diagnosis"));
            assertEquals(List.of(30, 40), row.get("age_at_response"));
            assertEquals(50, row.get("age_at_event_free_survival_status"));
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
