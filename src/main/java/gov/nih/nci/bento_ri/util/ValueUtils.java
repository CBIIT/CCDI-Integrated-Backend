package gov.nih.nci.bento_ri.util;

import java.util.List;
import java.util.Objects;

public final class ValueUtils {

    private ValueUtils() {
    }

    /** Converts a value to a list of strings. */
    public static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> values) {
            return values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
        }
        return List.of(value.toString());
    }
}
