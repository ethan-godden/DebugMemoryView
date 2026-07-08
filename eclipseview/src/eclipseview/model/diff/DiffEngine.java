package eclipseview.model.diff;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import eclipseview.model.FieldModel;
import eclipseview.model.HeapObjectModel;
import eclipseview.model.HeapObjectModel.ArrayObject;
import eclipseview.model.HeapObjectModel.BoxedObject;
import eclipseview.model.HeapObjectModel.FieldsObject;
import eclipseview.model.HeapObjectModel.StringObject;
import eclipseview.model.HeapObjectModel.StubObject;
import eclipseview.model.HeapReference;
import eclipseview.model.MemorySnapshot;
import eclipseview.model.NullValue;
import eclipseview.model.PrimitiveValue;
import eclipseview.model.StackFrameModel;
import eclipseview.model.StaticsClassModel;
import eclipseview.model.UnreadableValue;
import eclipseview.model.ValueModel;
import eclipseview.model.VariableModel;

public final class DiffEngine {

    private DiffEngine() {
    }

    public static MemoryDiff diff(MemorySnapshot prev, MemorySnapshot curr) {
        if (prev == null || !prev.threadKey().equals(curr.threadKey())) {
            return MemoryDiff.initial(curr);
        }
        Map<String, ChangeStatus> frameStatus = new HashMap<>();
        Map<String, ChangeStatus> variableStatus = new HashMap<>();
        Map<Long, ChangeStatus> objectStatus = new HashMap<>();
        Map<Long, Map<String, ChangeStatus>> fieldStatus = new HashMap<>();
        Map<Long, BitSet> changedElements = new HashMap<>();
        Map<String, ChangeStatus> staticStatus = new HashMap<>();
        List<StackFrameModel> deletedFrames = new ArrayList<>();
        Map<String, List<VariableModel>> deletedVariables = new HashMap<>();
        List<HeapObjectModel> deletedObjects = new ArrayList<>();
        List<StaticsClassModel> deletedStaticClasses = new ArrayList<>();
        Map<String, List<FieldModel>> deletedStaticFields = new HashMap<>();

        diffFrames(prev, curr, frameStatus, variableStatus, deletedFrames, deletedVariables);
        diffHeap(prev, curr, objectStatus, fieldStatus, changedElements, deletedObjects);
        diffStatics(prev, curr, staticStatus, deletedStaticClasses, deletedStaticFields);

        return new MemoryDiff(prev.sequence(), frameStatus, variableStatus, objectStatus, fieldStatus,
                changedElements, staticStatus, deletedFrames, deletedVariables, deletedObjects,
                deletedStaticClasses, deletedStaticFields);
    }

    private static void diffFrames(MemorySnapshot prev, MemorySnapshot curr,
            Map<String, ChangeStatus> frameStatus, Map<String, ChangeStatus> variableStatus,
            List<StackFrameModel> deletedFrames, Map<String, List<VariableModel>> deletedVariables) {

        Map<String, StackFrameModel> prevByKey = new LinkedHashMap<>();
        for (StackFrameModel f : prev.frames()) {
            prevByKey.put(f.frameKey(), f);
        }

        for (StackFrameModel f : curr.frames()) {
            StackFrameModel old = prevByKey.remove(f.frameKey());
            if (old == null) {
                frameStatus.put(f.frameKey(), ChangeStatus.NEW);
                for (VariableModel v : f.allVariables()) {
                    variableStatus.put(v.variableKey(f.frameKey()), ChangeStatus.NEW);
                }
                continue;
            }
            Map<String, VariableModel> oldVars = new LinkedHashMap<>();
            for (VariableModel v : old.allVariables()) {
                oldVars.put(v.name(), v);
            }
            boolean changed = f.lineNumber() != old.lineNumber();
            for (VariableModel v : f.allVariables()) {
                VariableModel oldVar = oldVars.remove(v.name());
                ChangeStatus status;
                if (oldVar == null) {
                    status = ChangeStatus.NEW;
                } else {
                    status = valueEquals(v.value(), oldVar.value()) ? ChangeStatus.UNCHANGED : ChangeStatus.CHANGED;
                }
                if (status != ChangeStatus.UNCHANGED) {
                    changed = true;
                }
                variableStatus.put(v.variableKey(f.frameKey()), status);
            }
            if (!oldVars.isEmpty()) {
                changed = true;
                deletedVariables.put(f.frameKey(), List.copyOf(oldVars.values()));
            }
            frameStatus.put(f.frameKey(), changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED);
        }

        for (StackFrameModel gone : prevByKey.values()) {
            frameStatus.put(gone.frameKey(), ChangeStatus.DELETED);
            deletedFrames.add(gone);
        }
    }

    private static void diffHeap(MemorySnapshot prev, MemorySnapshot curr,
            Map<Long, ChangeStatus> objectStatus, Map<Long, Map<String, ChangeStatus>> fieldStatus,
            Map<Long, BitSet> changedElements, List<HeapObjectModel> deletedObjects) {

        for (HeapObjectModel obj : curr.heap().values()) {
            HeapObjectModel old = prev.heap().get(obj.id());
            if (old == null) {
                objectStatus.put(obj.id(), ChangeStatus.NEW);
                continue;
            }
            // Either side unexplored: contents unknown — do not claim a change.
            if (!obj.explored() || !old.explored()) {
                objectStatus.put(obj.id(), ChangeStatus.UNCHANGED);
                continue;
            }
            objectStatus.put(obj.id(), diffExploredObject(old, obj, fieldStatus, changedElements));
        }

        for (HeapObjectModel old : prev.heap().values()) {
            if (!curr.heap().containsKey(old.id())) {
                objectStatus.put(old.id(), ChangeStatus.DELETED);
                deletedObjects.add(old);
            }
        }
    }

    private static ChangeStatus diffExploredObject(HeapObjectModel old, HeapObjectModel obj,
            Map<Long, Map<String, ChangeStatus>> fieldStatus, Map<Long, BitSet> changedElements) {

        // Both sides are explored (the caller gated on that) and share an id, so a
        // given id keeps its concrete shape across snapshots — old casts to obj's variant.
        return switch (obj) {
            // Immutable content per identity; same id means unchanged.
            case StringObject s -> ChangeStatus.UNCHANGED;
            case BoxedObject b -> ChangeStatus.UNCHANGED;
            // Unreachable: the caller returns before ever diffing an unexplored object.
            case StubObject s -> ChangeStatus.UNCHANGED;
            case ArrayObject arr -> diffArray((ArrayObject) old, arr, changedElements);
            // PLAIN and ENUM: enums can carry mutable fields, so both diff field-by-field.
            case FieldsObject fields -> diffFieldsObject((FieldsObject) old, fields, fieldStatus);
        };
    }

    private static ChangeStatus diffArray(ArrayObject old, ArrayObject arr, Map<Long, BitSet> changedElements) {
        boolean changed = arr.arrayLength() != old.arrayLength();
        BitSet bits = new BitSet();
        int common = Math.min(arr.elements().size(), old.elements().size());
        for (int i = 0; i < common; i++) {
            if (!valueEquals(arr.elements().get(i), old.elements().get(i))) {
                bits.set(i);
            }
        }
        if (!bits.isEmpty()) {
            changedElements.put(arr.id(), bits);
            changed = true;
        }
        return changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED;
    }

    private static ChangeStatus diffFieldsObject(FieldsObject old, FieldsObject obj,
            Map<Long, Map<String, ChangeStatus>> fieldStatus) {
        Map<String, ChangeStatus> byField = new HashMap<>();
        // Vanished fields (hot code replace) only flag the box CHANGED; no ghost rows inside a heap box.
        boolean changed = diffFields(old.fields(), obj.fields(), byField::put, deleted -> { });
        if (!byField.isEmpty()) {
            fieldStatus.put(obj.id(), byField);
        }
        return changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED;
    }

    /**
     * Diffs current against old fields keyed by {@link FieldModel#fieldKey()}: reports each current
     * field's status (NEW/UNCHANGED/CHANGED) to {@code onField} and the leftover deleted old fields
     * (in their original order) to {@code onDeleted}. Returns whether anything changed.
     */
    private static boolean diffFields(List<FieldModel> oldFieldList, List<FieldModel> currentFields,
            BiConsumer<String, ChangeStatus> onField, Consumer<List<FieldModel>> onDeleted) {

        Map<String, FieldModel> oldFields = new LinkedHashMap<>();
        for (FieldModel field : oldFieldList) {
            oldFields.put(field.fieldKey(), field);
        }
        boolean changed = false;
        for (FieldModel field : currentFields) {
            FieldModel oldField = oldFields.remove(field.fieldKey());
            ChangeStatus status = oldField == null ? ChangeStatus.NEW
                    : valueEquals(field.value(), oldField.value()) ? ChangeStatus.UNCHANGED : ChangeStatus.CHANGED;
            if (status != ChangeStatus.UNCHANGED) {
                changed = true;
            }
            onField.accept(field.fieldKey(), status);
        }
        if (!oldFields.isEmpty()) {
            changed = true;
            onDeleted.accept(List.copyOf(oldFields.values()));
        }
        return changed;
    }

    private static void diffStatics(MemorySnapshot prev, MemorySnapshot curr,
            Map<String, ChangeStatus> staticStatus, List<StaticsClassModel> deletedStaticClasses,
            Map<String, List<FieldModel>> deletedStaticFields) {

        Map<String, StaticsClassModel> prevByClass = new LinkedHashMap<>();
        for (StaticsClassModel c : prev.statics()) {
            prevByClass.put(c.className(), c);
        }

        for (StaticsClassModel c : curr.statics()) {
            StaticsClassModel old = prevByClass.remove(c.className());
            List<FieldModel> oldFields = old == null ? List.of() : old.fields();
            diffFields(oldFields, c.fields(), staticStatus::put, deleted -> {
                deletedStaticFields.put(c.className(), deleted);
                for (FieldModel f : deleted) {
                    staticStatus.put(f.fieldKey(), ChangeStatus.DELETED);
                }
            });
        }

        deletedStaticClasses.addAll(prevByClass.values());
    }

    /**
     * References compare by target identity — retargeting is the change; the target's
     * own mutation is reported on the target box, not on every inbound arrow.
     * Two unreadable values compare equal so read failures don't flag as changes.
     */
    static boolean valueEquals(ValueModel a, ValueModel b) {
        if (a == null || b == null) {
            return a == b;
        }
        return switch (a) {
            case PrimitiveValue pa -> b instanceof PrimitiveValue pb
                    && pa.typeName().equals(pb.typeName()) && pa.text().equals(pb.text());
            case NullValue na -> b instanceof NullValue;
            case HeapReference ra -> b instanceof HeapReference rb && ra.targetId() == rb.targetId();
            case UnreadableValue ua -> b instanceof UnreadableValue;
        };
    }
}
