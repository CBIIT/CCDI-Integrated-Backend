package gov.nih.nci.backendapi.transport;

import gov.nih.nci.bento.controller.GraphQLController;
import gov.nih.nci.bento.graphql.BentoGraphQL;
import gov.nih.nci.bento.model.ConfigurationDAO;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GraphQLControllerTest {

    private static final String PRIVATE_GRAPHQL_PATH = "/v1/graphql/";
    private static final String PUBLIC_GRAPHQL_PATH = "/v1/public-graphql/";
    private static final String REQUEST_BODY =
            "{\"query\":\"query Example($id: ID) { example(id: $id) }\",\"variables\":{\"id\":\"abc\"}}";

    @Mock
    private ConfigurationDAO config;
    @Mock
    private BentoGraphQL bentoGraphQL;
    @Mock
    private GraphQL privateGraphQL;
    @Mock
    private GraphQL publicGraphQL;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GraphQLController(config, bentoGraphQL))
                .addDispatcherServletCustomizer(servlet -> servlet.setDispatchOptionsRequest(true))
                .addDispatcherServletCustomizer(servlet -> servlet.setDispatchTraceRequest(true))
                .build();
    }

    @Test
    void returnsConfiguredApiVersion() throws Exception {
        when(config.getBentoApiVersion()).thenReturn("2.1.0");

        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"version\":\"2.1.0\"}"));
    }

    @Test
    void returnsNeo4jVersionFromPublicGraphQl() throws Exception {
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(bentoGraphQL.getPublicGraphQL()).thenReturn(publicGraphQL);
        stubResult(publicGraphQL, Map.of("data", Map.of("neo4jVersion", "5.26.0")));

        mockMvc.perform(get("/neo4j-version"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"version\":\"5.26.0\"}"));

        assertExecutedQuery(publicGraphQL, "{neo4jVersion}", Map.of());
    }

    @Test
    void returnsOpenSearchVersionFromPublicGraphQl() throws Exception {
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(bentoGraphQL.getPublicGraphQL()).thenReturn(publicGraphQL);
        stubResult(publicGraphQL, Map.of("data", Map.of("esVersion", "2.19.4")));

        mockMvc.perform(get("/opensearch-version"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"version\":\"2.19.4\"}"));

        assertExecutedQuery(publicGraphQL, "{esVersion}", Map.of());
    }

    @Test
    void executesPrivateGraphQlForPostRequest() throws Exception {
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(bentoGraphQL.getPrivateGraphQL()).thenReturn(privateGraphQL);
        stubResult(privateGraphQL, Map.of("data", Map.of("example", "private-result")));

        mockMvc.perform(post(PRIVATE_GRAPHQL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"example\":\"private-result\"}}"));

        assertExecutedQuery(
                privateGraphQL,
                "query Example($id: ID) { example(id: $id) }",
                Map.of("id", "abc"));
    }

    @Test
    void executesPublicGraphQlForPostRequest() throws Exception {
        when(config.isAllowGraphQLQuery()).thenReturn(true);
        when(bentoGraphQL.getPublicGraphQL()).thenReturn(publicGraphQL);
        stubResult(publicGraphQL, Map.of("data", Map.of("example", "public-result")));

        mockMvc.perform(post(PUBLIC_GRAPHQL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"example\":\"public-result\"}}"));

        assertExecutedQuery(
                publicGraphQL,
                "query Example($id: ID) { example(id: $id) }",
                Map.of("id", "abc"));
    }

    @ParameterizedTest(name = "{0} {1} returns 405")
    @MethodSource("nonPostGraphQlRequests")
    void rejectsNonPostRequestsForBothGraphQlRoutes(
            HttpMethod method,
            String path) throws Exception {
        MockHttpServletRequestBuilder request = request(method, path);

        mockMvc.perform(request)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().json(
                        "{\"errors\":[{\"message\":\"API will only accept POST requests\"}]}"));
    }

    private void stubResult(GraphQL graphQL, Map<String, Object> specification) {
        ExecutionResult result = org.mockito.Mockito.mock(ExecutionResult.class);
        when(result.toSpecification()).thenReturn(specification);
        when(graphQL.execute(any(ExecutionInput.class))).thenReturn(result);
    }

    private void assertExecutedQuery(
            GraphQL graphQL,
            String expectedQuery,
            Map<String, Object> expectedVariables) {
        ArgumentCaptor<ExecutionInput> inputCaptor = ArgumentCaptor.forClass(ExecutionInput.class);
        verify(graphQL).execute(inputCaptor.capture());
        assertEquals(expectedQuery, inputCaptor.getValue().getQuery());
        assertEquals(expectedVariables, inputCaptor.getValue().getVariables());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> nonPostGraphQlRequests() {
        return Stream.of(PRIVATE_GRAPHQL_PATH, PUBLIC_GRAPHQL_PATH)
                .flatMap(path -> Stream.of(
                        HttpMethod.GET,
                        HttpMethod.HEAD,
                        HttpMethod.PUT,
                        HttpMethod.DELETE,
                        HttpMethod.TRACE,
                        HttpMethod.OPTIONS,
                        HttpMethod.PATCH)
                        .map(method -> org.junit.jupiter.params.provider.Arguments.of(method, path)));
    }
}
