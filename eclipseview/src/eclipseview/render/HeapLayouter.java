package eclipseview.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import eclipseview.model.FieldModel;
import eclipseview.model.HeapObjectKind;
import eclipseview.model.HeapObjectModel;
import eclipseview.model.HeapReference;
import eclipseview.model.MemorySnapshot;
import eclipseview.model.StackFrameModel;
import eclipseview.model.StaticsClassModel;
import eclipseview.model.ValueModel;
import eclipseview.model.VariableModel;

/**
 * Deterministic single-column ordering of the heap: objects stack vertically
 * in BFS discovery order from the roots (frames top-down, then statics), with
 * {@link LayoutMemory} keeping previously seen objects in their remembered
 * positions (sticky orderKey) — existing ids never move, new ids append,
 * ghosts keep their slots, evicted ids drop.
 *
 * PURE: imports from eclipseview.model and the JDK only (headless-testable).
 */
public final class HeapLayouter {

    private HeapLayouter() {
    }

    /**
     * Returns the heap column: object ids ordered by the sticky orderKey. Ghosts
     * (DELETED objects living only in the diff) keep their remembered positions;
     * a ghost never seen before appends after the live objects.
     */
    public static List<Long> assign(MemorySnapshot snapshot, List<HeapObjectModel> ghosts, LayoutMemory memory) {
        // LinkedHashSet: iteration order == BFS discovery order (roots first).
        Set<Long> discovered = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();

        for (StackFrameModel frame : snapshot.frames()) {
            for (VariableModel variable : frame.allVariables()) {
                enqueueRoot(variable.value(), snapshot, discovered, queue);
            }
        }
        for (StaticsClassModel staticsClass : snapshot.statics()) {
            for (FieldModel field : staticsClass.fields()) {
                enqueueRoot(field.value(), snapshot, discovered, queue);
            }
        }
        bfs(snapshot, discovered, queue);

        // Objects unreachable from any rendered root (e.g. roots elided by caps):
        // seed them in heap map order so every object still gets a deterministic slot.
        for (Long id : snapshot.heap().keySet()) {
            if (discovered.add(id)) {
                queue.add(id);
                bfs(snapshot, discovered, queue);
            }
        }

        Set<Long> live = new HashSet<>(discovered);
        for (HeapObjectModel ghost : ghosts) {
            live.add(Long.valueOf(ghost.id()));
        }
        memory.retainAll(live);

        List<Long> ordered = new ArrayList<>(live.size());
        for (Long id : discovered) {
            memory.assign(id.longValue());
            ordered.add(id);
        }
        for (HeapObjectModel ghost : ghosts) {
            Long id = Long.valueOf(ghost.id());
            if (!discovered.contains(id)) {
                memory.assign(id.longValue());
                ordered.add(id);
            }
        }
        ordered.sort(Comparator.comparingLong(id -> memory.orderKeyOf(id.longValue()).longValue()));
        return ordered;
    }

    private static void bfs(MemorySnapshot snapshot, Set<Long> discovered, Deque<Long> queue) {
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            HeapObjectModel obj = snapshot.heap().get(id);
            if (obj == null) {
                continue;
            }
            for (ValueModel value : outgoing(obj)) {
                if (value instanceof HeapReference ref) {
                    Long target = Long.valueOf(ref.targetId());
                    if (snapshot.heap().containsKey(target) && discovered.add(target)) {
                        queue.add(target);
                    }
                }
            }
        }
    }

    private static List<ValueModel> outgoing(HeapObjectModel obj) {
        if (obj.kind() == HeapObjectKind.ARRAY) {
            return obj.elements();
        }
        if (obj.kind() == HeapObjectKind.PLAIN || obj.kind() == HeapObjectKind.ENUM) {
            List<ValueModel> values = new ArrayList<>(obj.fields().size());
            for (FieldModel field : obj.fields()) {
                values.add(field.value());
            }
            return values;
        }
        return List.of();
    }

    private static void enqueueRoot(ValueModel value, MemorySnapshot snapshot,
            Set<Long> discovered, Deque<Long> queue) {
        if (value instanceof HeapReference ref) {
            Long target = Long.valueOf(ref.targetId());
            if (snapshot.heap().containsKey(target) && discovered.add(target)) {
                queue.add(target);
            }
        }
    }
}
