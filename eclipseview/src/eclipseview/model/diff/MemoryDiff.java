package eclipseview.model.diff;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eclipseview.model.FieldModel;
import eclipseview.model.HeapObjectModel;
import eclipseview.model.MemorySnapshot;
import eclipseview.model.StackFrameModel;
import eclipseview.model.StaticsClassModel;
import eclipseview.model.VariableModel;

/**
 * Change annotations for one snapshot relative to the previous one on the same
 * thread. Ghosts (DELETED items) carry full models copied from the PREVIOUS
 * snapshot and live only here — the pipeline keeps one baseline per thread, so
 * every deletion is rendered exactly once. Renderers must treat the diff as
 * transient per render and never accumulate ghosts.
 */
public record MemoryDiff(
        long baselineSequence,                        // -1 for an initial diff
        Map<String, ChangeStatus> frameStatus,        // frameKey -> status
        Map<String, ChangeStatus> variableStatus,     // variableKey -> status
        Map<Long, ChangeStatus> objectStatus,         // heap id -> status
        Map<Long, Map<String, ChangeStatus>> fieldStatus, // heap id -> fieldKey -> status
        Map<Long, BitSet> changedElements,            // ARRAY id -> changed indices (shown range)
        Map<String, ChangeStatus> staticStatus,       // "className.fieldName" -> status
        List<StackFrameModel> deletedFrames,
        Map<String, List<VariableModel>> deletedVariables,   // surviving frameKey -> vanished vars
        List<HeapObjectModel> deletedObjects,
        List<StaticsClassModel> deletedStaticClasses,
        Map<String, List<FieldModel>> deletedStaticFields) { // surviving className -> vanished fields

    /** First suspend of a session / thread switch: everything renders as NEW (user decision). */
    public static MemoryDiff initial(MemorySnapshot s) {
        Map<String, ChangeStatus> frames = new HashMap<>();
        Map<String, ChangeStatus> variables = new HashMap<>();
        for (StackFrameModel f : s.frames()) {
            frames.put(f.frameKey(), ChangeStatus.NEW);
            for (VariableModel v : f.allVariables()) {
                variables.put(v.variableKey(f.frameKey()), ChangeStatus.NEW);
            }
        }
        Map<Long, ChangeStatus> objects = new HashMap<>();
        for (Long id : s.heap().keySet()) {
            objects.put(id, ChangeStatus.NEW);
        }
        Map<String, ChangeStatus> statics = new HashMap<>();
        for (StaticsClassModel c : s.statics()) {
            for (FieldModel f : c.fields()) {
                statics.put(f.fieldKey(), ChangeStatus.NEW);
            }
        }
        return new MemoryDiff(-1, frames, variables, objects, Map.of(), Map.of(), statics,
                List.of(), Map.of(), List.of(), List.of(), Map.of());
    }

    public ChangeStatus frameStatusOf(String frameKey) {
        return frameStatus.getOrDefault(frameKey, ChangeStatus.UNCHANGED);
    }

    public ChangeStatus variableStatusOf(String variableKey) {
        return variableStatus.getOrDefault(variableKey, ChangeStatus.UNCHANGED);
    }

    public ChangeStatus objectStatusOf(long id) {
        return objectStatus.getOrDefault(id, ChangeStatus.UNCHANGED);
    }

    /** Fields of NEW objects have no entries; the box-level NEW border communicates newness. */
    public ChangeStatus fieldStatusOf(long objectId, String fieldKey) {
        Map<String, ChangeStatus> byField = fieldStatus.get(objectId);
        return byField == null ? ChangeStatus.UNCHANGED
                : byField.getOrDefault(fieldKey, ChangeStatus.UNCHANGED);
    }

    public ChangeStatus staticStatusOf(String fieldKey) {
        return staticStatus.getOrDefault(fieldKey, ChangeStatus.UNCHANGED);
    }

    public boolean elementChanged(long arrayId, int index) {
        BitSet bits = changedElements.get(arrayId);
        return bits != null && bits.get(index);
    }
}
