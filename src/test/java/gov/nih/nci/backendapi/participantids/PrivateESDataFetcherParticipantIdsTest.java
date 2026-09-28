package gov.nih.nci.backendapi.participantids;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento_ri.model.FormattedCPIResponse;
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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherParticipantIdsTest {

    private static final String PARTICIPANTS_ENDPOINT = "/participants_table/_search";

    @Mock
    private InventoryESService inventoryESService;

    @Mock
    private CPIFetcherService cpiFetcherService;

    private PrivateESDataFetcher dataFetcher;
    private Cache<String, Object> cache;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
        cache = Caffeine.newBuilder().build();
        setPrivateField("caffeineCache", cache);
        setPrivateField("cpiFetcherService", cpiFetcherService);
    }

    /**
     * Verifies idsLists batches participants for CPI, returns participant and associated IDs,
     * enriches associations against participants_table, and caches the completed response.
     */
    @Test
    void returnsBatchedParticipantAndAssociatedIdsThenUsesCache() throws Exception {
        List<Map<String, Object>> participants = participantRows("P1", "P2", "P3");
        stubParticipantOverview(participants);
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ParticipantRequest> requests = invocation.getArgument(0);
            List<FormattedCPIResponse> responses = new ArrayList<>();
            for (ParticipantRequest request : requests) {
                List<FormattedCPIResponse.CPIDataItem> associations = "P1".equals(
                        request.getParticipantId())
                        ? new ArrayList<>(List.of(
                                new FormattedCPIResponse.CPIDataItem(
                                        "ASSOC-INTERNAL", "STUDY-B", "Internal", "study", "/internal"),
                                new FormattedCPIResponse.CPIDataItem(
                                        "ASSOC-EXTERNAL", "EXT", "External", "registry", "/external")))
                        : new ArrayList<>();
                responses.add(new FormattedCPIResponse(
                        request.getParticipantId(), request.getStudyId(), associations));
            }
            return responses;
        });
        when(inventoryESService.send(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            if (!PARTICIPANTS_ENDPOINT.equals(request.getEndpoint())) {
                throw new AssertionError("Unexpected OpenSearch endpoint: " + request.getEndpoint());
            }
            return participantSearchResponse("PID-INTERNAL", "ASSOC-INTERNAL", "STUDY-B");
        });
        Map<String, Object> params = Map.of("cpi_batch_size", 2, "use_cache", true);

        Map<String, List<Object>> firstResult = invokeIdsLists(params);

        assertEquals(List.of("P1", "P2", "P3"), firstResult.get("participantIds"));
        assertEquals(List.of(
                Map.of("associated_id", "ASSOC-INTERNAL", "participant_id", "P1"),
                Map.of("associated_id", "ASSOC-EXTERNAL", "participant_id", "P1")),
                firstResult.get("associatedIds"));
        verify(cpiFetcherService, times(2)).fetchAssociatedParticipantIds(any());
        verify(inventoryESService).send(any(Request.class));

        clearInvocations(inventoryESService, cpiFetcherService);
        Map<String, List<Object>> cachedResult = invokeIdsLists(params);

        assertSame(firstResult, cachedResult);
        verifyNoInteractions(inventoryESService, cpiFetcherService);
    }

    /** Verifies idsLists returns empty identifiers without calling CPI when no participants exist. */
    @Test
    void returnsEmptyIdentifierListsWhenNoParticipantsExist() throws Exception {
        stubParticipantOverview(List.of());

        Map<String, List<Object>> result = invokeIdsLists(
                Map.of("cpi_batch_size", 25, "use_cache", true));

        assertEquals(Map.of("participantIds", List.of(), "associatedIds", List.of()), result);
        verifyNoInteractions(cpiFetcherService);
        assertEquals(0, cache.estimatedSize());
    }

    /**
     * Verifies a CPI failure does not discard identifiers obtained from OpenSearch and that the
     * fallback result is still cached.
     */
    @Test
    void keepsParticipantIdsWhenCpiIsUnavailable() throws Exception {
        stubParticipantOverview(participantRows("P1"));
        when(cpiFetcherService.fetchAssociatedParticipantIds(any()))
                .thenThrow(new IOException("CPI unavailable"));

        Map<String, List<Object>> result = invokeIdsLists(
                Map.of("cpi_batch_size", 10, "use_cache", true));

        assertEquals(List.of("P1"), result.get("participantIds"));
        assertEquals(List.of(), result.get("associatedIds"));
        assertEquals(1, cache.estimatedSize());
    }

    /** Verifies a failed asynchronous CPI batch is skipped while idsLists still returns safely. */
    @Test
    void skipsFailedAsynchronousCpiBatches() throws Exception {
        stubParticipantOverview(participantRows("P1"));
        when(cpiFetcherService.fetchAssociatedParticipantIds(any()))
                .thenThrow(new AssertionError("worker failed"));

        Map<String, List<Object>> result = invokeIdsLists(
                Map.of("cpi_batch_size", 1, "use_cache", true));

        assertEquals(List.of(), result.get("participantIds"));
        assertEquals(List.of(), result.get("associatedIds"));
        assertEquals(1, cache.estimatedSize());
    }

    /**
     * Verifies use_cache=false ignores an existing entry, recomputes identifiers, and replaces the
     * entry with the fresh result.
     */
    @Test
    void recomputesAndRefreshesCacheWhenCacheReadsAreDisabled() throws Exception {
        Map<String, Object> params = Map.of("cpi_batch_size", 10, "use_cache", false);
        String cacheKey = "idsLists" + invokePrivate(
                "generateCacheKey", new Class<?>[]{Map.class}, params);
        Map<String, List<Object>> sentinel = Map.of(
                "participantIds", List.of("STALE"), "associatedIds", List.of());
        cache.put(cacheKey, sentinel);
        stubParticipantOverview(participantRows("P1"));
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenReturn(List.of());

        Map<String, List<Object>> result = invokeIdsLists(params);

        assertEquals(List.of("P1"), result.get("participantIds"));
        assertSame(result, cache.getIfPresent(cacheKey));
        verify(inventoryESService).collectPage(
                any(Request.class), anyMap(), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0));
    }

    /** Verifies a non-map cache value is ignored and replaced by a valid idsLists response. */
    @Test
    void ignoresInvalidCachedValues() throws Exception {
        Map<String, Object> params = Map.of("cpi_batch_size", 10, "use_cache", true);
        String cacheKey = "idsLists" + invokePrivate(
                "generateCacheKey", new Class<?>[]{Map.class}, params);
        cache.put(cacheKey, "invalid");
        stubParticipantOverview(participantRows("P1"));
        when(cpiFetcherService.fetchAssociatedParticipantIds(any())).thenReturn(List.of());

        Map<String, List<Object>> result = invokeIdsLists(params);

        assertEquals(List.of("P1"), result.get("participantIds"));
        assertSame(result, cache.getIfPresent(cacheKey));
    }

    /**
     * Verifies findParticipantIdsInList delegates the supplied identifiers to the list-query
     * builder and retrieves every matching participant from the participant index.
     */
    @Test
    void findsParticipantIdsUsingTheListQuery() throws Exception {
        Map<String, Object> params = Map.of("participant_id", List.of("PID-1", "PID-2"));
        Map<String, Object> query = new HashMap<>(Map.of("query", Map.of("match_all", Map.of())));
        List<Map<String, Object>> expected = List.of(
                Map.of("participant_id", "P1", "study_id", "STUDY-A"),
                Map.of("participant_id", "P2", "study_id", "STUDY-B"));
        when(inventoryESService.buildListQuery(params, Set.of(), false)).thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0))).thenReturn(expected);

        List<Map<String, Object>> result = invokeFindParticipantIdsInList(params);

        assertSame(expected, result);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).collectPage(
                requestCaptor.capture(), eq(query), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0));
        assertEquals(PARTICIPANTS_ENDPOINT, requestCaptor.getValue().getEndpoint());
    }

    /** Verifies findParticipantIdsInList propagates OpenSearch query-construction failures. */
    @Test
    void propagatesParticipantListQueryFailures() throws Exception {
        Map<String, Object> params = Map.of("participant_id", List.of("PID-1"));
        Map<String, Object> query = new HashMap<>();
        when(inventoryESService.buildListQuery(params, Set.of(), false)).thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenThrow(new IOException("OpenSearch unavailable"));

        IOException exception = assertThrows(IOException.class,
                () -> invokeFindParticipantIdsInList(params));

        assertEquals("OpenSearch unavailable", exception.getMessage());
    }

    private void stubParticipantOverview(List<Map<String, Object>> participants) throws IOException {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), eq("nested_filters"),
                eq("participants_table"))).thenReturn(new HashMap<>());
        when(inventoryESService.collectPage(
                any(Request.class), anyMap(), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0))).thenReturn(participants);
    }

    private static List<Map<String, Object>> participantRows(String... participantIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < participantIds.length; index++) {
            rows.add(new HashMap<>(Map.of(
                    "id", "PID-" + (index + 1),
                    "participant_id", participantIds[index],
                    "study_id", "STUDY-A")));
        }
        return rows;
    }

    private static JsonObject participantSearchResponse(
            String id, String participantId, String studyId) {
        JsonObject source = new JsonObject();
        source.addProperty("id", id);
        source.addProperty("participant_id", participantId);
        source.addProperty("study_id", studyId);
        JsonObject hit = new JsonObject();
        hit.add("_source", source);
        JsonArray hits = new JsonArray();
        hits.add(hit);
        JsonObject hitsObject = new JsonObject();
        hitsObject.add("hits", hits);
        JsonObject response = new JsonObject();
        response.add("hits", hitsObject);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Object>> invokeIdsLists(Map<String, Object> params) throws Exception {
        return invokePrivate("idsLists", new Class<?>[]{Map.class}, params);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeFindParticipantIdsInList(Map<String, Object> params)
            throws Exception {
        return invokePrivate("findParticipantIdsInList", new Class<?>[]{Map.class}, params);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivate(String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return (T) method.invoke(dataFetcher, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void setPrivateField(String name, Object value) throws Exception {
        Field field = PrivateESDataFetcher.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dataFetcher, value);
    }
}
