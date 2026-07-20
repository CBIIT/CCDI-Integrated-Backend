package gov.nih.nci.bento_ri.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueUtilsTest {

    @Test
    void toStringListReturnsEmptyListForNull() {
        assertEquals(List.of(), ValueUtils.toStringList(null));
    }

    @Test
    void toStringListWrapsSingleValue() {
        assertEquals(List.of("chemotherapy"), ValueUtils.toStringList("chemotherapy"));
    }

    @Test
    void toStringListConvertsListValuesAndRemovesNulls() {
        List<Object> values = Arrays.asList("chemotherapy", null, 42);

        assertEquals(
            List.of("chemotherapy", "42"),
            ValueUtils.toStringList(values)
        );
    }
}
