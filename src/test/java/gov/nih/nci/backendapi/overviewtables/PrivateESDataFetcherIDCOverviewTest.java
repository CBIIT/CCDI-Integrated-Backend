package gov.nih.nci.backendapi.overviewtables;

import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.Request;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateESDataFetcherIDCOverviewTest {
    private InventoryESService service;
    private GraphQL graphQL;
    private Map<String, Object> searchQuery;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(InventoryESService.class);
        PrivateESDataFetcher fetcher = new PrivateESDataFetcher(service);
        var schema = new SchemaParser().parse(getClass().getResourceAsStream(
                "/graphql/ccdi-portal-private-es.graphql"));
        graphQL = GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(
                schema, fetcher.buildRuntimeWiring())).build();
        searchQuery = new HashMap<>();
        when(service.buildFacetFilterQuery(anyMap(), anySet(),
                eq(Set.of("first", "offset", "order_by", "sort_direction")),
                eq(Set.of()), eq("nested_filters"), eq("idc_table")))
                .thenReturn(searchQuery);
    }

    @Test
    void filtersSortsAndPagesIDCRecordsAndReturnsScalarImagingColumns() throws Exception {
        Map<String, Object> row = new HashMap<>(Map.of(
                "id", "https://viewer.example/studies/1/series/2",
                "slim_url", "https://viewer.example/studies/1/series/2",
                "study_uid", "1",
                "series_uid", "2",
                "study_id", "STUDY-1",
                "participant_id", List.of("P-1", "P-2"),
                "sample_id", List.of("S-1", "S-2"),
                "image_modality", List.of("SM"),
                "magnification", List.of("40"),
                "fixation_embedding_method", List.of("FFPE")));
        row.put("staining_method", List.of("H&E"));
        when(service.collectPage(any(Request.class), eq(searchQuery), any(String[][].class), eq(2), eq(3)))
                .thenReturn(List.of(row));

        ExecutionResult result = graphQL.execute("""
                { idcTableOverview(race: ["White"], sex_at_birth: ["Female"],
                    diagnosis: ["Neuroblastoma"], first: 2, offset: 3,
                    order_by: "study_uid", sort_direction: "DESC") {
                    id slim_url study_uid series_uid study_id participant_id sample_id
                    image_modality magnification fixation_embedding_method staining_method
                } }
                """);

        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<String, Object> data = result.getData();
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("idcTableOverview");
        Map<String, Object> actual = records.get(0);
        assertEquals(11, actual.size());
        assertEquals("1", actual.get("study_uid"));
        assertEquals(List.of("P-1", "P-2"), actual.get("participant_id"));
        assertEquals(List.of("S-1", "S-2"), actual.get("sample_id"));
        assertEquals("SM", actual.get("image_modality"));
        assertEquals("40", actual.get("magnification"));
        assertEquals("FFPE", actual.get("fixation_embedding_method"));
        assertEquals("H&E", actual.get("staining_method"));
        assertEquals(Map.of("study_uid", "desc"), searchQuery.get("sort"));
        assertEquals(Map.of("includes", actual.keySet()), searchQuery.get("_source"));

        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(service).collectPage(request.capture(), eq(searchQuery), any(String[][].class), eq(2), eq(3));
        assertEquals("/idc_table/_search", request.getValue().getEndpoint());
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(service).buildFacetFilterQuery(params.capture(), anySet(), anySet(),
                eq(Set.of()), eq("nested_filters"), eq("idc_table"));
        assertEquals(List.of("White"), params.getValue().get("race"));
        assertEquals(List.of("Female"), params.getValue().get("sex_at_birth"));
        assertEquals(List.of("Neuroblastoma"), params.getValue().get("diagnosis"));
    }

    @Test
    void handlesScalarAndEmptyImagingPropertiesAndDefaultsToURLSorting() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("image_modality", "SM");
        row.put("magnification", List.of());
        when(service.collectPage(any(Request.class), eq(searchQuery), any(String[][].class), eq(10), eq(0)))
                .thenReturn(List.of(row));

        ExecutionResult result = graphQL.execute("""
                { idcTableOverview {
                    image_modality magnification fixation_embedding_method staining_method
                } }
                """);

        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<String, Object> data = result.getData();
        Map<String, Object> actual = ((List<Map<String, Object>>) data.get("idcTableOverview")).get(0);
        assertEquals("SM", actual.get("image_modality"));
        assertNull(actual.get("magnification"));
        assertNull(actual.get("fixation_embedding_method"));
        assertNull(actual.get("staining_method"));
        assertEquals(Map.of("slim_url", "asc"), searchQuery.get("sort"));
    }
}
