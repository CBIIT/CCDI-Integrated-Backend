package gov.nih.nci.backendapi.transport;

import gov.nih.nci.bento.controller.IndexController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Unit test for IndexController
 * This is a lightweight unit test that doesn't load the full Spring context
 */
@ExtendWith(MockitoExtension.class)
public class IndexControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        // Create a standalone MockMvc without loading the full Spring context
        IndexController controller = new IndexController();
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void root_getReturnsIndexView() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("/index"));
    }

    @Test
    void ping_getReturnsPong() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/ping")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void ping_postReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/ping"))
                .andExpect(status().isMethodNotAllowed());
    }

}
