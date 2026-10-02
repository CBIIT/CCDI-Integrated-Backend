package gov.nih.nci.backendapi.opensearchresponse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the response shapes accepted by {@link InventoryESService#collectFileIDs}.
 */
class InventoryESServiceFileIdCollectorTest {

    private InventoryESService service;

    @BeforeEach
    void setUp() throws Exception {
        service = InventoryESServiceTestSupport.newService();
    }

    @AfterEach
    void tearDown() throws Exception {
        InventoryESServiceTestSupport.closeClient(service);
    }

    /** Verifies that null, missing, and null-valued hit envelopes yield an empty ID list. */
    @Test
    void returnsEmptyListWhenHitsAreUnavailable() {
        assertTrue(service.collectFileIDs(null).isEmpty());
        assertTrue(service.collectFileIDs(json("{}")).isEmpty());
        assertTrue(service.collectFileIDs(json("{\"hits\":null}")).isEmpty());
        assertTrue(service.collectFileIDs(json("{\"hits\":{}}")).isEmpty());
        assertTrue(service.collectFileIDs(json("{\"hits\":{\"hits\":null}}")).isEmpty());
    }

    /** Verifies that malformed hits without a source document are skipped. */
    @Test
    void skipsHitsWithoutSourceDocuments() {
        JsonObject response = json("""
                {"hits":{"hits":[{}]}}
                """);

        assertTrue(service.collectFileIDs(response).isEmpty());
    }

    /** Verifies that an explicitly null source document is rejected as malformed. */
    @Test
    void rejectsNullSourceDocument() {
        JsonObject response = json("""
                {"hits":{"hits":[{"_source":null}]}}
                """);

        assertThrows(ClassCastException.class, () -> service.collectFileIDs(response));
    }

    /** Verifies that null entries in embedded file arrays are ignored. */
    @Test
    void ignoresNullEmbeddedFileIds() {
        JsonObject response = json("""
                {"hits":{"hits":[{"_source":{"files":["file-1",null,"file-2"]}}]}}
                """);

        assertEquals(List.of("file-1", "file-2"), service.collectFileIDs(response));
    }

    /** Verifies direct file_id collection and the legacy id fallback used by file documents. */
    @Test
    void collectsDirectAndFallbackFileIds() {
        JsonObject response = json("""
                {"hits":{"hits":[
                  {"_source":{"file_id":"file-1"}},
                  {"_source":{"file_id":null,"id":"file-2"}},
                  {"_source":{"id":"file-3"}}
                ]}}
                """);

        assertEquals(List.of("file-1", "file-2", "file-3"), service.collectFileIDs(response));
    }

    /** Verifies that empty, null, and wrongly typed identifier fields do not create IDs. */
    @Test
    void ignoresSourcesWithoutUsableFileIds() {
        JsonObject response = json("""
                {"hits":{"hits":[
                  {"_source":{"files":[]}},
                  {"_source":{"files":"not-an-array","file_id":null,"id":null}},
                  {"_source":{}}
                ]}}
                """);

        assertTrue(service.collectFileIDs(response).isEmpty());
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
