package eclipseview.model;

/**
 * A value as captured at extraction time, fully detached from JDI.
 * References into the heap are {@link HeapReference}s whose target is always
 * present in {@link MemorySnapshot#heap()} (at least as a stub node).
 */
public sealed interface ValueModel permits PrimitiveValue, NullValue, HeapReference, UnreadableValue {
}
