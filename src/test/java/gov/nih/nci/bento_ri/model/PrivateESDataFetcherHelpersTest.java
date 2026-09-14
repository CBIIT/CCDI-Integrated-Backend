package gov.nih.nci.bento_ri.model;

import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Focused unit tests for deterministic helper behavior in {@link PrivateESDataFetcher}.
 * The cases are adapted from the CCDI Hub backend 2.12.0 test suite.
 */
class PrivateESDataFetcherHelpersTest {

    private PrivateESDataFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        fetcher = new PrivateESDataFetcher(mock(InventoryESService.class));
    }

    @Test
    void escapeWildcard_escapesOpenSearchMetacharacters() throws Exception {
        assertEquals("alpha\\*beta\\?gamma\\\\delta", invoke(
                "escapeWildcard", new Class<?>[] {String.class}, "alpha*beta?gamma\\delta"));
        assertEquals("", invoke("escapeWildcard", new Class<?>[] {String.class}, (Object) null));
    }

    @Test
    void paginate_returnsRequestedWindowAndEmptyListPastEnd() throws Exception {
        assertEquals(List.of("b", "c"), invoke(
                "paginate", new Class<?>[] {List.class, int.class, int.class},
                List.of("a", "b", "c"), 10, 1));
        assertEquals(List.of(), invoke(
                "paginate", new Class<?>[] {List.class, int.class, int.class},
                List.of("a"), 10, 1));
    }

    @Test
    void mapSortOrder_validatesFieldAndDirection() throws Exception {
        Map<String, String> mapping = Map.of("name", "file_name.keyword");

        assertEquals(Map.of("file_name.keyword", "desc"), invoke(
                "mapSortOrder",
                new Class<?>[] {String.class, String.class, String.class, Map.class},
                "name", "desc", "file_id", mapping));
        assertEquals(Map.of("file_id", "asc"), invoke(
                "mapSortOrder",
                new Class<?>[] {String.class, String.class, String.class, Map.class},
                "unknown", "sideways", "file_id", mapping));
    }

    @Test
    void mapSortOrderWithMetadata_buildsNestedSortAndSafeFallback() throws Exception {
        Map<String, Map<String, Object>> mapping = Map.of(
                "diagnosis",
                Map.of("isNested", true, "osName", "diagnosis", "path", "nested_filters"));

        assertEquals(
                Map.of("nested_filters.diagnosis", Map.of("nested_path", "nested_filters", "order", "desc")),
                invoke(
                        "mapSortOrderWithMetadata",
                        new Class<?>[] {String.class, String.class, String.class, Map.class},
                        "diagnosis", "DESC", "participant_id", mapping));
        assertEquals(Map.of("participant_id", "asc"), invoke(
                "mapSortOrderWithMetadata",
                new Class<?>[] {String.class, String.class, String.class, Map.class},
                "diagnosis", "desc", "participant_id", null));
    }

    @Test
    void mapProperties_preservesGraphQlToOpenSearchMapping() throws Exception {
        String[][] mapped = (String[][]) invoke(
                "mapProperties", new Class<?>[] {List.class},
                List.of(
                        Map.of("gqlName", "participantId", "osName", "participant_id"),
                        Map.of("gqlName", "studyId", "osName", "study_id")));

        assertArrayEquals(new String[] {"participantId", "participant_id"}, mapped[0]);
        assertArrayEquals(new String[] {"studyId", "study_id"}, mapped[1]);
    }

    @Test
    void idHelpers_handleNullEmptyAndMixedValues() throws Exception {
        assertFalse((Boolean) invoke("hasUsableIds", new Class<?>[] {List.class}, (Object) null));
        assertFalse((Boolean) invoke("hasUsableIds", new Class<?>[] {List.class}, List.of("")));
        assertTrue((Boolean) invoke("hasUsableIds", new Class<?>[] {List.class}, List.of("p1")));
        assertEquals(List.of("17", "p1"), invoke(
                "castIdList", new Class<?>[] {Object.class}, Arrays.asList(17, null, "p1")));
        assertEquals(List.of(), invoke("castIdList", new Class<?>[] {Object.class}, "p1"));
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(fetcher, args);
    }
}
