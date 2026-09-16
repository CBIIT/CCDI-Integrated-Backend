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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherCohortMetadataTest {

    private static final int PAGE_SIZE = 25;
    private static final int OFFSET = 0;

    @Mock
    private InventoryESService inventoryESService;

    @Test
    void cohortMetadataNestsParticipantsWithinConsentGroupsAndStudies() throws Exception {
        Map<String, Object> firstParticipant = participant("P-1", "study-guid-1", "consent-guid-1");
        Map<String, Object> secondParticipant = participant("P-2", "study-guid-1", "consent-guid-1");
        Map<String, Object> thirdParticipant = participant("P-3", "study-guid-2", "consent-guid-2");
        Map<String, Object> firstStudy = study(
            "study-guid-1",
            "STUDY-1",
            "phs001",
            consentGroup("consent-guid-1", "CG-1"),
            consentGroup("unused-consent-guid", "CG-unused")
        );
        firstStudy.put("study_name", "First study");

        List<Map<String, Object>> result = runCohortMetadata(List.of(
            firstParticipant,
            secondParticipant,
            thirdParticipant
        ), List.of(
            firstStudy,
            study(
                "study-guid-2",
                "STUDY-2",
                "phs002",
                consentGroup("consent-guid-2", "CG-2")
            )
        ));

        assertEquals(2, result.size());
        assertEquals("First study", result.get(0).get("study_name"));
        assertEquals(
            List.of(firstParticipant, secondParticipant),
            participantsFor(result.get(0), "consent-guid-1")
        );
        assertEquals(List.of(thirdParticipant), participantsFor(result.get(1), "consent-guid-2"));
        assertEquals(1, mapList(result.get(0), "consent_groups").size());
    }

    @Test
    void cohortMetadataSortsSurvivalsByAgeWithNullsLast() throws Exception {
        Map<String, Object> participant = participant(
            "P-1",
            "study-guid-1",
            "consent-guid-1"
        );
        participant.put("survivals", new ArrayList<>(List.of(
            mutableMap("age_at_last_known_survival_status", 12),
            mutableMap("age_at_last_known_survival_status", "3"),
            mutableMap("last_known_survival_status", "Unknown")
        )));

        List<Map<String, Object>> result = runCohortMetadata(
            List.of(participant),
            List.of(study(
                "study-guid-1",
                "STUDY-1",
                "phs001",
                consentGroup("consent-guid-1", "CG-1")
            ))
        );

        Map<String, Object> nestedParticipant =
            participantsFor(result.get(0), "consent-guid-1").get(0);
        List<Map<String, Object>> survivals = mapList(nestedParticipant, "survivals");
        assertEquals("3", survivals.get(0).get("age_at_last_known_survival_status"));
        assertEquals(12, survivals.get(1).get("age_at_last_known_survival_status"));
        assertNull(survivals.get(2).get("age_at_last_known_survival_status"));
    }

    @Test
    void cohortMetadataRequestsEveryTopLevelFieldFromBothIndexes() throws Exception {
        Map<String, Object> participant = participant(
            "P-1",
            "study-guid-1",
            "consent-guid-1"
        );
        Set<String> expectedParticipantFields = Set.of(
            "id", "participant_id", "race", "sex_at_birth", "occupation", "guid",
            "crdc_id", "consent_group_guid", "study_guid", "clinical_measure_files",
            "diagnoses", "exposures", "family_relationships", "laboratory_tests",
            "medical_histories", "radiology_files", "survivals", "synonyms",
            "treatments_chemotherapy", "treatments_other", "treatments_radiation",
            "treatment_responses", "treatments_surgery", "samples"
        );
        Set<String> expectedStudyFields = Set.of(
            "guid", "study_id", "dbgap_accession", "study_name", "study_acronym",
            "study_description", "external_url", "experimental_strategy_and_data_subtype",
            "study_phase", "study_period_start", "study_period_stop", "study_data_types",
            "promotion_status", "crdc_id", "clinical_measure_files", "generic_files",
            "publications", "study_admins", "study_arms", "study_fundings",
            "study_personnels", "study_statuses", "consent_groups", "cell_lines"
        );
        Map<String, Object> params = params();

        stubFacetQuery(params);
        when(inventoryESService.collectPage(
            any(Request.class),
            ArgumentMatchers.<Map<String, Object>>any(),
            any(String[][].class),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.anyInt()
        )).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            String[][] properties = invocation.getArgument(2);
            Set<String> fields = propertyNames(properties);
            if ("/cohorts/_search".equals(request.getEndpoint())) {
                assertEquals(expectedParticipantFields, fields);
                return List.of(participant);
            }
            assertEquals(expectedStudyFields, fields);
            return List.of(study(
                "study-guid-1",
                "STUDY-1",
                "phs001",
                consentGroup("consent-guid-1", "CG-1")
            ));
        });

        invokeCohortMetadata(new PrivateESDataFetcher(inventoryESService), params);
    }

    @Test
    void cohortMetadataQueriesOnlyStudiesReferencedByParticipants() throws Exception {
        Map<String, Object> params = params();
        List<Map<String, Object>> participants = List.of(
            participant("P-1", "study-guid-2", "consent-guid-2"),
            participant("P-2", "study-guid-1", "consent-guid-1"),
            participant("P-3", "study-guid-2", "consent-guid-2")
        );

        stubFacetQuery(params);
        when(inventoryESService.collectPage(
            any(Request.class),
            ArgumentMatchers.<Map<String, Object>>any(),
            any(String[][].class),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.anyInt()
        )).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            if ("/cohorts/_search".equals(request.getEndpoint())) {
                return participants;
            }
            Map<String, Object> query = invocation.getArgument(1);
            assertEquals(
                Map.of("query", Map.of("terms", Map.of(
                    "guid", Set.of("study-guid-1", "study-guid-2")
                ))),
                query
            );
            assertEquals("/studies_for_cohorts/_search", request.getEndpoint());
            assertEquals(2, invocation.<Integer>getArgument(3));
            assertEquals(0, invocation.<Integer>getArgument(4));
            return List.of(
                study(
                    "study-guid-1",
                    "STUDY-1",
                    "phs001",
                    consentGroup("consent-guid-1", "CG-1")
                ),
                study(
                    "study-guid-2",
                    "STUDY-2",
                    "phs002",
                    consentGroup("consent-guid-2", "CG-2")
                )
            );
        });

        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        invokeCohortMetadata(dataFetcher, params);

        verify(inventoryESService).collectPage(
            ArgumentMatchers.argThat(request ->
                "/studies_for_cohorts/_search".equals(request.getEndpoint())
            ),
            ArgumentMatchers.<Map<String, Object>>any(),
            any(String[][].class),
            eq(2),
            eq(0)
        );
    }

    private List<Map<String, Object>> runCohortMetadata(
        List<Map<String, Object>> participants,
        List<Map<String, Object>> studies
    ) throws Exception {
        Map<String, Object> params = params();
        stubFacetQuery(params);
        when(inventoryESService.collectPage(
            any(Request.class),
            ArgumentMatchers.<Map<String, Object>>any(),
            any(String[][].class),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.anyInt()
        )).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            return "/cohorts/_search".equals(request.getEndpoint()) ? participants : studies;
        });

        PrivateESDataFetcher dataFetcher = new PrivateESDataFetcher(inventoryESService);
        return invokeCohortMetadata(dataFetcher, params);
    }

    private void stubFacetQuery(Map<String, Object> params) throws IOException {
        when(inventoryESService.buildFacetFilterQuery(
            same(params),
            ArgumentMatchers.<Set<String>>any(),
            ArgumentMatchers.<Set<String>>any(),
            ArgumentMatchers.<Set<String>>any(),
            eq("nested_filters"),
            eq("cohorts")
        )).thenReturn(new HashMap<>());
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

    private static Map<String, Object> participant(
        String participantId,
        String studyGuid,
        String consentGroupGuid
    ) {
        return mutableMap(
            "participant_id", participantId,
            "study_guid", studyGuid,
            "consent_group_guid", consentGroupGuid
        );
    }

    private static Map<String, Object> study(
        String guid,
        String studyId,
        String dbgapAccession,
        Map<String, Object>... consentGroups
    ) {
        return mutableMap(
            "guid", guid,
            "study_id", studyId,
            "dbgap_accession", dbgapAccession,
            "consent_groups", new ArrayList<>(List.of(consentGroups))
        );
    }

    private static Map<String, Object> consentGroup(String guid, String consentGroupId) {
        return mutableMap(
            "guid", guid,
            "consent_group_id", consentGroupId
        );
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            map.put((String) entries[index], entries[index + 1]);
        }
        return map;
    }

    private static List<Map<String, Object>> participantsFor(
        Map<String, Object> study,
        String consentGroupGuid
    ) {
        for (Map<String, Object> consentGroup : mapList(study, "consent_groups")) {
            if (consentGroupGuid.equals(consentGroup.get("guid"))) {
                return mapList(consentGroup, "participants");
            }
        }
        throw new AssertionError("Expected consent group " + consentGroupGuid);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static Set<String> propertyNames(String[][] properties) {
        return java.util.Arrays.stream(properties)
            .map(property -> property[0])
            .collect(Collectors.toSet());
    }
}
