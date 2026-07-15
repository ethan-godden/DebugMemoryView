package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.ethangodden.debugmemoryview.model.Box;
import com.github.ethangodden.debugmemoryview.model.Frame;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.Variable;

/**
 * Change annotations for one {@link MemoryDiagram} relative to the previous one on the same thread.
 * All keys are opaque tokens (frame tokens, box tokens, variable/field symbol ids) — never a JVM id.
 * Ghosts (DELETED items) carry full neutral models copied from the PREVIOUS diagram and live only
 * here; the pipeline keeps one baseline per thread, so every deletion renders exactly once. Renderers
 * must treat the diff as transient per render and never accumulate ghosts.
 *
 * <p>{@code fieldStatus} covers object fields, array elements, and string chars uniformly (all are
 * {@link Variable} rows keyed by symbol id within their box).
 */
public record MemoryDiff(
        long baselineSequence,                              // -1 for an initial diff
        Map<String, ChangeStatus> frameStatus,              // frameToken -> status
        Map<String, ChangeStatus> variableStatus,           // frameToken#symbolId -> status
        Map<String, ChangeStatus> boxStatus,                // boxToken -> status
        Map<String, Map<String, ChangeStatus>> fieldStatus, // boxToken -> (field symbolId -> status)
        List<Frame> deletedFrames,
        Map<String, List<Variable>> deletedVariables,       // surviving frameToken -> vanished rows
        List<Box> deletedBoxes) {

    /** Composite key for a variable row: its frame token plus its symbol id. */
    public static String variableKey(String frameToken, String symbolId) {
        return frameToken + "#" + symbolId;
    }

    /** First suspend of a session / thread switch: everything renders as NEW. */
    public static MemoryDiff initial(MemoryDiagram d) {
        Map<String, ChangeStatus> frames = new HashMap<>();
        Map<String, ChangeStatus> variables = new HashMap<>();
        for (Frame f : d.frames()) {
            frames.put(f.frameToken(), ChangeStatus.NEW);
            for (Variable v : f.variables()) {
                variables.put(variableKey(f.frameToken(), v.symbolId()), ChangeStatus.NEW);
            }
        }
        Map<String, ChangeStatus> boxes = new HashMap<>();
        for (Box b : d.heap()) {
            boxes.put(b.boxToken(), ChangeStatus.NEW);
        }
        // Fields of NEW boxes carry no entries; the box-level NEW border communicates newness.
        return new MemoryDiff(-1, Map.copyOf(frames), Map.copyOf(variables), Map.copyOf(boxes),
                Map.of(), List.of(), Map.of(), List.of());
    }

    public ChangeStatus frameStatusOf(String frameToken) {
        return frameStatus.getOrDefault(frameToken, ChangeStatus.UNCHANGED);
    }

    public ChangeStatus variableStatusOf(String frameToken, String symbolId) {
        return variableStatus.getOrDefault(variableKey(frameToken, symbolId), ChangeStatus.UNCHANGED);
    }

    public ChangeStatus boxStatusOf(String boxToken) {
        return boxStatus.getOrDefault(boxToken, ChangeStatus.UNCHANGED);
    }

    /** Fields of a NEW box have no entries; the box-level NEW border communicates newness. */
    public ChangeStatus fieldStatusOf(String boxToken, String symbolId) {
        Map<String, ChangeStatus> byField = fieldStatus.get(boxToken);
        return byField == null ? ChangeStatus.UNCHANGED
                : byField.getOrDefault(symbolId, ChangeStatus.UNCHANGED);
    }
}
