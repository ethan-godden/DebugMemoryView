package eclipseview.model;

public enum HeapObjectKind {
    PLAIN, ARRAY, ENUM, STRING, BOXED,
    /** Referenced but not explored (over caps); box drawn with type name only. */
    STUB
}
