package eclipseview.model;

import java.util.List;
import java.util.Map;

/**
 * One immutable capture of a suspended thread's memory. Every {@link HeapReference}
 * in the snapshot resolves via {@link #heap()} — the extractor inserts at least a
 * stub node for every referenced object, so lookups never miss and aliasing is
 * structural (same id, same node).
 */
public record MemorySnapshot(
        String debugTargetKey,
        String threadKey,
        String threadName,
        long sequence,               // pipeline request sequence that produced it
        long timestampMillis,
        List<StackFrameModel> frames,     // index 0 = top of stack
        int framesOmitted,
        Map<Long, HeapObjectModel> heap,  // unmodifiable LinkedHashMap; BFS order
        List<StaticsClassModel> statics,  // stack order, deduped
        String selectedFrameKey,          // frame selected in the Debug view (nullable)
        ExtractionStats stats) {

    public MemorySnapshot withSelectedFrame(String frameKey) {
        return new MemorySnapshot(debugTargetKey, threadKey, threadName, sequence, timestampMillis,
                frames, framesOmitted, heap, statics, frameKey, stats);
    }
}
