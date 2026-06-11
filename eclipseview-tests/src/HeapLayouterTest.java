import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eclipseview.model.ExtractionStats;
import eclipseview.model.FieldModel;
import eclipseview.model.HeapObjectModel;
import eclipseview.model.HeapReference;
import eclipseview.model.MemorySnapshot;
import eclipseview.model.StackFrameModel;
import eclipseview.model.StaticsClassModel;
import eclipseview.model.VariableKind;
import eclipseview.model.VariableModel;
import eclipseview.render.HeapLayouter;
import eclipseview.render.LayoutMemory;

/** Headless main()-based tests for HeapLayouter + LayoutMemory. Exits 1 on any failure. */
public final class HeapLayouterTest {

    // ---------- tiny assert helpers ----------
    private static final List<String> failures = new ArrayList<>();
    private static int checks = 0;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            failures.add(msg);
        }
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures.add(msg + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ---------- model builders ----------
    private static HeapReference ref(long id) {
        return new HeapReference(id, "T");
    }

    /** PLAIN object whose fields reference the given child ids in order. */
    private static HeapObjectModel node(long id, long... childIds) {
        List<FieldModel> fields = new ArrayList<>();
        for (int i = 0; i < childIds.length; i++) {
            fields.add(new FieldModel("f" + i, "T", "T", ref(childIds[i])));
        }
        return HeapObjectModel.plain(id, "T", "T", fields, 0);
    }

    private static Map<Long, HeapObjectModel> heap(HeapObjectModel... objects) {
        Map<Long, HeapObjectModel> m = new LinkedHashMap<>();
        for (HeapObjectModel o : objects) {
            m.put(o.id(), o);
        }
        return m;
    }

    private static StackFrameModel frameWithRoots(long... rootIds) {
        List<VariableModel> locals = new ArrayList<>();
        for (int i = 0; i < rootIds.length; i++) {
            locals.add(new VariableModel("v" + i, "T", ref(rootIds[i]), VariableKind.LOCAL));
        }
        return new StackFrameModel(StackFrameModel.frameKey(0, "Demo", "main", "()V"),
                "Demo", "main", "()V", "Demo.main() line 1", 1, 0,
                false, false, true, true, null, locals);
    }

    private static MemorySnapshot snap(Map<Long, HeapObjectModel> heap, List<StackFrameModel> frames,
            List<StaticsClassModel> statics) {
        return new MemorySnapshot("target", "thread-1", "main", 1L, 0L, frames, 0, heap, statics,
                null, ExtractionStats.empty());
    }

    private static List<Long> longs(long... expected) {
        List<Long> out = new ArrayList<>();
        for (long id : expected) {
            out.add(Long.valueOf(id));
        }
        return out;
    }

    // ---------- tests ----------
    public static void main(String[] args) {
        testDeterministicOrder();
        testFramesBeforeStaticsRootOrder();
        testDeepChainDiscoveryOrder();
        testUnreachableSeededDeterministically();
        testSlotStabilityAcrossReassign();
        testGhostKeepsRememberedSlot();
        testGhostNeverSeenAppends();
        testEviction();
        testLayoutMemoryDirect();

        System.out.println("HeapLayouterTest: " + checks + " checks, " + failures.size() + " failures");
        for (String f : failures) {
            System.out.println("  FAIL: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void testDeterministicOrder() {
        // roots: A(1), D(4); A -> B(2) -> C(3): single column in BFS discovery order
        MemorySnapshot s = snap(heap(node(1, 2), node(2, 3), node(3), node(4)),
                List.of(frameWithRoots(1, 4)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        checkEq(longs(1, 4, 2, 3), order,
                "order: roots in variable order first, then BFS discovery of children");
        // same snapshot, fresh memory => identical layout
        List<Long> order2 = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        checkEq(order, order2, "order: deterministic across runs with fresh memory");
    }

    private static void testFramesBeforeStaticsRootOrder() {
        // frame root A(1); statics root S(9); frame roots enqueued first
        StaticsClassModel statics = new StaticsClassModel("app.S", "S",
                List.of(new FieldModel("s", "app.S", "T", ref(9))), 0);
        MemorySnapshot s = snap(heap(node(9), node(1)), List.of(frameWithRoots(1)), List.of(statics));
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        checkEq(longs(1, 9), order, "order: frame roots ordered before statics roots");
    }

    private static void testDeepChainDiscoveryOrder() {
        // chain 1 -> 2 -> ... -> 9: every object appears, in reference order
        HeapObjectModel[] chain = new HeapObjectModel[9];
        for (int i = 1; i <= 9; i++) {
            chain[i - 1] = i < 9 ? node(i, i + 1) : node(i);
        }
        MemorySnapshot s = snap(heap(chain), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        checkEq(longs(1, 2, 3, 4, 5, 6, 7, 8, 9), order,
                "order: deep chains stack in discovery order, nothing dropped");
    }

    private static void testUnreachableSeededDeterministically() {
        // root A(1); unreachable cluster U(50) -> V(51), in heap map order after A
        MemorySnapshot s = snap(heap(node(1), node(50, 51), node(51)),
                List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        checkEq(longs(1, 50, 51), order,
                "unreachable: seeded in heap order after reachables, children following");
    }

    private static void testSlotStabilityAcrossReassign() {
        LayoutMemory memory = new LayoutMemory();
        // step 1: root -> A(1) -> B(2)
        MemorySnapshot s1 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        List<Long> order1 = HeapLayouter.assign(s1, List.of(), memory);
        checkEq(longs(1, 2), order1, "stability: initial layout");

        // step 2: references invert (root -> B(2) -> A(1)) and a new root C(3) appears.
        // Discovery order changes, but A and B keep their remembered positions; C appends.
        MemorySnapshot s2 = snap(heap(node(2, 1), node(1), node(3)),
                List.of(frameWithRoots(2, 3)), List.of());
        List<Long> order2 = HeapLayouter.assign(s2, List.of(), memory);
        checkEq(longs(1, 2, 3), order2,
                "stability: existing ids keep position despite discovery-order swap; new id appends");
        checkEq(Long.valueOf(0), memory.orderKeyOf(1), "stability: A orderKey verbatim");
        checkEq(Long.valueOf(1), memory.orderKeyOf(2), "stability: B orderKey verbatim");
        Long keyC = memory.orderKeyOf(3);
        check(keyC != null && keyC.longValue() > memory.orderKeyOf(2).longValue(),
                "stability: new id sorts after every existing box");
    }

    private static void testGhostKeepsRememberedSlot() {
        LayoutMemory memory = new LayoutMemory();
        MemorySnapshot s1 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        HeapLayouter.assign(s1, List.of(), memory); // remembers A then B

        // B deleted from the heap but rendered as a ghost: it must stay in its slot.
        MemorySnapshot s2 = snap(heap(node(1)), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s2, List.of(node(2)), memory);
        checkEq(longs(1, 2), order, "ghost: keeps remembered slot");
        checkEq(Long.valueOf(1), memory.orderKeyOf(2), "ghost: orderKey retained in memory");
    }

    private static void testGhostNeverSeenAppends() {
        LayoutMemory memory = new LayoutMemory();
        MemorySnapshot s = snap(heap(node(1)), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(node(99)), memory);
        checkEq(longs(1, 99), order, "ghost: never-seen ghost appends after live objects");
    }

    private static void testEviction() {
        LayoutMemory memory = new LayoutMemory();
        MemorySnapshot s1 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        HeapLayouter.assign(s1, List.of(), memory);
        check(memory.orderKeyOf(2) != null, "eviction: orderKey exists while live");

        // B gone and not a ghost: evicted from memory and absent from the column.
        MemorySnapshot s2 = snap(heap(node(1)), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s2, List.of(), memory);
        checkEq(longs(1), order, "eviction: evicted id absent from the column");
        check(memory.orderKeyOf(2) == null, "eviction: orderKey removed from memory");

        // if B reappears later it is assigned fresh (appended), not restored.
        MemorySnapshot s3 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        List<Long> order3 = HeapLayouter.assign(s3, List.of(), memory);
        checkEq(longs(1, 2), order3, "eviction: reappearing id re-assigned by discovery");
        check(memory.orderKeyOf(2).longValue() > memory.orderKeyOf(1).longValue(),
                "eviction: reappearing id gets a fresh orderKey");
    }

    private static void testLayoutMemoryDirect() {
        LayoutMemory memory = new LayoutMemory();
        long a = memory.assign(1L);
        long b = memory.assign(2L);
        checkEq(Long.valueOf(0), Long.valueOf(a), "memory: first assignment");
        checkEq(Long.valueOf(1), Long.valueOf(b), "memory: second assignment increments orderKey");
        checkEq(Long.valueOf(a), Long.valueOf(memory.assign(1L)), "memory: re-assign keeps orderKey verbatim");
        check(memory.orderKeyOf(42L) == null, "memory: unknown id has no orderKey");

        Set<Long> live = new HashSet<>();
        live.add(1L);
        memory.retainAll(live);
        check(memory.orderKeyOf(2L) == null && memory.orderKeyOf(1L) != null,
                "memory: retainAll evicts dead ids");

        memory.clear();
        check(memory.orderKeyOf(1L) == null, "memory: clear drops orderKeys");
        checkEq(Long.valueOf(0), Long.valueOf(memory.assign(9L)), "memory: clear resets orderKey counter");
    }
}
