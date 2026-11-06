package gov.nih.nci.backendapi.files;

import gov.nih.nci.backendapi.support.InventoryESServiceTestSupport;
import gov.nih.nci.bento_ri.service.InventoryESService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryESServiceFileQueryTest {

    private InventoryESService service;

    @BeforeEach
    void setUp() throws Exception {
        service = InventoryESServiceTestSupport.newService();
    }

    @AfterEach
    void tearDown() throws Exception {
        InventoryESServiceTestSupport.closeClient(service);
    }

    /** Verifies files-table lookup targets the requested linkage field and projects both file ID forms. */
    @Test
    void buildsFilesTableIdQuery() {
        List<String> ids = List.of("PARTICIPANT-1", "PARTICIPANT-2");

        Map<String, Object> query = service.buildFilesTableIDsQuery("participant_id", ids);

        assertEquals(Set.of("id", "file_id"), query.get("_source"));
        assertEquals(Map.of("terms", Map.of("participant_id", ids)), query.get("query"));
        InventoryESServiceTestSupport.assertJsonRoundTrip(query);
    }
}
