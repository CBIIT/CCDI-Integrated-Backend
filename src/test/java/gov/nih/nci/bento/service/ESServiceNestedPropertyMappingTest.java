package gov.nih.nci.bento.service;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ESServiceNestedPropertyMappingTest {

    @Test
    void collectPageMapsSelectedFieldsFromNestedFilterArrays() throws Exception {
        ESService service = mock(ESService.class, CALLS_REAL_METHODS);
        var response = JsonParser.parseString("""
            {
              "hits": {
                "hits": [{
                  "_source": {
                    "id": "participant-1",
                    "sample_diagnosis_genetic_analysis_file_filters": [{
                      "diagnosis": "Rhabdomyosarcoma",
                      "sample_anatomic_site": ["brain"],
                      "unused_internal_field": "not returned"
                    }]
                  }
                }]
              }
            }
            """).getAsJsonObject();

        List<Map<String, Object>> properties = List.of(
            Map.of("gqlName", "id", "osName", "id"),
            Map.of(
                "gqlName", "clinical_filters",
                "osName", "sample_diagnosis_genetic_analysis_file_filters",
                "nested", List.of(
                    Map.of("gqlName", "diagnosis", "osName", "diagnosis"),
                    Map.of("gqlName", "anatomic_site", "osName", "sample_anatomic_site")
                )
            )
        );

        List<Map<String, Object>> page = service.collectPage(response, properties, 10);

        assertEquals("participant-1", page.get(0).get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters =
            (List<Map<String, Object>>) page.get(0).get("clinical_filters");
        assertEquals("Rhabdomyosarcoma", filters.get(0).get("diagnosis"));
        assertEquals(List.of("brain"), filters.get(0).get("anatomic_site"));
        assertFalse(filters.get(0).containsKey("unused_internal_field"));
    }
}
