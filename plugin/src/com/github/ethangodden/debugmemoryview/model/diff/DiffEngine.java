package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.Value;

/**
 * Computes a {@link MemoryDiff} between two consecutive {@link MemorySnapshot}s on the same thread.
 * Identity is by opaque token: frames by frame id, variables by row key within a frame, structs by
 * struct id, fields by row key within a struct. A struct that is unexplored on either side is never
 * claimed as changed. References compare by RESOLVED TARGET — retargeting is the change on the
 * referring row; a target's own mutation shows on the target struct. Two dangling references compare
 * equal; two unreadable values (both {@code Primitive("?")}) compare equal.
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    /**
     * @param prevSequence the extraction sequence {@code prev} was produced under (the snapshot
     *                     itself no longer carries one); ignored when {@code prev} is null.
     */
    public static MemoryDiff diff(MemorySnapshot prev, long prevSequence, MemorySnapshot curr) {
        if (prev == null || !threadIds(prev).equals(threadIds(curr))) {
            return MemoryDiff.initial(curr);
        }
        Map<String, ChangeStatus> frameStatus = new HashMap<>();
        Map<String, ChangeStatus> variableStatus = new HashMap<>();
        List<DisplayableFrame> deletedFrames = new ArrayList<>();
        Map<String, List<DisplayableVariable>> deletedVariables = new HashMap<>();
        diffFrames(prev, curr, frameStatus, variableStatus, deletedFrames, deletedVariables);

        Map<String, ChangeStatus> boxStatus = new HashMap<>();
        Map<String, Map<String, ChangeStatus>> fieldStatus = new HashMap<>();
        List<DisplayableStruct> deletedBoxes = new ArrayList<>();
        diffHeap(prev, curr, boxStatus, fieldStatus, deletedBoxes);

        // Ghosts are copied verbatim from the PREVIOUS snapshot. Reference tokens are stable across
        // snapshots of one target, so a ghost's live reference already resolves against the CURRENT
        // snapshot with no coordinate remap; only a reference whose target is gone (or that was
        // already dangling) is rewritten to the absent value, so a deleted item's row renders an
        // empty cell (no arrow) rather than a wrong-box arrow or a dangling glyph.
        List<DisplayableFrame> ghostFrames = new ArrayList<>(deletedFrames.size());
        for (DisplayableFrame f : deletedFrames) {
            ghostFrames.add(ghostFrame(f, prev, curr));
        }
        Map<String, List<DisplayableVariable>> ghostVars = new HashMap<>();
        for (Map.Entry<String, List<DisplayableVariable>> e : deletedVariables.entrySet()) {
            ghostVars.put(e.getKey(), ghostVariables(e.getValue(), prev, curr));
        }
        List<DisplayableStruct> ghostBoxes = new ArrayList<>(deletedBoxes.size());
        for (DisplayableStruct s : deletedBoxes) {
            ghostBoxes.add(ghostStruct(s, prev, curr));
        }

        return new MemoryDiff(prevSequence, Map.copyOf(frameStatus), Map.copyOf(variableStatus),
                Map.copyOf(boxStatus), Map.copyOf(fieldStatus), List.copyOf(ghostFrames),
                Map.copyOf(ghostVars), List.copyOf(ghostBoxes));
    }

    private static List<String> threadIds(MemorySnapshot s) {
        List<String> ids = new ArrayList<>(s.threads().size());
        for (DisplayableThread t : s.threads()) {
            ids.add(t.id());
        }
        return ids;
    }

    /** All frames of a snapshot, across threads, in thread then stack order (top-of-stack first). */
    private static List<DisplayableFrame> allFrames(MemorySnapshot s) {
        List<DisplayableFrame> frames = new ArrayList<>();
        for (DisplayableThread t : s.threads()) {
            frames.addAll(t.frames());
        }
        return frames;
    }

    /** A ghost reference survives only if it resolved in prev AND its target still exists in curr;
     * otherwise it becomes the absent value (empty cell, no arrow). Non-references pass through. */
    private static Value ghostValue(Value v, MemorySnapshot prev, MemorySnapshot curr) {
        if (!(v instanceof Value.Reference ref)) {
            return v;
        }
        if (prev.resolve(ref).isEmpty() || curr.resolve(ref).isEmpty()) {
            return null;
        }
        return ref;
    }

    private static List<DisplayableVariable> ghostVariables(List<DisplayableVariable> vars,
            MemorySnapshot prev, MemorySnapshot curr) {
        List<DisplayableVariable> out = new ArrayList<>(vars.size());
        for (DisplayableVariable v : vars) {
            out.add(new DisplayableVariable(v.label(), v.type(), ghostValue(v.value(), prev, curr)));
        }
        return out;
    }

    private static DisplayableStruct ghostStruct(DisplayableStruct s, MemorySnapshot prev, MemorySnapshot curr) {
        return new DisplayableStruct(s.id(), s.type(), ghostVariables(s.variables(), prev, curr),
                s.explored(), s.omitted(), s.monitor());
    }

    private static DisplayableFrame ghostFrame(DisplayableFrame f, MemorySnapshot prev, MemorySnapshot curr) {
        if (f.note() != null) {
            return f;
        }
        return new DisplayableFrame(f.id(), f.label(), ghostVariables(f.variables(), prev, curr), null);
    }

    private static void diffFrames(MemorySnapshot prev, MemorySnapshot curr,
            Map<String, ChangeStatus> frameStatus, Map<String, ChangeStatus> variableStatus,
            List<DisplayableFrame> deletedFrames, Map<String, List<DisplayableVariable>> deletedVariables) {

        Map<String, DisplayableFrame> prevById = new LinkedHashMap<>();
        for (DisplayableFrame f : allFrames(prev)) {
            prevById.put(f.id(), f);
        }

        for (DisplayableFrame f : allFrames(curr)) {
            DisplayableFrame old = prevById.remove(f.id());
            List<String> keys = MemoryDiff.rowKeys(f.variables());
            if (old == null) {
                frameStatus.put(f.id(), ChangeStatus.NEW);
                for (String key : keys) {
                    variableStatus.put(MemoryDiff.variableKey(f.id(), key), ChangeStatus.NEW);
                }
                continue;
            }
            Map<String, DisplayableVariable> oldVars = new LinkedHashMap<>();
            List<String> oldKeys = MemoryDiff.rowKeys(old.variables());
            for (int i = 0; i < oldKeys.size(); i++) {
                oldVars.put(oldKeys.get(i), old.variables().get(i));
            }
            // The label carries the line number and the note any body string, so a step within the
            // same frame (same id) reads as a label change here.
            boolean changed = !equalText(f.label(), old.label()) || !equalText(f.note(), old.note());
            for (int i = 0; i < keys.size(); i++) {
                DisplayableVariable v = f.variables().get(i);
                DisplayableVariable oldVar = oldVars.remove(keys.get(i));
                ChangeStatus status = oldVar == null ? ChangeStatus.NEW
                        : valueEquals(v.value(), oldVar.value(), curr, prev) ? ChangeStatus.UNCHANGED
                                : ChangeStatus.CHANGED;
                if (status != ChangeStatus.UNCHANGED) {
                    changed = true;
                }
                variableStatus.put(MemoryDiff.variableKey(f.id(), keys.get(i)), status);
            }
            if (!oldVars.isEmpty()) {
                changed = true;
                deletedVariables.put(f.id(), List.copyOf(oldVars.values()));
            }
            frameStatus.put(f.id(), changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED);
        }

        for (DisplayableFrame gone : prevById.values()) {
            frameStatus.put(gone.id(), ChangeStatus.DELETED);
            deletedFrames.add(gone);
        }
    }

    private static void diffHeap(MemorySnapshot prev, MemorySnapshot curr,
            Map<String, ChangeStatus> boxStatus, Map<String, Map<String, ChangeStatus>> fieldStatus,
            List<DisplayableStruct> deletedBoxes) {

        Map<String, DisplayableStruct> prevById = new LinkedHashMap<>();
        for (DisplayableStruct s : prev.heap()) {
            prevById.put(s.id(), s);
        }
        Map<String, DisplayableStruct> currById = new LinkedHashMap<>();
        for (DisplayableStruct s : curr.heap()) {
            currById.put(s.id(), s);
        }

        for (DisplayableStruct struct : curr.heap()) {
            DisplayableStruct old = prevById.get(struct.id());
            if (old == null) {
                boxStatus.put(struct.id(), ChangeStatus.NEW);
                continue;
            }
            // Either side unexplored: contents unknown — never claim a change.
            if (!struct.explored() || !old.explored()) {
                boxStatus.put(struct.id(), ChangeStatus.UNCHANGED);
                continue;
            }
            boxStatus.put(struct.id(), diffStruct(old, struct, curr, prev, fieldStatus));
        }

        for (DisplayableStruct old : prev.heap()) {
            if (!currById.containsKey(old.id())) {
                boxStatus.put(old.id(), ChangeStatus.DELETED);
                deletedBoxes.add(old);
            }
        }
    }

    private static ChangeStatus diffStruct(DisplayableStruct old, DisplayableStruct struct,
            MemorySnapshot curr, MemorySnapshot prev,
            Map<String, Map<String, ChangeStatus>> fieldStatus) {

        // The type carries neutral display info (e.g. an array's length); a change there is a change.
        boolean changed = !equalText(old.type(), struct.type()) || old.omitted() != struct.omitted()
                || !Objects.equals(old.monitor(), struct.monitor());
        Map<String, ChangeStatus> byField = new HashMap<>();
        Map<String, DisplayableVariable> oldFields = new LinkedHashMap<>();
        List<String> oldKeys = MemoryDiff.rowKeys(old.variables());
        for (int i = 0; i < oldKeys.size(); i++) {
            oldFields.put(oldKeys.get(i), old.variables().get(i));
        }
        List<String> keys = MemoryDiff.rowKeys(struct.variables());
        for (int i = 0; i < keys.size(); i++) {
            DisplayableVariable f = struct.variables().get(i);
            DisplayableVariable oldField = oldFields.remove(keys.get(i));
            ChangeStatus status = oldField == null ? ChangeStatus.NEW
                    : valueEquals(f.value(), oldField.value(), curr, prev) ? ChangeStatus.UNCHANGED
                            : ChangeStatus.CHANGED;
            if (status != ChangeStatus.UNCHANGED) {
                changed = true;
            }
            byField.put(keys.get(i), status);
        }
        // Vanished fields only flag the struct CHANGED; there are no ghost rows inside a struct.
        if (!oldFields.isEmpty()) {
            changed = true;
        }
        if (!byField.isEmpty()) {
            fieldStatus.put(struct.id(), byField);
        }
        return changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED;
    }

    /**
     * Values compare so that: two absent (null) values are equal; two primitives are equal iff their
     * strings match; two references are equal iff they resolve to the same target struct (both
     * dangling counts as equal); a primitive never equals a reference or absent.
     */
    static boolean valueEquals(Value a, Value b, MemorySnapshot da, MemorySnapshot db) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Value.Primitive pa) {
            return b instanceof Value.Primitive pb && pa.value().equals(pb.value());
        }
        if (a instanceof Value.Reference ra) {
            if (!(b instanceof Value.Reference rb)) {
                return false;
            }
            Optional<DisplayableStruct> ta = da.resolve(ra);
            Optional<DisplayableStruct> tb = db.resolve(rb);
            if (ta.isEmpty() || tb.isEmpty()) {
                return ta.isEmpty() && tb.isEmpty(); // both dangling -> equal
            }
            return ta.get().id().equals(tb.get().id());
        }
        return false;
    }

    private static boolean equalText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
