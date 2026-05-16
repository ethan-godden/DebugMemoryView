package eclipseview.model;

import java.util.List;

public record HeapObjectModel(
        long id,                    // JDI unique id — never reused during a VM's lifetime
        String typeName,
        String simpleName,          // BOXED: the wrapper's simple name
        HeapObjectKind kind,
        List<FieldModel> fields,    // PLAIN/ENUM; empty otherwise
        int fieldsOmitted,
        int arrayLength,            // -1 unless ARRAY
        List<ValueModel> elements,  // ARRAY only, first maxArrayElements
        int elementsOmitted,
        String enumConstantName,    // ENUM only, else null
        String displayText,         // STRING: contents; BOXED: value text; else null
        boolean textTruncated,      // STRING only
        boolean jvmCached) {        // BOXED only: value within the spec-defined valueOf cache range

    public boolean explored() {
        return kind != HeapObjectKind.STUB;
    }

    public static HeapObjectModel stub(long id, String typeName, String simpleName) {
        return new HeapObjectModel(id, typeName, simpleName, HeapObjectKind.STUB,
                List.of(), 0, -1, List.of(), 0, null, null, false, false);
    }

    public static HeapObjectModel plain(long id, String typeName, String simpleName,
            List<FieldModel> fields, int fieldsOmitted) {
        return new HeapObjectModel(id, typeName, simpleName, HeapObjectKind.PLAIN,
                fields, fieldsOmitted, -1, List.of(), 0, null, null, false, false);
    }

    public static HeapObjectModel array(long id, String typeName, String simpleName,
            int length, List<ValueModel> elements, int elementsOmitted) {
        return new HeapObjectModel(id, typeName, simpleName, HeapObjectKind.ARRAY,
                List.of(), 0, length, elements, elementsOmitted, null, null, false, false);
    }

    public static HeapObjectModel enumConstant(long id, String typeName, String simpleName,
            List<FieldModel> fields, int fieldsOmitted, String constantName) {
        return new HeapObjectModel(id, typeName, simpleName, HeapObjectKind.ENUM,
                fields, fieldsOmitted, -1, List.of(), 0, constantName, null, false, false);
    }

    public static HeapObjectModel string(long id, String text, boolean truncated) {
        return new HeapObjectModel(id, "java.lang.String", "String", HeapObjectKind.STRING,
                List.of(), 0, -1, List.of(), 0, null, text, truncated, false);
    }

    public static HeapObjectModel boxed(long id, String typeName, String wrapperSimpleName,
            String text, boolean jvmCached) {
        return new HeapObjectModel(id, typeName, wrapperSimpleName, HeapObjectKind.BOXED,
                List.of(), 0, -1, List.of(), 0, null, text, false, jvmCached);
    }
}
