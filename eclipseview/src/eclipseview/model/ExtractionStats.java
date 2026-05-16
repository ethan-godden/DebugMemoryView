package eclipseview.model;

import java.util.List;

public record ExtractionStats(
        int objectsExplored,
        int objectsOmitted,      // enqueues refused by the caps
        boolean heapTruncated,   // true iff any enqueue was refused
        boolean partial,         // true iff any per-value read failed
        List<String> errors) {   // capped at ExtractionLimits.maxErrors

    public static ExtractionStats empty() {
        return new ExtractionStats(0, 0, false, false, List.of());
    }
}
