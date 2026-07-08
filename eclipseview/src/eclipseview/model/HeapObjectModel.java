package eclipseview.model;

import java.util.List;

/**
 * One heap node captured at extraction time, fully detached from JDI. A closed
 * set of shapes discriminated structurally (like {@link ValueModel}): a
 * {@link StubObject} (referenced but not explored), a {@link PlainObject} or
 * {@link EnumObject} (a {@link FieldsObject} carrying named fields), an
 * {@link ArrayObject} (indexed elements), or a {@link StringObject} /
 * {@link BoxedObject} (a single display value). Every {@link HeapReference}
 * resolves to one of these via {@link MemorySnapshot#heap()}.
 *
 * <p>The six factory methods are the sole construction path; readers pattern-match
 * on the variants so the compiler flags any shape a switch forgets.
 */
public sealed interface HeapObjectModel
        permits HeapObjectModel.StubObject, HeapObjectModel.ArrayObject,
                HeapObjectModel.StringObject, HeapObjectModel.BoxedObject,
                HeapObjectModel.FieldsObject {

    long id();                 // JDI unique id — never reused during a VM's lifetime

    String typeName();

    String simpleName();       // BOXED: the wrapper's simple name

    /** False only for a {@link StubObject}: contents are unknown, never a claimed change. */
    default boolean explored() {
        return !(this instanceof StubObject);
    }

    /** Referenced but not explored (over caps); box drawn with type name only. */
    record StubObject(long id, String typeName, String simpleName) implements HeapObjectModel {
    }

    record ArrayObject(long id, String typeName, String simpleName,
            int arrayLength, List<ValueModel> elements, int elementsOmitted) implements HeapObjectModel {
    }

    /** STRING contents; {@code textTruncated} when the captured text was clipped. */
    record StringObject(long id, String typeName, String simpleName,
            String displayText, boolean textTruncated) implements HeapObjectModel {
    }

    /** A boxed primitive; {@code jvmCached} when within the spec-defined valueOf cache range. */
    record BoxedObject(long id, String typeName, String simpleName,
            String displayText, boolean jvmCached) implements HeapObjectModel {
    }

    /** PLAIN and ENUM share a list of named fields (both are diffed field-by-field). */
    sealed interface FieldsObject extends HeapObjectModel permits PlainObject, EnumObject {
        List<FieldModel> fields();

        int fieldsOmitted();
    }

    record PlainObject(long id, String typeName, String simpleName,
            List<FieldModel> fields, int fieldsOmitted) implements FieldsObject {
    }

    record EnumObject(long id, String typeName, String simpleName,
            List<FieldModel> fields, int fieldsOmitted, String enumConstantName) implements FieldsObject {
    }

    static HeapObjectModel stub(long id, String typeName, String simpleName) {
        return new StubObject(id, typeName, simpleName);
    }

    static HeapObjectModel plain(long id, String typeName, String simpleName,
            List<FieldModel> fields, int fieldsOmitted) {
        return new PlainObject(id, typeName, simpleName, fields, fieldsOmitted);
    }

    static HeapObjectModel array(long id, String typeName, String simpleName,
            int length, List<ValueModel> elements, int elementsOmitted) {
        return new ArrayObject(id, typeName, simpleName, length, elements, elementsOmitted);
    }

    static HeapObjectModel enumConstant(long id, String typeName, String simpleName,
            List<FieldModel> fields, int fieldsOmitted, String constantName) {
        return new EnumObject(id, typeName, simpleName, fields, fieldsOmitted, constantName);
    }

    static HeapObjectModel string(long id, String text, boolean truncated) {
        return new StringObject(id, "java.lang.String", "String", text, truncated);
    }

    static HeapObjectModel boxed(long id, String typeName, String wrapperSimpleName,
            String text, boolean jvmCached) {
        return new BoxedObject(id, typeName, wrapperSimpleName, text, jvmCached);
    }
}
