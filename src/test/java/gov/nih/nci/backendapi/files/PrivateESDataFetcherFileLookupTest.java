package gov.nih.nci.backendapi.files;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gov.nih.nci.bento.service.ESService;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivateESDataFetcherFileLookupTest {

    private static final String FILES_ENDPOINT = "/files_table/_search";
    private static final Set<String> FILENAME_EXCLUDED_ARGUMENTS = Set.of(
            "first", "offset", "order_by", "sort_direction", "filename");
    private static final Set<String> FILENAME_SEARCH_FIELDS = Set.of(
            "file_name", "data_category", "file_description", "file_type", "file_access",
            "study_id", "participant_id", "sample_id", "guid", "md5sum",
            "library_selection", "library_source_material", "library_strategy",
            "library_source_molecule", "file_mapping_level", "anatomic_site",
            "sample_tumor_status", "tumor_spatial_extent", "sample_description",
            "consent_codes", "fixation_embedding_method", "staining_method");
    private static final List<List<String>> FILENAME_PROPERTIES = pairs(
            "id", "id",
            "file_id", "file_id",
            "guid", "guid",
            "file_name", "file_name",
            "data_category", "data_category",
            "file_description", "file_description",
            "file_type", "file_type",
            "file_size", "file_size",
            "library_selection", "library_selection",
            "library_source_material", "library_source_material",
            "library_source_molecule", "library_source_molecule",
            "library_strategy", "library_strategy",
            "file_mapping_level", "file_mapping_level",
            "file_access", "file_access",
            "anatomic_site", "anatomic_site",
            "participant_age_at_collection", "participant_age_at_collection",
            "sample_tumor_status", "sample_tumor_status",
            "tumor_spatial_extent", "tumor_spatial_extent",
            "sample_description", "sample_description",
            "percent_tumor", "percent_tumor",
            "percent_necrosis", "percent_necrosis",
            "consent_codes", "consent_codes",
            "fixation_embedding_method", "fixation_embedding_method",
            "staining_method", "staining_method",
            "study_id", "study_id",
            "participant_id", "participant_id",
            "sample_id", "sample_id",
            "md5sum", "md5sum",
            "files", "files");
    private static final List<List<String>> FILE_DETAIL_PROPERTIES = pairs(
            "id", "id",
            "file_id", "file_id",
            "guid", "datamodel_dcf_indexd_guid",
            "file_name", "file_name",
            "library_selection", "library_selection",
            "library_source_material", "library_source_material",
            "library_source_molecule", "library_source_molecule",
            "library_strategy", "library_strategy",
            "file_mapping_level", "file_mapping_level",
            "file_access", "file_access",
            "study_name", "study_name",
            "dbgap_accession", "dbgap_accession",
            "sample_id", "sample_id",
            "participant_id", "participant_id",
            "study_id", "study_id",
            "file_type", "file_type",
            "file_size", "file_size",
            "md5sum", "md5sum");
    private static final List<List<String>> MANIFEST_PROPERTIES = pairs(
            "guid", "datamodel_dcf_indexd_guid",
            "file_name", "file_name",
            "participant_id", "participant_id",
            "md5sum", "md5sum");

    @Mock
    private InventoryESService inventoryESService;

    private PrivateESDataFetcher dataFetcher;

    @BeforeEach
    void setUp() throws Exception {
        dataFetcher = new PrivateESDataFetcher(inventoryESService);
    }

    /**
     * Verifies filename search adds case-insensitive wildcards across searchable file fields,
     * applies defaults, returns the exact total, and uses the complete result projection.
     */
    @Test
    void returnsMatchingFilenamesAndTotalCount() throws Exception {
        Map<String, Object> params = Map.of("filename", "rna");
        Map<String, Object> baseQuery = Map.of("query", Map.of("match_all", Map.of()));
        List<Map<String, Object>> page = List.of(Map.of("file_id", "FILE-1"));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(FILENAME_EXCLUDED_ARGUMENTS), eq(Set.of()),
                eq("nested_filters"), eq("files_table"))).thenReturn(baseQuery);
        when(inventoryESService.send(any(Request.class))).thenReturn(countResponse(37));
        when(inventoryESService.collectPage(
                any(Request.class), anyMap(), any(String[][].class), eq(10), eq(0)))
                .thenReturn(page);

        Map<String, Object> result = invoke("getFilenames", params);

        assertSame(page, result.get("files"));
        assertEquals(37, result.get("totalCount"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Request> pageRequestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService).collectPage(
                pageRequestCaptor.capture(), queryCaptor.capture(), propertiesCaptor.capture(),
                eq(10), eq(0));
        assertEquals(FILES_ENDPOINT, pageRequestCaptor.getValue().getEndpoint());
        assertEquals(FILENAME_PROPERTIES, propertyPairs(propertiesCaptor.getValue()));

        Map<String, Object> query = queryCaptor.getValue();
        assertEquals(Map.of("file_id", "asc"), query.get("sort"));
        JsonObject queryJson = JsonParser.parseString(
                new com.google.gson.Gson().toJson(query)).getAsJsonObject();
        JsonArray wildcardClauses = queryJson.getAsJsonObject("query")
                .getAsJsonObject("bool").getAsJsonArray("must").get(1).getAsJsonObject()
                .getAsJsonObject("bool").getAsJsonArray("should");
        assertWildcardSearch(wildcardClauses, "rna");

        ArgumentCaptor<Request> countRequestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(countRequestCaptor.capture());
        assertEquals(FILES_ENDPOINT, countRequestCaptor.getValue().getEndpoint());
        JsonObject countQuery = requestBody(countRequestCaptor.getValue());
        assertEquals(0, countQuery.get("size").getAsInt());
        assertTrue(countQuery.get("track_total_hits").getAsBoolean());
    }

    /** Verifies filename search is inserted ahead of existing facet filters. */
    @Test
    void combinesFilenameSearchWithFacetFilters() throws Exception {
        Map<String, Object> params = pagingParams("file_name", "DESC", 5, 2);
        params.put("filename", "bam");
        Map<String, Object> originalFilter = Map.of("terms", Map.of("file_type", List.of("BAM")));
        Map<String, Object> baseQuery = Map.of("query", Map.of("bool", Map.of(
                "should", List.of(Map.of("bool", Map.of("filter", List.of(originalFilter)))))));
        when(inventoryESService.buildFacetFilterQuery(
                eq(params), anySet(), eq(FILENAME_EXCLUDED_ARGUMENTS), eq(Set.of()),
                eq("nested_filters"), eq("files_table"))).thenReturn(baseQuery);
        when(inventoryESService.send(any(Request.class))).thenReturn(countResponse(1));
        when(inventoryESService.collectPage(
                any(Request.class), anyMap(), any(String[][].class), eq(5), eq(2)))
                .thenReturn(List.of());

        invoke("getFilenames", params);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(inventoryESService).collectPage(
                any(Request.class), queryCaptor.capture(), any(String[][].class), eq(5), eq(2));
        JsonObject query = JsonParser.parseString(
                new com.google.gson.Gson().toJson(queryCaptor.getValue())).getAsJsonObject();
        var filters = query.getAsJsonObject("query").getAsJsonObject("bool")
                .getAsJsonArray("should").get(0).getAsJsonObject()
                .getAsJsonObject("bool").getAsJsonArray("filter");
        assertEquals(2, filters.size());
        assertTrue(filters.get(0).toString().contains("*bam*"));
        assertEquals(JsonParser.parseString(new com.google.gson.Gson().toJson(originalFilter)),
                filters.get(1));
        assertEquals(Map.of("file_name", "desc"), queryCaptor.getValue().get("sort"));
    }

    /** Verifies failures from the filename query are exposed as resolver IOExceptions. */
    @Test
    void wrapsFilenameSearchFailures() throws Exception {
        when(inventoryESService.buildFacetFilterQuery(
                anyMap(), anySet(), anySet(), anySet(), any(String.class), any(String.class)))
                .thenThrow(new IOException("query failed"));

        IOException exception = assertThrows(
                IOException.class, () -> invoke("getFilenames", Map.of()));

        assertTrue(exception.getMessage().contains("Error in getFilenames: query failed"));
    }

    /** Verifies manifest export filters by document IDs and requests only manifest fields. */
    @Test
    void returnsFileManifestRowsForRequestedIds() throws Exception {
        Map<String, Object> params = pagingParams("", "ASC", 20, 4);
        params.put("id", List.of("FILE-GUID-1", "FILE-GUID-2"));
        Map<String, Object> query = new HashMap<>();
        List<Map<String, Object>> expected = List.of(Map.of(
                "guid", "INDEXD-1", "file_name", "file.bam", "md5sum", "abc"));
        when(inventoryESService.buildListQuery(
                eq(Map.of("id", params.get("id"))), eq(Set.of()), eq(false)))
                .thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class), eq(20), eq(4)))
                .thenReturn(expected);

        List<Map<String, Object>> result = invoke("filesManifestInList", params);

        assertSame(expected, result);
        assertEquals(Map.of("includes", Set.of(
                "datamodel_dcf_indexd_guid", "file_name", "participant_id", "md5sum")),
                query.get("_source"));
        assertCollectedPage(query, 20, 4, MANIFEST_PROPERTIES);
    }

    /** Verifies file-detail lookup filters IDs, maps sorting, projects all fields, and caps page size. */
    @Test
    void returnsSortedFileDetailsAndCapsPageSize() throws Exception {
        Map<String, Object> params = pagingParams(
                "library_selection", "desc", ESService.MAX_ES_SIZE + 1, 8);
        params.put("id", List.of("FILE-GUID-1"));
        Map<String, Object> query = new HashMap<>();
        List<Map<String, Object>> expected = List.of(Map.of("file_id", "FILE-1"));
        when(inventoryESService.buildListQuery(
                eq(Map.of("id", params.get("id"))), eq(Set.of()), eq(false)))
                .thenReturn(query);
        when(inventoryESService.collectPage(
                any(Request.class), eq(query), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(8))).thenReturn(expected);

        List<Map<String, Object>> result = invoke("filesInList", params);

        assertSame(expected, result);
        assertEquals(Map.of("library_selection", "desc"), query.get("sort"));
        assertCollectedPage(query, ESService.MAX_ES_SIZE, 8, FILE_DETAIL_PROPERTIES);
    }

    /** Verifies participant GUID and display-ID lookups query their correct fields and deduplicate files. */
    @Test
    void resolvesFileIdsFromParticipantIdentifiers() throws Exception {
        List<String> pids = List.of("PARTICIPANT-GUID-1");
        List<String> participantIds = List.of("PARTICIPANT-1");
        Map<String, Object> pidQuery = Map.of("query", "pid");
        Map<String, Object> displayQuery = Map.of("query", "participant_id");
        when(inventoryESService.buildFilesTableIDsQuery("pid", pids)).thenReturn(pidQuery);
        when(inventoryESService.buildFilesTableIDsQuery("participant_id", participantIds))
                .thenReturn(displayQuery);
        when(inventoryESService.collectPage(
                any(Request.class), eq(pidQuery), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenReturn(List.of(
                        Map.of("file_id", "FILE-1"),
                        Map.of("file_id", "", "id", "FILE-GUID-2")));
        when(inventoryESService.collectPage(
                any(Request.class), eq(displayQuery), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenReturn(List.of(Map.of("file_id", "FILE-1"), Map.of("file_id", "FILE-3")));

        List<String> result = invoke("fileIDsFromList", Map.of(
                "pid", pids,
                "participant_ids", participantIds));

        assertEquals(List.of("FILE-1", "FILE-GUID-2", "FILE-3"), result);
    }

    /** Verifies diagnosis lookup returns embedded file IDs without an unnecessary files-table query. */
    @Test
    void returnsEmbeddedDiagnosisFileIds() throws Exception {
        List<String> diagnosisIds = List.of("DIAGNOSIS-GUID-1");
        Map<String, Object> query = Map.of("query", "diagnosis");
        JsonObject response = new JsonObject();
        when(inventoryESService.buildGetFileIDsQuery(diagnosisIds)).thenReturn(query);
        when(inventoryESService.send(any(Request.class))).thenReturn(response);
        when(inventoryESService.collectFileIDs(response)).thenReturn(List.of("FILE-1", "FILE-2"));

        List<String> result = invoke(
                "fileIDsFromList", Map.of("diagnosis_ids", diagnosisIds));

        assertEquals(List.of("FILE-1", "FILE-2"), result);
        verify(inventoryESService, never()).buildFilesTableIDsQuery(any(String.class), any());
    }

    /** Verifies diagnosis lookup falls back to the files table when no embedded IDs exist. */
    @Test
    void fallsBackToFilesTableForDiagnosisFileIds() throws Exception {
        List<String> diagnosisIds = List.of("DIAGNOSIS-GUID-1");
        List<String> participantPids = List.of("PARTICIPANT-GUID-9");
        Map<String, Object> embeddedQuery = Map.of("query", "diagnosis");
        Map<String, Object> filesQuery = Map.of("query", "files");
        JsonObject response = JsonParser.parseString("""
                {"hits":{"hits":[{"_source":{
                  "pid":"PARTICIPANT-GUID-9"
                }}]}}
                """).getAsJsonObject();
        when(inventoryESService.buildGetFileIDsQuery(diagnosisIds)).thenReturn(embeddedQuery);
        when(inventoryESService.send(any(Request.class))).thenReturn(response);
        when(inventoryESService.collectFileIDs(response)).thenCallRealMethod();
        when(inventoryESService.buildFilesTableIDsQuery("pid", participantPids))
                .thenReturn(filesQuery);
        when(inventoryESService.collectPage(
                any(Request.class), eq(filesQuery), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenReturn(List.of(Map.of("file_id", "FILE-1")));

        List<String> result = invoke(
                "fileIDsFromList", Map.of("diagnosis_ids", diagnosisIds));

        assertEquals(List.of("FILE-1"), result);
        ArgumentCaptor<Request> diagnosisRequestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(diagnosisRequestCaptor.capture());
        assertEquals(Set.of("files", "pid"),
                new com.google.gson.Gson().fromJson(
                        requestBody(diagnosisRequestCaptor.getValue()).get("_source"), Set.class));
    }

    /** Verifies study lookup uses embedded study files when they are available. */
    @Test
    void returnsEmbeddedStudyFileIds() throws Exception {
        List<String> studyIds = List.of("STUDY-GUID-1");
        Map<String, Object> query = Map.of("query", "study");
        JsonObject response = new JsonObject();
        when(inventoryESService.buildGetFileIDsQuery(studyIds)).thenReturn(query);
        when(inventoryESService.send(any(Request.class))).thenReturn(response);
        when(inventoryESService.collectFileIDs(response)).thenReturn(List.of("FILE-1"));

        List<String> result = invoke("fileIDsFromList", Map.of("study_ids", studyIds));

        assertEquals(List.of("FILE-1"), result);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(requestCaptor.capture());
        assertEquals("/studies_table/_search", requestCaptor.getValue().getEndpoint());
    }

    /** Verifies sample document IDs are resolved to sample IDs before querying the files table. */
    @Test
    void resolvesSampleIdsBeforeFindingFiles() throws Exception {
        List<String> suppliedIds = List.of("SAMPLE-GUID-1");
        JsonObject sampleResponse = JsonParser.parseString("""
                {"hits":{"hits":[
                  {"_source":{"sample_id":["SAMPLE-1","SAMPLE-2"]}},
                  {"_source":{"sample_id":"SAMPLE-3"}},
                  {"_source":{}}
                ]}}
                """).getAsJsonObject();
        List<String> resolvedIds = List.of("SAMPLE-GUID-1", "SAMPLE-1", "SAMPLE-2", "SAMPLE-3");
        Map<String, Object> filesQuery = Map.of("query", "samples");
        when(inventoryESService.send(any(Request.class))).thenReturn(sampleResponse);
        when(inventoryESService.buildFilesTableIDsQuery("sample_id", resolvedIds))
                .thenReturn(filesQuery);
        when(inventoryESService.collectPage(
                any(Request.class), eq(filesQuery), any(String[][].class),
                eq(ESService.MAX_ES_SIZE), eq(0)))
                .thenReturn(List.of(Map.of("file_id", "FILE-1")));

        List<String> result = invoke("fileIDsFromList", Map.of("sample_ids", suppliedIds));

        assertEquals(List.of("FILE-1"), result);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(inventoryESService).send(requestCaptor.capture());
        assertEquals("/samples_table/_search", requestCaptor.getValue().getEndpoint());
    }

    /** Verifies explicitly supplied file IDs are returned directly without OpenSearch calls. */
    @Test
    void returnsSuppliedFileIdsDirectly() throws Exception {
        List<String> fileIds = List.of("FILE-1", "FILE-2");

        List<String> result = invoke("fileIDsFromList", Map.of("file_ids", fileIds));

        assertEquals(fileIds, result);
        verifyNoInteractions(inventoryESService);
    }

    /** Verifies absent or unusable identifier arguments produce an empty result. */
    @Test
    void returnsNoFileIdsForUnusableArguments() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("pid", List.of(""));
        params.put("participant_ids", "not-a-list");
        List<Object> nullFileId = new ArrayList<>();
        nullFileId.add(null);
        params.put("file_ids", nullFileId);

        List<String> result = invoke("fileIDsFromList", params);

        assertEquals(List.of(), result);
        verifyNoInteractions(inventoryESService);
    }

    private void assertCollectedPage(
            Map<String, Object> query,
            int pageSize,
            int offset,
            List<List<String>> expectedProperties) throws IOException {
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<String[][]> propertiesCaptor = ArgumentCaptor.forClass(String[][].class);
        verify(inventoryESService).collectPage(
                requestCaptor.capture(), eq(query), propertiesCaptor.capture(),
                eq(pageSize), eq(offset));
        assertEquals(FILES_ENDPOINT, requestCaptor.getValue().getEndpoint());
        assertEquals(expectedProperties, propertyPairs(propertiesCaptor.getValue()));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Map<String, Object> params) throws Exception {
        Method method = PrivateESDataFetcher.class.getDeclaredMethod(methodName, Map.class);
        method.setAccessible(true);
        try {
            return (T) method.invoke(dataFetcher, params);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static Map<String, Object> pagingParams(
            String orderBy, String direction, int first, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_by", orderBy);
        params.put("sort_direction", direction);
        params.put("first", first);
        params.put("offset", offset);
        return params;
    }

    private static JsonObject countResponse(int count) {
        return JsonParser.parseString(
                "{\"hits\":{\"total\":{\"value\":" + count + "}}}").getAsJsonObject();
    }

    private static JsonObject requestBody(Request request) throws IOException {
        return JsonParser.parseString(EntityUtils.toString(request.getEntity())).getAsJsonObject();
    }

    private static void assertWildcardSearch(JsonArray wildcardClauses, String term) {
        Set<String> actualFields = new java.util.LinkedHashSet<>();
        for (var clause : wildcardClauses) {
            JsonObject wildcard = clause.getAsJsonObject().getAsJsonObject("wildcard");
            assertEquals(1, wildcard.size());
            var fieldEntry = wildcard.entrySet().iterator().next();
            actualFields.add(fieldEntry.getKey());
            JsonObject options = fieldEntry.getValue().getAsJsonObject();
            assertEquals("*" + term + "*", options.get("value").getAsString());
            assertTrue(options.get("case_insensitive").getAsBoolean());
        }
        assertEquals(FILENAME_SEARCH_FIELDS, actualFields);
    }

    private static List<List<String>> pairs(String... values) {
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            result.add(List.of(values[index], values[index + 1]));
        }
        return List.copyOf(result);
    }

    private static List<List<String>> propertyPairs(String[][] properties) {
        List<List<String>> result = new ArrayList<>();
        for (String[] property : properties) {
            result.add(List.of(property));
        }
        return result;
    }
}
