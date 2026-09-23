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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BentoGraphQLSchemaMergeTest {

    private static final String QUERY_TYPE = "QueryType";

    /**
     * Verifies that merging the Neo4j and private ES schemas retains every query, parameter, and runtime fetcher.
     */
    @Test
    void mergesNeo4jAndPrivateEsSchemaContracts() throws Exception {
        GraphQLSchema neo4jSchema = neo4jSchema();
        RuntimeWiring privateWiring =
                new PrivateESDataFetcher(mock(InventoryESService.class)).buildRuntimeWiring();
        GraphQLSchema privateEsSchema = PrivateESDataFetcherRuntimeWiringTest.executablePrivateSchema(
                PrivateESDataFetcherRuntimeWiringTest.privateEsRegistry(),
                privateWiring);

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
     * Verifies that the ES schema is returned unchanged when no Neo4j schema is supplied.
     */
    @Test
    void returnsEsSchemaWhenNeo4jSchemaIsAbsent() throws Exception {
        GraphQLSchema esSchema = neo4jSchema();

        assertEquals(esSchema, invokeMergeSchema(null, esSchema));
    }

    /**
     * Verifies that the Neo4j schema is returned unchanged when no ES schema is supplied.
     */
    @Test
    void returnsNeo4jSchemaWhenEsSchemaIsAbsent() throws Exception {
        GraphQLSchema neo4jSchema = neo4jSchema();

        assertEquals(neo4jSchema, invokeMergeSchema(neo4jSchema, null));
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
