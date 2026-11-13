package gov.nih.nci.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Executes the indexing query against isolated, rolled-back graph fixtures. */
@EnabledIfEnvironmentVariable(named = "NEO4J_TEST_URI", matches = ".+")
class TreatmentConsolidationIntegrationTest {
    private Driver driver;
    private Session session;
    private Transaction tx;
    private String participantGuid;
    private String query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        try (var stream = getClass().getResourceAsStream("/yaml/indices.yaml")) {
            Map<String, Object> config = new ObjectMapper(new YAMLFactory()).readValue(stream, Map.class);
            List<Map<String, Object>> indices = (List<Map<String, Object>>) config.get("indices");
            Map<String, Object> participants = indices.stream()
                    .filter(index -> "participants_table".equals(index.get("index_name")))
                    .findFirst().orElseThrow();
            query = ((List<Map<String, Object>>) participants.get("update_queries")).stream()
                    .filter(update -> "treatments".equals(update.get("name")))
                    .map(update -> (String) update.get("query")).findFirst().orElseThrow();
        }
        // Restrict the production query to this fixture without changing its consolidation logic.
        query = query.replace("MATCH (p:participant)",
                "MATCH (p:participant) WHERE p.guid = $participantGuid");
        driver = GraphDatabase.driver(System.getenv("NEO4J_TEST_URI"));
        session = driver.session();
        tx = session.beginTransaction();
        participantGuid = UUID.randomUUID().toString();
        tx.run("CREATE (:participant {guid: $guid})", Map.of("guid", participantGuid)).consume();
    }

    @AfterEach
    void tearDown() {
        if (tx != null) { tx.rollback(); tx.close(); }
        if (session != null) { session.close(); }
        if (driver != null) { driver.close(); }
    }

    @Test
    void alignsAllFourSourcesAndSortsUnknownAgesByDescendingGuid() {
        treatment("chemotherapy", "a", 20, 30, List.of("Chemotherapy"));
        treatment("radiation", "b", 10, 15, List.of("Radiation"));
        treatment("surgery", "z", -999, 40, List.of("Surgery"));
        treatment("other", "y", "Not Reported", null, List.of("Other"));
        treatment("other", "x", null, 50, List.of("Other"));
        treatment("other", "w", "-999", -999, List.of("Other"));
        Map<String, Object> row = consolidate();
        assertEquals(List.of("Radiation", "Chemotherapy", "Surgery", "Other", "Other", "Other"),
                row.get("treatment_type_str"));
        assertEquals(List.of("10", "20", "Not Reported", "Not Reported", "Not Reported", "Not Reported"),
                row.get("age_at_treatment_start_str"));
        assertEquals(List.of("15", "30", "40", "Not Reported", "50", "Not Reported"),
                row.get("age_at_treatment_end_str"));
        assertEquals(List.of(10L, 20L, -999L, -999L, -999L, -999L), row.get("age_at_treatment_start"));
        assertEquals(List.of(15L, 30L, 40L, -999L, 50L, -999L), row.get("age_at_treatment_end"));
        assertEquals(List.of("agent-a"), row.get("chemotherapy_agent"));
        assertEquals(List.of("radiation-b"), row.get("radiation_therapy_type"));
        assertEquals(List.of("beam-b"), row.get("beam_radiation_type"));
        assertEquals(List.of("surgery-z"), row.get("surgery_type"));
        assertEquals(List.of("other-y", "other-x", "other-w"), row.get("other_treatment_type"));
        List<Map<String, Object>> filters = filters(row);
        assertEquals(List.of("b", "a", "z", "y", "x", "w"),
                filters.stream().map(record -> record.get("guid")).toList());
    }

    @Test
    void retainsIdenticalInstancesAndUsesTreatmentTypeRatherThanChemotherapySubtype() {
        treatment("chemotherapy", "a", 0, null, List.of("Chemotherapy"));
        treatment("chemotherapy", "b", 0, null, List.of("Chemotherapy"));
        Map<String, Object> row = consolidate();
        assertEquals(2, filters(row).size());
        assertEquals(List.of("Chemotherapy", "Chemotherapy"), row.get("treatment_type_str"));
        assertEquals(List.of("b", "a"), filters(row).stream().map(record -> record.get("guid")).toList());
        assertEquals(List.of("agent-b", "agent-a"), row.get("chemotherapy_agent"));
        assertEquals(List.of("0", "0"), row.get("age_at_treatment_start_str"));
        assertEquals(List.of("Not Reported", "Not Reported"), row.get("age_at_treatment_end_str"));
        assertEquals(List.of(0L, 0L), row.get("age_at_treatment_start"));
        assertEquals(List.of(-999L, -999L), row.get("age_at_treatment_end"));
    }

    @Test
    void retainsAgePositionsForMultipleAndMissingTypeValues() {
        treatment("other", "a", 1, 2, List.of("Other", "Surgery"));
        treatment("chemotherapy", "b", 3, 4, null);
        Map<String, Object> row = consolidate();
        assertEquals(List.of("Other", "Surgery", "Not Reported"), row.get("treatment_type_str"));
        assertEquals(List.of("1", "1", "3"), row.get("age_at_treatment_start_str"));
        assertEquals(List.of("2", "2", "4"), row.get("age_at_treatment_end_str"));
        assertEquals(List.of(1L, 1L, 3L), row.get("age_at_treatment_start"));
        assertEquals(List.of(2L, 2L, 4L), row.get("age_at_treatment_end"));
    }

    @Test
    void returnsParticipantWithEmptyColumnsWhenNoTreatmentsExist() {
        Map<String, Object> row = consolidate();
        assertEquals(List.of(), row.get("treatment_filters"));
        assertEquals(List.of(), row.get("treatment_type_str"));
        assertEquals(List.of(), row.get("age_at_treatment_start_str"));
        assertEquals(List.of(), row.get("age_at_treatment_end_str"));
        assertEquals(List.of(), row.get("age_at_treatment_start"));
        assertEquals(List.of(), row.get("age_at_treatment_end"));
    }

    private void treatment(String kind, String guid, Object start, Object end, List<String> types) {
        Map<String, Object> props = new HashMap<>();
        props.put("guid", guid);
        props.put("treatment_" + kind + "_id", "id-" + guid);
        props.put("treatment_type", types);
        props.put("age_at_treatment_start", start);
        props.put("age_at_treatment_end", end);
        if ("chemotherapy".equals(kind)) {
            props.put("chemotherapy_type", List.of("Subtype must not populate Treatment Type"));
            props.put("chemotherapy_agent", List.of("agent-" + guid));
        } else if ("radiation".equals(kind)) {
            props.put("radiation_therapy_type", List.of("radiation-" + guid));
            props.put("beam_radiation_type", List.of("beam-" + guid));
        } else if ("surgery".equals(kind)) {
            props.put("surgery_type", List.of("surgery-" + guid));
        } else if ("other".equals(kind)) {
            props.put("other_treatment_type", List.of("other-" + guid));
        }
        tx.run("MATCH (p:participant {guid: $participantGuid}) CREATE "
                        + "(:treatment_" + kind + " $props)-[:of_treatment_" + kind + "]->(p)",
                Map.of("participantGuid", participantGuid, "props", props)).consume();
    }

    private Map<String, Object> consolidate() {
        return tx.run(query, Map.of("participantGuid", participantGuid, "skip", 0, "limit", 1))
                .single().asMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filters(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("treatment_filters");
    }
}
