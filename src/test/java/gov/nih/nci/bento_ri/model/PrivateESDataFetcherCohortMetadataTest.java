package gov.nih.nci.bento_ri.model;

import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherCohortMetadataTest {

    private static final int PAGE_SIZE = 25;
    private static final int OFFSET = 0;

    @Mock
    private InventoryESService inventoryESService;

    @Test
    void cohortMetadataGroupsParticipantsByDbgapAccession() throws Exception {
        Map<String, Object> firstParticipant = participant("P-1", "phs001");
        Map<String, Object> secondParticipant = participant("P-2", "phs001");
        Map<String, Object> thirdParticipant = participant("P-3", "phs002");

        List<Map<String, Object>> result = runCohortMetadata(List.of(
            firstParticipant,
            secondParticipant,
            thirdParticipant
        ));

        assertEquals(2, result.size());
        assertEquals(List.of(firstParticipant, secondParticipant), participantsFor(result, "phs001"));
        assertEquals(List.of(thirdParticipant), participantsFor(result, "phs002"));
    }

    @Test
    void cohortMetadataSortsSurvivalsByAgeWithNullsLast() throws Exception {
        Map<String, Object> participant = participant("P-1", "phs001");
        participant.put("survivals", new ArrayList<>(List.of(
            mutableMap("age_at_last_known_survival_status", 12),
            mutableMap("age_at_last_known_survival_status", "3"),
            mutableMap("last_known_survival_status", "Unknown")
        )));

        List<Map<String, Object>> result = runCohortMetadata(List.of(participant));

        List<Map<String, Object>> survivals = mapList(participantsFor(result, "phs001").get(0), "survivals");
        assertEquals("3", survivals.get(0).get("age_at_last_known_survival_status"));
        assertEquals(12, survivals.get(1).get("age_at_last_known_survival_status"));
        assertNull(survivals.get(2).get("age_at_last_known_survival_status"));
    }

    @Test
    void cohortMetadataNormalizesTreatmentAgentAndTypeToStringLists() throws Exception {
        Map<String, Object> participant = participant("P-1", "phs001");
        participant.put("treatments", new ArrayList<>(List.of(
            mutableMap(
                "treatment_agent", "Drug A",
                "treatment_type", Arrays.asList("Chemotherapy", null, 42)
            ),
            mutableMap(
                "treatment_agent", Arrays.asList("Drug B", 7),
                "treatment_type", null
            )
        )));

        List<Map<String, Object>> result = runCohortMetadata(List.of(participant));

        List<Map<String, Object>> treatments = mapList(participantsFor(result, "phs001").get(0), "treatments");
        assertEquals(List.of("Drug A"), treatments.get(0).get("treatment_agent"));
        assertEquals(List.of("Chemotherapy", "42"), treatments.get(0).get("treatment_type"));
        assertEquals(List.of("Drug B", "7"), treatments.get(1).get("treatment_agent"));
        assertEquals(List.of(), treatments.get(1).get("treatment_type"));
    }

    private List<Map<String, Object>> runCohortMetadata(List<Map<String, Object>> participants) throws Exception {
        Map<String, Object> params = params();
        when(inventoryESService.buildFacetFilterQuery(
            same(params),
            ArgumentMatchers.<Set<String>>any(),
            ArgumentMatchers.<Set<String>>any(),
            ArgumentMatchers.<Set<String>>any(),
            eq("nested_filters"),
            eq("cohorts")
        )).thenReturn(new HashMap<>());
        when(inventoryESService.collectPage(
            any(Request.class),
            ArgumentMatchers.<Map<String, Object>>any(),
            any(String[][].class),
            eq(PAGE_SIZE),
            eq(OFFSET)
        )).thenReturn(participants);

        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        return invokeCohortMetadata(dataFetcher, params);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> invokeCohortMetadata(
        PrivateESDataFetcher dataFetcher,
        Map<String, Object> params
    ) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod("cohortMetadata", Map.class);
        method.setAccessible(true);
        try {
            return (List<Map<String, Object>>) method.invoke(dataFetcher, params);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("first", PAGE_SIZE);
        params.put("offset", OFFSET);
        params.put("order_by", "participant_id");
        params.put("sort_direction", "asc");
        return params;
    }

    private static Map<String, Object> participant(String participantId, String dbgapAccession) {
        return mutableMap(
            "participant_id", participantId,
            "dbgap_accession", dbgapAccession
        );
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            map.put((String) entries[index], entries[index + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> participantsFor(
        List<Map<String, Object>> cohortMetadata,
        String dbgapAccession
    ) {
        for (Map<String, Object> studyGroup : cohortMetadata) {
            if (dbgapAccession.equals(studyGroup.get("dbgap_accession"))) {
                return (List<Map<String, Object>>) studyGroup.get("participants");
            }
        }
        fail("Expected cohort metadata for dbgap accession " + dbgapAccession);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }
}
