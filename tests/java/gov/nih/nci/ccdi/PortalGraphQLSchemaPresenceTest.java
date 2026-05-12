package gov.nih.nci.ccdi;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test that CCDI portal GraphQL schema resources are packaged on the test classpath.
 * No Spring context — confirms JUnit + Maven test compilation for the {@code tests/java} tree.
 */
class PortalGraphQLSchemaPresenceTest {

    @Test
    void privateEsSchemaIsOnClasspath() throws Exception {
        try (InputStream in = classLoader().getResourceAsStream("graphql/ccdi-portal-private-es.graphql")) {
            assertNotNull(in, "graphql/ccdi-portal-private-es.graphql should be on the classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("type QueryType"), "private ES schema should declare QueryType");
            assertTrue(text.contains("idsLists"), "private ES schema should expose idsLists for the portal");
        }
    }

    @Test
    void publicEsSchemaIsOnClasspath() throws Exception {
        try (InputStream in = classLoader().getResourceAsStream("graphql/ccdi-portal-public-es.graphql")) {
            assertNotNull(in, "graphql/ccdi-portal-public-es.graphql should be on the classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("type QueryType"), "public ES schema should declare QueryType");
        }
    }

    private static ClassLoader classLoader() {
        return PortalGraphQLSchemaPresenceTest.class.getClassLoader();
    }
}
