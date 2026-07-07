package eclipseview.model;

/** A reference into the heap; rendered as an arrow to the target node. */
public record HeapReference(long targetId, String targetTypeName) implements ValueModel {
}
