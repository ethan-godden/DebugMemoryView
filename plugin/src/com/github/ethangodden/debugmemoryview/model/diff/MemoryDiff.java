package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;

/**
 * Change annotations for one {@link MemorySnapshot} relative to the previous one on the same thread.
 * All keys are opaque tokens (frame ids, struct ids, row keys) — never a JVM id. Ghosts (DELETED
 * items) carry full neutral models copied from the PREVIOUS snapshot and live only here; the
 * pipeline keeps one baseline per thread, so every deletion renders exactly once. Renderers must
 * treat the diff as transient per render and never accumulate ghosts.
 *
 * <p>{@code fieldStatus} covers object fields, array elements, and string chars uniformly (all are
 * {@link DisplayableVariable} rows keyed by their {@link #rowKeys row key} within their struct).
 */
public record MemoryDiff(
        Map<String, ChangeStatus> frameStatus,              // frame id -> status
        Map<String, ChangeStatus> variableStatus,           // frameId#rowKey -> status
        Map<String, ChangeStatus> structStatus,             // struct id -> status
        Map<String, Map<String, ChangeStatus>> fieldStatus, // struct id -> (rowKey -> status)
        List<DisplayableFrame> deletedFrames,
        Map<String, List<DisplayableVariable>> deletedVariables, // surviving frame id -> vanished rows
        List<DisplayableStruct> deletedStructs) {

    /** Composite key for a variable row: its frame id plus its row key. */
    public static String variableKey(String frameId, String rowKey) {
        return frameId + "#" + rowKey; //$NON-NLS-1$
    }

    /**
     * Diff keys for an ordered row list. A {@link DisplayableVariable} carries no separate symbol
     * id, so a row's cross-snapshot identity is its label, disambiguated by occurrence index when
     * the same label repeats (shadowed fields — pairing stays stable because the frontend emits
     * fields in a stable order). The differ and the renderer must key rows through this one helper.
     */
    public static List<String> rowKeys(List<DisplayableVariable> rows) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> keys = new ArrayList<>(rows.size());
        for (DisplayableVariable row : rows) {
            int occurrence = seen.merge(row.label(), Integer.valueOf(1), Integer::sum).intValue();
            keys.add(occurrence == 1 ? row.label() : row.label() + "#" + occurrence); //$NON-NLS-1$
        }
        return keys;
    }

    /** First suspend of a session / thread switch: everything renders as NEW. */
    public static MemoryDiff initial(MemorySnapshot d) {
        Map<String, ChangeStatus> frames = new HashMap<>();
        Map<String, ChangeStatus> variables = new HashMap<>();
        for (DisplayableThread t : d.threads()) {
            for (DisplayableFrame f : t.frames()) {
                frames.put(f.id(), ChangeStatus.NEW);
                for (String key : rowKeys(f.variables())) {
                    variables.put(variableKey(f.id(), key), ChangeStatus.NEW);
                }
            }
        }
        Map<String, ChangeStatus> structs = new HashMap<>();
        for (DisplayableStruct s : d.heap()) {
            structs.put(s.id(), ChangeStatus.NEW);
        }
        // Fields of NEW structs carry no entries; the struct-level NEW border communicates newness.
        return new MemoryDiff(Map.copyOf(frames), Map.copyOf(variables), Map.copyOf(structs),
                Map.of(), List.of(), Map.of(), List.of());
    }

    public ChangeStatus frameStatusOf(String frameId) {
        return frameStatus.getOrDefault(frameId, ChangeStatus.UNCHANGED);
    }

    public ChangeStatus variableStatusOf(String frameId, String rowKey) {
        return variableStatus.getOrDefault(variableKey(frameId, rowKey), ChangeStatus.UNCHANGED);
    }

    public ChangeStatus structStatusOf(String structId) {
        return structStatus.getOrDefault(structId, ChangeStatus.UNCHANGED);
    }

    /** Fields of a NEW struct have no entries; the struct-level NEW border communicates newness. */
    public ChangeStatus fieldStatusOf(String structId, String rowKey) {
        Map<String, ChangeStatus> byField = fieldStatus.get(structId);
        return byField == null ? ChangeStatus.UNCHANGED
                : byField.getOrDefault(rowKey, ChangeStatus.UNCHANGED);
    }
}
