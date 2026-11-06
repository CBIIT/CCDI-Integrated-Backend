package gov.nih.nci.ccdi;

import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void cohortMetadataSchemaUsesStudyConsentGroupParticipantHierarchy() throws Exception {
        try (InputStream in = classLoader().getResourceAsStream("graphql/ccdi-portal-private-es.graphql")) {
            assertNotNull(in, "graphql/ccdi-portal-private-es.graphql should be on the classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            TypeDefinitionRegistry registry = new SchemaParser().parse(text);

            ObjectTypeDefinition study = objectType(registry, "CohortMetadataResult");
            assertEquals(
                "CohortMetadataStudyConsentGroup",
                listTypeName(field(study, "consent_groups"))
            );

            ObjectTypeDefinition consentGroup = objectType(
                registry,
                "CohortMetadataStudyConsentGroup"
            );
            assertEquals(
                "CohortMetadataParticipant",
                listTypeName(field(consentGroup, "participants"))
            );

            ObjectTypeDefinition participant = objectType(registry, "CohortMetadataParticipant");
            assertEquals(
                "CohortMetadataParticipantDiagnosis",
                listTypeName(field(participant, "diagnoses"))
            );
            assertEquals(
                "CohortMetadataParticipantSample",
                listTypeName(field(participant, "samples"))
            );
            assertFalse(
                registry.getType("CohortMetadataReturnObject").isPresent(),
                "the obsolete flat participant type should be removed"
            );
        }
    }

    private static ObjectTypeDefinition objectType(
        TypeDefinitionRegistry registry,
        String typeName
    ) {
        return (ObjectTypeDefinition) registry.getType(typeName).orElseThrow();
    }

    private static FieldDefinition field(ObjectTypeDefinition type, String fieldName) {
        return type.getFieldDefinitions().stream()
            .filter(field -> fieldName.equals(field.getName()))
            .findFirst()
            .orElseThrow();
    }

    private static String listTypeName(FieldDefinition field) {
        ListType listType = (ListType) field.getType();
        return ((TypeName) listType.getType()).getName();
    }

    private static ClassLoader classLoader() {
        return PortalGraphQLSchemaPresenceTest.class.getClassLoader();
    }
}
