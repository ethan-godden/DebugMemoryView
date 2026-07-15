package com.github.ethangodden.debugmemoryview.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.ethangodden.debugmemoryview.model.Box;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;

/**
 * Deterministic single-column ordering of the heap: boxes stack vertically in
 * the diagram's discovery order ({@link MemoryDiagram#heap()}, already emitted
 * statics-first then BFS order by the adapter), with {@link LayoutMemory}
 * keeping previously seen boxes in their remembered positions (sticky orderKey)
 * — existing tokens never move, new tokens append, ghosts keep their slots,
 * evicted tokens drop.
 *
 * PURE: imports from eclipseview.model and the JDK only (headless-testable).
 */
public final class HeapLayouter {

    private HeapLayouter() {
    }

    /**
     * Returns the heap column: box tokens ordered by the sticky orderKey. Ghosts
     * (DELETED boxes living only in the diff) keep their remembered positions; a
     * ghost never seen before appends after the live boxes.
     */
    public static List<String> assign(MemoryDiagram diagram, List<Box> ghostBoxes, LayoutMemory memory) {
        Set<String> live = new HashSet<>();
        for (Box box : diagram.heap()) {
            live.add(box.boxToken());
        }
        for (Box ghost : ghostBoxes) {
            live.add(ghost.boxToken());
        }
        memory.retainAll(live);

        List<String> ordered = new ArrayList<>(live.size());
        Set<String> seen = new HashSet<>();
        for (Box box : diagram.heap()) {
            String token = box.boxToken();
            memory.assign(token);
            ordered.add(token);
            seen.add(token);
        }
        for (Box ghost : ghostBoxes) {
            String token = ghost.boxToken();
            if (seen.add(token)) {
                memory.assign(token);
                ordered.add(token);
            }
        }
        ordered.sort(Comparator.comparingLong(token -> memory.orderKeyOf(token).longValue()));
        return ordered;
    }
}
