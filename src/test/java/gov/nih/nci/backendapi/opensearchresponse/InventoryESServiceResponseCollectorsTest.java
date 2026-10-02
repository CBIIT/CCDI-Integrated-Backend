package gov.nih.nci.backendapi.opensearchresponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InventoryESService} OpenSearch response parsing (no live cluster).
 */
class InventoryESServiceResponseCollectorsTest {

    private InventoryESService service;

    @BeforeEach
    void setUp() throws Exception {
        service = InventoryESServiceTestSupport.newService();
    }

    @AfterEach
    void tearDown() throws Exception {
        InventoryESServiceTestSupport.closeClient(service);
    }

    /** Verifies that flat term aggregations use each bucket's document count. */
    @Test
    void usesDocumentCountsForFlatCustomTerms() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("custom_terms_flat.json");

        Map<String, Integer> terms = service.collectCustomTerms(response, "facetAgg");

        assertEquals(2, terms.size());
        assertEquals(40919, terms.get("Active"));
        assertEquals(19703, terms.get("Completed"));
    }

    /** Verifies that nested term aggregations use the reverse-nested participant count. */
    @Test
    void usesReverseNestedCountsForNestedCustomTerms() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("custom_terms_nested.json");

        Map<String, Integer> terms = service.collectCustomTerms(response, "facetAgg");

        assertEquals(2, terms.size());
        assertEquals(6374, terms.get("Asian"));
        assertEquals(4100, terms.get("White"));
    }

    /** Verifies that embedded file arrays from every hit are flattened into one ID list. */
    @Test
    void flattensEmbeddedFileIdsFromAllHits() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("file_ids_hits.json");

        List<String> fileIds = service.collectFileIDs(response);

        assertEquals(List.of("ccdi-int-f001", "ccdi-int-f002", "ccdi-int-f003"), fileIds);
    }

    /** Verifies that the simple term collector extracts every bucket key. */
    @Test
    void extractsTermBucketKeys() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("terms_aggs.json");

        List<String> keys = service.collectTerms(response, "study_status");

        assertEquals(List.of("active", "completed"), keys);
    }

    /** Verifies that node-count aggregation buckets are returned under the requested node name. */
    @Test
    void returnsNodeCountBuckets() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("node_count_aggs.json");

        Map<String, JsonArray> aggs = service.collectNodeCountAggs(response, "race");

        assertEquals(2, aggs.get("race").size());
        assertEquals("Asian", aggs.get("race").get(0).getAsJsonObject().get("key").getAsString());
    }

    /** Verifies that range-count aggregation buckets are returned under the requested name. */
    @Test
    void returnsRangeCountBuckets() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("range_count_aggs.json");

        Map<String, JsonArray> aggs = service.collectRangCountAggs(response, "age_at_diagnosis");

        assertEquals(2, aggs.get("age_at_diagnosis").size());
        assertEquals("0 - 4", aggs.get("age_at_diagnosis").get(0).getAsJsonObject().get("key").getAsString());
    }

    /** Verifies that nested range statistics are collected from the inner aggregation. */
    @Test
    void returnsInnerRangeStatistics() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("range_stats_aggs.json");

        Map<String, JsonObject> aggs = service.collectRangAggs(response, "age_at_diagnosis");

        JsonObject stats = aggs.get("age_at_diagnosis");
        assertEquals(100, stats.get("count").getAsInt());
        assertEquals(5.0, stats.get("min").getAsDouble());
        assertEquals(65.0, stats.get("max").getAsDouble());
    }

    /** Verifies that multiple named term aggregation buckets can be collected. */
    @Test
    void returnsNamedTermBucketArrays() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("node_count_aggs.json");

        Map<String, JsonArray> aggs = service.collectTermAggs(response, new String[] {"race"});

        assertEquals(2, aggs.get("race").size());
    }

    /** Verifies that named range aggregation objects are preserved in the result map. */
    @Test
    void returnsNamedRangeAggregationObjects() {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("range_count_aggs.json");

        Map<String, JsonObject> aggs = service.collectRangeAggs(response, new String[] {"age_at_diagnosis"});

        assertTrue(aggs.get("age_at_diagnosis").has("buckets"));
    }

    /** Verifies that page collection recursively maps scalar and object source values. */
    @Test
    void mapsPageScalarsAndNestedObjects() throws IOException {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("search_hits_page.json");
        String[][] properties = {
            {"participant_id", "participant_id"},
            {"race", "race"},
            {"study", "study"}
        };

        List<Map<String, Object>> page = service.collectPage(response, properties, 10);

        assertEquals(2, page.size());
        assertEquals("ccdi-int-p001", page.get(0).get("participant_id"));
        assertEquals("Asian", page.get(0).get("race"));
        Object study = page.get(0).get("study");
        assertInstanceOf(Map.class, study);
        @SuppressWarnings("unchecked")
        Map<String, Object> studyMap = (Map<String, Object>) study;
        assertEquals("ccdi-int-study-1", studyMap.get("study_id"));
        assertEquals("CCDI Integration Fixture Study", studyMap.get("study_name"));
    }

    /** Verifies that page collection skips the offset and stops at the requested page size. */
    @Test
    void respectsPageSizeAndOffset() throws IOException {
        JsonObject response = InventoryESServiceTestSupport.loadResponseFixture("search_hits_page.json");
        String[][] properties = {{"participant_id", "participant_id"}};

        List<Map<String, Object>> page = service.collectPage(response, properties, null, 1, 1);

        assertEquals(1, page.size());
        assertEquals("ccdi-int-p002", page.get(0).get("participant_id"));
    }

    /**
     * Verifies the source-array, missing-value, and global-search highlight handling performed by
     * the project-owned response collector.
     */
    @Test
    void collectPageMapsGlobalSearchHighlightsAndMissingValues() throws IOException {
        JsonObject response = JsonParser.parseString("""
                {
                  "hits": {
                    "hits": [
                      {
                        "_source": {
                          "node": "participant",
                          "aliases": ["subject", "patient"],
                          "description": null
                        },
                        "highlight": {
                          "node": ["$participant$"],
                          "description": [],
                          "scalar": "not-an-array"
                        }
                      },
                      {
                        "_source": {"node": "study"},
                        "highlight": "not-an-object"
                      },
                      {
                        "_source": {"node": "file"}
                      }
                    ]
                  }
                }
                """).getAsJsonObject();
        String[][] properties = {
                {"node", "node"},
                {"aliases", "aliases"},
                {"description", "description"},
                {"missing", "missing"}
        };
        String[][] highlights = {
                {"highlight", "node"},
                {"descriptionHighlight", "description"},
                {"scalarHighlight", "scalar"},
                {"missingHighlight", "missing"}
        };

        List<Map<String, Object>> page = service.collectPage(
                response,
                properties,
                highlights,
                10,
                0
        );

        assertEquals(3, page.size());
        assertEquals(List.of("subject", "patient"), page.get(0).get("aliases"));
        assertNull(page.get(0).get("description"));
        assertNull(page.get(0).get("missing"));
        assertEquals("$participant$", page.get(0).get("highlight"));
        assertFalse(page.get(0).containsKey("descriptionHighlight"));
        assertFalse(page.get(0).containsKey("scalarHighlight"));
        assertFalse(page.get(0).containsKey("missingHighlight"));
        assertFalse(page.get(1).containsKey("highlight"));
        assertFalse(page.get(2).containsKey("highlight"));
    }

    /** Verifies that getJSonFromResponse reads and parses a normal response entity. */
    @Test
    void parsesResponseEntityBody() throws IOException {
        String body = "{\"count\":42}";
        Response response = mock(Response.class);
        when(response.getEntity()).thenReturn(new StringEntity(body, ContentType.APPLICATION_JSON));

        JsonObject parsed = service.getJSonFromResponse(response);

        assertNotNull(parsed);
        assertEquals(42, parsed.get("count").getAsInt());
    }

    /** Verifies that malformed aggregation envelopes fail instead of yielding misleading data. */
    @Test
    void rejectsMissingAggregationEnvelope() {
        JsonObject malformed = JsonParser.parseString("{}").getAsJsonObject();

        assertThrows(
                NullPointerException.class,
                () -> service.collectNodeCountAggs(malformed, "race"));
        assertThrows(
                NullPointerException.class,
                () -> service.collectRangCountAggs(malformed, "age_at_diagnosis"));
        assertThrows(
                NullPointerException.class,
                () -> service.collectRangAggs(malformed, "age_at_diagnosis"));
        assertThrows(
                NullPointerException.class,
                () -> service.collectTerms(malformed, "study_status"));
        assertThrows(
                NullPointerException.class,
                () -> service.collectCustomTerms(malformed, "facetAgg"));
        assertThrows(
                NullPointerException.class,
                () -> service.collectTermAggs(malformed, new String[] {"race"}));
        assertThrows(
                NullPointerException.class,
                () -> service.collectRangeAggs(malformed, new String[] {"age_at_diagnosis"}));
    }

    /** Verifies that a malformed search response without a hits envelope is rejected. */
    @Test
    void rejectsPageWithoutHitsEnvelope() {
        JsonObject malformed = JsonParser.parseString("{}").getAsJsonObject();

        assertThrows(
                NullPointerException.class,
                () -> service.collectPage(malformed, new String[][] {{"id", "id"}}, 10));
    }
}
