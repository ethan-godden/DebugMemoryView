package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.ethangodden.debugmemoryview.model.Box;
import com.github.ethangodden.debugmemoryview.model.Frame;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.Primitive;
import com.github.ethangodden.debugmemoryview.model.Reference;
import com.github.ethangodden.debugmemoryview.model.Section;
import com.github.ethangodden.debugmemoryview.model.Value;
import com.github.ethangodden.debugmemoryview.model.Variable;

/**
 * Computes a {@link MemoryDiff} between two consecutive {@link MemoryDiagram}s on the same thread.
 * Identity is by opaque token: frames by frame token, variables by symbol id within a frame, boxes by
 * box token, fields by symbol id within a box. A box that is unexplored on either side is never
 * claimed as changed. References compare by RESOLVED TARGET TOKEN — retargeting is the change on the
 * referring slot; a target's own mutation shows on the target box. Two dangling references compare
 * equal; two unreadable values (both {@link Primitive}{@code ("?")}) compare equal.
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    public static MemoryDiff diff(MemoryDiagram prev, MemoryDiagram curr) {
        if (prev == null || !prev.threadToken().equals(curr.threadToken())) {
            return MemoryDiff.initial(curr);
        }
        Map<String, ChangeStatus> frameStatus = new HashMap<>();
        Map<String, ChangeStatus> variableStatus = new HashMap<>();
        List<Frame> deletedFrames = new ArrayList<>();
        Map<String, List<Variable>> deletedVariables = new HashMap<>();
        diffFrames(prev, curr, frameStatus, variableStatus, deletedFrames, deletedVariables);

        Map<String, ChangeStatus> boxStatus = new HashMap<>();
        Map<String, Map<String, ChangeStatus>> fieldStatus = new HashMap<>();
        List<Box> deletedBoxes = new ArrayList<>();
        diffHeap(prev, curr, boxStatus, fieldStatus, deletedBoxes);

        // Ghosts are copied verbatim from the PREVIOUS diagram and still carry ITS cell coordinates.
        // Remap their references into the CURRENT diagram's coordinates so a deleted box's arrow points
        // at its surviving target's current cell — never at an unrelated box that now occupies the old
        // slot — and renders empty (no arrow) when the target is gone too.
        Map<String, Integer> currSlotByToken = new HashMap<>();
        for (Map.Entry<Integer, Box> e : curr.heapBySlot().entrySet()) {
            currSlotByToken.put(e.getValue().boxToken(), e.getKey());
        }
        List<Frame> ghostFrames = new ArrayList<>(deletedFrames.size());
        for (Frame f : deletedFrames) {
            ghostFrames.add(remapFrame(f, prev, currSlotByToken));
        }
        Map<String, List<Variable>> ghostVars = new HashMap<>();
        for (Map.Entry<String, List<Variable>> e : deletedVariables.entrySet()) {
            ghostVars.put(e.getKey(), remapVariables(e.getValue(), prev, currSlotByToken));
        }
        List<Box> ghostBoxes = new ArrayList<>(deletedBoxes.size());
        for (Box b : deletedBoxes) {
            ghostBoxes.add(remapBox(b, prev, currSlotByToken));
        }

        return new MemoryDiff(prev.sequence(), Map.copyOf(frameStatus), Map.copyOf(variableStatus),
                Map.copyOf(boxStatus), Map.copyOf(fieldStatus), List.copyOf(ghostFrames),
                Map.copyOf(ghostVars), List.copyOf(ghostBoxes));
    }

    /** Rewrites a ghost reference from the previous diagram's cell to the target's current cell, or to
     * an absent value (no arrow) when the target no longer exists. Non-references are returned as-is. */
    private static Value remapGhostValue(Value v, MemoryDiagram prev, Map<String, Integer> currSlotByToken) {
        if (!(v instanceof Reference ref)) {
            return v;
        }
        Optional<Box> target = prev.resolve(ref);
        if (target.isPresent()) {
            Integer slot = currSlotByToken.get(target.get().boxToken());
            if (slot != null) {
                return new Reference(Section.HEAP, slot);
            }
        }
        return null;
    }

    private static List<Variable> remapVariables(List<Variable> vars, MemoryDiagram prev,
            Map<String, Integer> currSlotByToken) {
        List<Variable> out = new ArrayList<>(vars.size());
        for (Variable v : vars) {
            out.add(new Variable(v.symbolId(), v.identifier(), v.typeLabel(),
                    remapGhostValue(v.value(), prev, currSlotByToken)));
        }
        return out;
    }

    private static Box remapBox(Box b, MemoryDiagram prev, Map<String, Integer> currSlotByToken) {
        return new Box(b.boxToken(), b.header(), remapVariables(b.fields(), prev, currSlotByToken),
                b.explored(), b.omittedCount());
    }

    private static Frame remapFrame(Frame f, MemoryDiagram prev, Map<String, Integer> currSlotByToken) {
        if (f.hasBody()) {
            return f;
        }
        return Frame.withVariables(f.frameToken(), f.header(),
                remapVariables(f.variables(), prev, currSlotByToken));
    }

    private static void diffFrames(MemoryDiagram prev, MemoryDiagram curr,
            Map<String, ChangeStatus> frameStatus, Map<String, ChangeStatus> variableStatus,
            List<Frame> deletedFrames, Map<String, List<Variable>> deletedVariables) {

        Map<String, Frame> prevByToken = new LinkedHashMap<>();
        for (Frame f : prev.frames()) {
            prevByToken.put(f.frameToken(), f);
        }

        for (Frame f : curr.frames()) {
            Frame old = prevByToken.remove(f.frameToken());
            if (old == null) {
                frameStatus.put(f.frameToken(), ChangeStatus.NEW);
                for (Variable v : f.variables()) {
                    variableStatus.put(MemoryDiff.variableKey(f.frameToken(), v.symbolId()), ChangeStatus.NEW);
                }
                continue;
            }
            Map<String, Variable> oldVars = new LinkedHashMap<>();
            for (Variable v : old.variables()) {
                oldVars.put(v.symbolId(), v);
            }
            // The header carries the line number and any body string, so a step within the same frame
            // (same token) reads as a header change here.
            boolean changed = !equalText(f.header(), old.header()) || !equalText(f.body(), old.body());
            for (Variable v : f.variables()) {
                Variable oldVar = oldVars.remove(v.symbolId());
                ChangeStatus status = oldVar == null ? ChangeStatus.NEW
                        : valueEquals(v.value(), oldVar.value(), curr, prev) ? ChangeStatus.UNCHANGED
                                : ChangeStatus.CHANGED;
                if (status != ChangeStatus.UNCHANGED) {
                    changed = true;
                }
                variableStatus.put(MemoryDiff.variableKey(f.frameToken(), v.symbolId()), status);
            }
            if (!oldVars.isEmpty()) {
                changed = true;
                deletedVariables.put(f.frameToken(), List.copyOf(oldVars.values()));
            }
            frameStatus.put(f.frameToken(), changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED);
        }

        for (Frame gone : prevByToken.values()) {
            frameStatus.put(gone.frameToken(), ChangeStatus.DELETED);
            deletedFrames.add(gone);
        }
    }

    private static void diffHeap(MemoryDiagram prev, MemoryDiagram curr,
            Map<String, ChangeStatus> boxStatus, Map<String, Map<String, ChangeStatus>> fieldStatus,
            List<Box> deletedBoxes) {

        Map<String, Box> prevByToken = prev.boxByToken();
        Map<String, Box> currByToken = curr.boxByToken();

        for (Box box : curr.heap()) {
            Box old = prevByToken.get(box.boxToken());
            if (old == null) {
                boxStatus.put(box.boxToken(), ChangeStatus.NEW);
                continue;
            }
            // Either side unexplored: contents unknown — never claim a change.
            if (!box.explored() || !old.explored()) {
                boxStatus.put(box.boxToken(), ChangeStatus.UNCHANGED);
                continue;
            }
            boxStatus.put(box.boxToken(), diffBox(old, box, curr, prev, fieldStatus));
        }

        for (Box old : prev.heap()) {
            if (!currByToken.containsKey(old.boxToken())) {
                boxStatus.put(old.boxToken(), ChangeStatus.DELETED);
                deletedBoxes.add(old);
            }
        }
    }

    private static ChangeStatus diffBox(Box old, Box box, MemoryDiagram curr, MemoryDiagram prev,
            Map<String, Map<String, ChangeStatus>> fieldStatus) {

        // The header carries neutral display info (e.g. an array's length); a change there is a change.
        boolean changed = !equalText(old.header(), box.header()) || old.omittedCount() != box.omittedCount();
        Map<String, ChangeStatus> byField = new HashMap<>();
        Map<String, Variable> oldFields = new LinkedHashMap<>();
        for (Variable f : old.fields()) {
            oldFields.put(f.symbolId(), f);
        }
        for (Variable f : box.fields()) {
            Variable oldField = oldFields.remove(f.symbolId());
            ChangeStatus status = oldField == null ? ChangeStatus.NEW
                    : valueEquals(f.value(), oldField.value(), curr, prev) ? ChangeStatus.UNCHANGED
                            : ChangeStatus.CHANGED;
            if (status != ChangeStatus.UNCHANGED) {
                changed = true;
            }
            byField.put(f.symbolId(), status);
        }
        // Vanished fields only flag the box CHANGED; there are no ghost rows inside a box.
        if (!oldFields.isEmpty()) {
            changed = true;
        }
        if (!byField.isEmpty()) {
            fieldStatus.put(box.boxToken(), byField);
        }
        return changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED;
    }

    /**
     * Values compare so that: two absent (null) values are equal; two primitives are equal iff their
     * strings match; two references are equal iff they resolve to the same target box token (both
     * dangling counts as equal); a primitive never equals a reference or absent.
     */
    static boolean valueEquals(Value a, Value b, MemoryDiagram da, MemoryDiagram db) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Primitive pa) {
            return b instanceof Primitive pb && pa.value().equals(pb.value());
        }
        if (a instanceof Reference ra) {
            if (!(b instanceof Reference rb)) {
                return false;
            }
            Optional<Box> ta = da.resolve(ra);
            Optional<Box> tb = db.resolve(rb);
            if (ta.isEmpty() || tb.isEmpty()) {
                return ta.isEmpty() && tb.isEmpty(); // both dangling -> equal
            }
            return ta.get().boxToken().equals(tb.get().boxToken());
        }
        return false;
    }

    private static boolean equalText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
