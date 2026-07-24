package com.github.ethangodden.debugmemoryview.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;

/**
 * Deterministic single-column ordering of the heap: structs stack vertically in
 * the snapshot's discovery order ({@link MemorySnapshot#heap()}, already emitted
 * statics-first then BFS order by the adapter), with {@link LayoutMemory}
 * keeping previously seen structs in their remembered positions (sticky orderKey)
 * — existing ids never move, new ids append, ghosts keep their slots,
 * evicted ids drop.
 *
 * PURE: imports from eclipseview.model and the JDK only (headless-testable).
 */
public final class HeapLayouter {

    private HeapLayouter() {
    }

    /**
     * Returns the heap column: struct ids ordered by the sticky orderKey. Ghosts
     * (DELETED structs living only in the diff) keep their remembered positions; a
     * ghost never seen before appends after the live structs.
     */
    public static List<String> assign(MemorySnapshot snapshot, List<DisplayableStruct> ghosts, LayoutMemory memory) {
        Set<String> live = new HashSet<>();
        for (DisplayableStruct struct : snapshot.heap()) {
            live.add(struct.id());
        }
        for (DisplayableStruct ghost : ghosts) {
            live.add(ghost.id());
        }
        memory.retainAll(live);

        List<String> ordered = new ArrayList<>(live.size());
        Set<String> seen = new HashSet<>();
        for (DisplayableStruct struct : snapshot.heap()) {
            String id = struct.id();
            memory.assign(id);
            ordered.add(id);
            seen.add(id);
        }
        for (DisplayableStruct ghost : ghosts) {
            String id = ghost.id();
            if (seen.add(id)) {
                memory.assign(id);
                ordered.add(id);
            }
        }
        ordered.sort(Comparator.comparingLong(id -> memory.orderKeyOf(id).longValue()));
        return ordered;
    }
}
