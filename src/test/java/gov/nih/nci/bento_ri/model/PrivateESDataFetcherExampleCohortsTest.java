package gov.nih.nci.bento_ri.model;

import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherExampleCohortsTest {

    @Mock
    private InventoryESService inventoryESService;

    @Test
    void retrievesAllMembershipsOnceAndGroupsTheThreeCohorts() throws Exception {
        when(inventoryESService.collectPage(
                any(Request.class),
                anyMap(),
                any(String[][].class),
                eq(InventoryESService.MAX_ES_SIZE),
                eq(0)
        )).thenReturn(List.of(
            Map.of(
                "cohort", "c2",
                "participant_id", "participant-2",
                "id", "guid-2",
                "study_id", "study-1"
            ),
            Map.of(
                "cohort", "c1",
                "participant_id", "participant-1",
                "id", "guid-1",
                "study_id", "study-1"
            ),
            Map.of(
                "cohort", "c2",
                "participant_id", "participant-3",
                "id", "guid-3",
                "study_id", "study-1"
            ),
            Map.of(
                "cohort", "c3",
                "participant_id", "participant-4",
                "id", "guid-4",
                "study_id", "study-2"
            ),
            Map.of(
                "cohort", "c3",
                "participant_id", "participant-2",
                "id", "guid-2",
                "study_id", "study-1"
            ),
            Map.of(
                "cohort", "unexpected",
                "participant_id", "participant-5",
                "id", "guid-5",
                "study_id", "study-2"
            )
        ));

        Map<String, Object> result = invokeExampleCohorts(
            new PrivateESDataFetcher(inventoryESService)
        );

        assertEquals(List.of(
            Map.of(
                "participant_id", "participant-1",
                "id", "guid-1",
                "study_id", "study-1"
            )
        ), result.get("c1"));
        assertEquals(List.of(
            Map.of(
                "participant_id", "participant-2",
                "id", "guid-2",
                "study_id", "study-1"
            ),
            Map.of(
                "participant_id", "participant-3",
                "id", "guid-3",
                "study_id", "study-1"
            )
        ), result.get("c2"));
        assertEquals(List.of(
            Map.of(
                "participant_id", "participant-4",
                "id", "guid-4",
                "study_id", "study-2"
            ),
            Map.of(
                "participant_id", "participant-2",
                "id", "guid-2",
                "study_id", "study-1"
            )
        ), result.get("c3"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(inventoryESService, times(1)).collectPage(
            requestCaptor.capture(),
            queryCaptor.capture(),
            any(String[][].class),
            eq(InventoryESService.MAX_ES_SIZE),
            eq(0)
        );

        assertEquals("/example_cohorts/_search", requestCaptor.getValue().getEndpoint());
        assertEquals(Map.of("match_all", Map.of()), queryCaptor.getValue().get("query"));
        assertEquals(List.of(
            Map.of("cohort", Map.of("order", "asc")),
            Map.of("participant_id", Map.of("order", "asc"))
        ), queryCaptor.getValue().get("sort"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeExampleCohorts(
            PrivateESDataFetcher dataFetcher
    ) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod("exampleCohorts");
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(dataFetcher);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nestedException) {
                throw nestedException;
            }
            throw exception;
        }
    }
}
