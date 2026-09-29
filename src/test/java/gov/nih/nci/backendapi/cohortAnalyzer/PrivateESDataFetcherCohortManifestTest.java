package gov.nih.nci.backendapi.cohortAnalyzer;

import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.Request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherCohortManifestTest {

    private static final String COHORT_MANIFEST_ENDPOINT =
            "/diagnoses_cohort_manifest/_search";

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
    }

    /** Verifies cohortManifest returns the requested cohort page with its public manifest fields. */
    @Test
    void returnsPaginatedCohortManifest() throws Exception {
        Map<String, Object> params = mutableMap(
                "order_by", "",
                "sort_direction", "asc",
                "first", 25,
                "offset", 5);
        Map<String, Object> query = new HashMap<>();
        List<Map<String, Object>> expected = List.of(Map.of(
                "id", "GUID-1", "participant_id", "P-1", "study_id", "STUDY-1"));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), anySet(), eq(Set.of()),
                eq("nested_filters"), eq("cohorts"))).thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class), eq(25), eq(5)))
                .thenReturn(expected);

        List<Map<String, Object>> result = invokeCohortManifest(params);

        assertSame(expected, result);
        assertFalse(query.containsKey("_source"));
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService).collectPage(
                requestCaptor.capture(), eq(query), propertiesCaptor.capture(), eq(25), eq(5));
        assertEquals(COHORT_MANIFEST_ENDPOINT, requestCaptor.getValue().getEndpoint());
        Set<String> actualFields = java.util.Arrays.stream(propertiesCaptor.getValue())
                .map(property -> property[0])
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "id", "participant_id", "study_id", "sex_at_birth", "race", "diagnosis"),
                actualFields);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeCohortManifest(Map<String, Object> params)
            throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod("cohortManifest", Map.class);
        method.setAccessible(true);
        try {
            return (List<Map<String, Object>>) method.invoke(dataFetcher, params);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            map.put((String) entries[index], entries[index + 1]);
        }
        return map;
    }
}
