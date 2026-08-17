package gov.nih.nci.bento_ri.model;

import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OverviewGraphQLSchemaTest {

    @Test
    void overviewFieldsBackedByArraysAreDeclaredAsLists() {
        InputStream schema = getClass().getResourceAsStream(
            "/graphql/ccdi-portal-private-es.graphql"
        );
        assertNotNull(schema);

        TypeDefinitionRegistry registry = new SchemaParser().parse(schema);

        assertListField(registry, "ParticipantOverViewResult", "race");

        assertListField(registry, "StudyOverViewResult", "diagnosis");
        assertListField(registry, "StudyOverViewResult", "anatomic_site");
        assertListField(registry, "StudyOverViewResult", "file_type");
        assertListField(registry, "StudyOverViewResult", "pubmed_id");
        assertListField(registry, "StudyOverViewResult", "personnel_name");
        assertListField(registry, "StudyOverViewResult", "grant_id");

        assertListField(registry, "DiagnosisOverViewResult", "anatomic_site");
        assertListField(registry, "DiagnosisOverViewResult", "diagnosis_classification_system");
        assertListField(registry, "DiagnosisOverViewResult", "diagnosis_basis");

        assertListField(registry, "GeneticAnalysisOverviewResult", "gene_symbol");

        assertListField(registry, "TreatmentOverViewResult", "treatment_type");
        assertListField(registry, "TreatmentOverViewResult", "treatment_agent");

        assertListField(registry, "FileOverViewResult", "participant_age_at_collection");
        assertListField(registry, "FileOverViewResult", "datamodel_dcf_indexd_guid");
        assertListField(registry, "FileOverViewResult", "datamodel_guid");
        assertListField(registry, "FileOverViewResult", "datamodel_file_id");
    }

    private static void assertListField(
        TypeDefinitionRegistry registry,
        String typeName,
        String fieldName
    ) {
        ObjectTypeDefinition type = (ObjectTypeDefinition) registry.getType(typeName).orElseThrow();
        FieldDefinition field = type.getFieldDefinitions().stream()
            .filter(candidate -> candidate.getName().equals(fieldName))
            .findFirst()
            .orElseThrow();

        assertInstanceOf(ListType.class, field.getType(), typeName + "." + fieldName);
    }
}
