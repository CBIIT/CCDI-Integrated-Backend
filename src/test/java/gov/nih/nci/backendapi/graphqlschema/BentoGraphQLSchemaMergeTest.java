package gov.nih.nci.backendapi.graphqlschema;

import gov.nih.nci.bento.graphql.BentoGraphQL;
import gov.nih.nci.bento_ri.model.PrivateESDataFetcher;
import gov.nih.nci.bento_ri.service.InventoryESService;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BentoGraphQLSchemaMergeTest {

    private static final String QUERY_TYPE = "QueryType";

    /**
     * Verifies that the inherited Bento merge behavior retains every query, parameter, and runtime fetcher.
     * The backend currently does not use Neo4j, so the Neo4j schema here is synthetic.
     */
    @Test
    void mergesNeo4jAndPrivateEsSchemaContracts() throws Exception {
        GraphQLSchema neo4jSchema = neo4jSchema();
        RuntimeWiring privateWiring =
                new PrivateESDataFetcher(mock(InventoryESService.class)).buildRuntimeWiring();
        GraphQLSchema privateEsSchema = privateEsSchema(privateWiring);

        GraphQLSchema mergedSchema = invokeMergeSchema(neo4jSchema, privateEsSchema);

        Map<String, Set<String>> expectedContract =
                new LinkedHashMap<>(PrivateESDataFetcherRuntimeWiringTest.expectedContract());
        expectedContract.put("schemaVersion", Set.of());
        expectedContract.put("neo4jVersion", Set.of());
        assertEquals(
                expectedContract,
                PrivateESDataFetcherRuntimeWiringTest.executableContract(mergedSchema));

        assertTrue(mergedSchema.getCodeRegistry().hasDataFetcher(
                FieldCoordinates.coordinates(QUERY_TYPE, "schemaVersion")));
        assertTrue(mergedSchema.getCodeRegistry().hasDataFetcher(
                FieldCoordinates.coordinates(QUERY_TYPE, "neo4jVersion")));

        Set<String> privateDataQueries = privateWiring.getDataFetcherForType(QUERY_TYPE).keySet();
        for (String query : PrivateESDataFetcherRuntimeWiringTest.expectedContract().keySet()) {
            if (privateDataQueries.contains(query)) {
                assertTrue(
                        mergedSchema.getCodeRegistry().hasDataFetcher(
                                FieldCoordinates.coordinates(QUERY_TYPE, query)),
                        () -> "Merged schema lost the data fetcher for " + query);
            }
        }
    }

    /**
     * Verifies the project's current ES-only configuration returns the ES schema unchanged.
     * The backend currently does not use Neo4j.
     */
    @Test
    void returnsEsSchemaWhenNeo4jSchemaIsAbsent() throws Exception {
        GraphQLSchema esSchema = privateEsSchema();

        assertSame(esSchema, invokeMergeSchema(null, esSchema));
    }

    /**
     * Verifies the inherited Bento Neo4j-only fallback even though this project does not use Neo4j.
     */
    @Test
    void returnsNeo4jSchemaWhenEsSchemaIsAbsent() throws Exception {
        GraphQLSchema neo4jSchema = neo4jSchema();

        assertSame(neo4jSchema, invokeMergeSchema(neo4jSchema, null));
    }

    private static GraphQLSchema privateEsSchema() throws Exception {
        RuntimeWiring wiring =
                new PrivateESDataFetcher(mock(InventoryESService.class)).buildRuntimeWiring();
        return privateEsSchema(wiring);
    }

    private static GraphQLSchema privateEsSchema(RuntimeWiring wiring) {
        return PrivateESDataFetcherRuntimeWiringTest.executablePrivateSchema(
                PrivateESDataFetcherRuntimeWiringTest.privateEsRegistry(), wiring);
    }

    private static GraphQLSchema neo4jSchema() {
        String schema = """
                schema { query: QueryType }
                type QueryType {
                    schemaVersion: String
                    neo4jVersion: String
                }
                """;
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type(newTypeWiring(QUERY_TYPE)
                        .dataFetcher("schemaVersion", environment -> "1.0.0")
                        .dataFetcher("neo4jVersion", environment -> "5.26.0"))
                .build();
        return new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(schema), wiring);
    }

    private static GraphQLSchema invokeMergeSchema(GraphQLSchema left, GraphQLSchema right) throws Exception {
        Method method = BentoGraphQL.class.getDeclaredMethod(
                "mergeSchema", GraphQLSchema.class, GraphQLSchema.class);
        method.setAccessible(true);
        return (GraphQLSchema) method.invoke(mock(BentoGraphQL.class), left, right);
    }
}
