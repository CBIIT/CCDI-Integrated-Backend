package gov.nih.nci.backendapi.graphqlschema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PrivateESDataFetcherRuntimeWiringTest {

    private static final String QUERY_TYPE = "QueryType";
    private static final String EXPECTED_CONTRACT_RESOURCE =
            "/graphql/private-es-query-contract.jsonc";
    private static final Map<String, Set<String>> EXPECTED_CONTRACT = loadExpectedContract();

    private TypeDefinitionRegistry registry;
    private RuntimeWiring wiring;

    @BeforeEach
    void setup() throws Exception {
        registry = privateEsRegistry();
        wiring = new PrivateESDataFetcher(mock(InventoryESService.class)).buildRuntimeWiring();
    }

    /**
     * Verifies the complete private ES QueryType contract, including every query and argument name.
     */
    @Test
    void declaresEveryPrivateQueryAndParameter() {
        assertEquals(expectedContract(), schemaContract(registry));
    }

    /**
     * Verifies that PrivateESDataFetcher explicitly registers a fetcher for every private data query.
     */
    @Test
    void wiresEveryPrivateDataQuery() {
        Set<String> expectedWiredQueries = new LinkedHashSet<>(expectedContract().keySet());
        // The version endpoint intentionally executes esVersion through PublicESDataFetcher.
        expectedWiredQueries.remove("esVersion");

        Set<String> wiredQueries = wiring.getDataFetcherForType(QUERY_TYPE).keySet();
        Set<String> missingQueries = new LinkedHashSet<>(expectedWiredQueries);
        missingQueries.removeAll(wiredQueries);

        assertTrue(missingQueries.isEmpty(), () -> "Queries without runtime wiring: " + missingQueries);
    }

    /**
     * Verifies that schema generation preserves every private query and parameter.
     * Explicit fetcher registration is checked separately by wiresEveryPrivateDataQuery.
     */
    @Test
    void buildsCompleteExecutablePrivateSchema() {
        GraphQLSchema schema = executablePrivateSchema(registry, wiring);

        assertEquals(expectedContract(), executableContract(schema));
    }

    static Map<String, Set<String>> expectedContract() {
        return EXPECTED_CONTRACT;
    }

    static TypeDefinitionRegistry privateEsRegistry() {
        InputStream schema = PrivateESDataFetcherRuntimeWiringTest.class.getResourceAsStream(
                "/graphql/ccdi-portal-private-es.graphql");
        assertNotNull(schema, "private ES schema should be on the classpath");
        return new SchemaParser().parse(schema);
    }

    static GraphQLSchema executablePrivateSchema(TypeDefinitionRegistry registry, RuntimeWiring wiring) {
        return new SchemaGenerator().makeExecutableSchema(registry, wiring);
    }

    static Map<String, Set<String>> executableContract(GraphQLSchema schema) {
        return schema.getQueryType().getFieldDefinitions().stream()
                .collect(Collectors.toMap(
                        GraphQLFieldDefinition::getName,
                        field -> field.getArguments().stream()
                                .map(argument -> argument.getName())
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private static Map<String, Set<String>> schemaContract(TypeDefinitionRegistry registry) {
        ObjectTypeDefinition queryType = (ObjectTypeDefinition) registry.getType(QUERY_TYPE).orElseThrow();
        return queryType.getFieldDefinitions().stream()
                .collect(Collectors.toMap(
                        FieldDefinition::getName,
                        field -> field.getInputValueDefinitions().stream()
                                .map(input -> input.getName())
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private static Map<String, Set<String>> loadExpectedContract() {
        InputStream fixture = Objects.requireNonNull(
                PrivateESDataFetcherRuntimeWiringTest.class.getResourceAsStream(
                        EXPECTED_CONTRACT_RESOURCE),
                "Missing GraphQL contract fixture " + EXPECTED_CONTRACT_RESOURCE);
        try (fixture) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(JsonParser.Feature.ALLOW_COMMENTS);
            JsonNode root = mapper.readTree(fixture);
            Map<String, List<String>> argumentGroups = mapper.convertValue(
                    root.required("argumentGroups"),
                    new TypeReference<LinkedHashMap<String, List<String>>>() {});
            Map<String, List<String>> queryArguments = mapper.convertValue(
                    root.required("queries"),
                    new TypeReference<LinkedHashMap<String, List<String>>>() {});

            Map<String, Set<String>> contract = new LinkedHashMap<>();
            queryArguments.forEach((query, arguments) ->
                    contract.put(query, expandArguments(arguments, argumentGroups)));
            return contract;
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read GraphQL contract fixture", exception);
        }
    }

    private static Set<String> expandArguments(
            List<String> arguments,
            Map<String, List<String>> argumentGroups) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String argument : arguments) {
            if (argument.startsWith("$")) {
                String groupName = argument.substring(1);
                expanded.addAll(Objects.requireNonNull(
                        argumentGroups.get(groupName),
                        "Unknown argument group " + groupName));
            } else {
                expanded.add(argument);
            }
        }
        return expanded;
    }
}
