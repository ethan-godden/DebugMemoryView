package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.ethangodden.debugmemoryview.model.FieldModel;
import com.github.ethangodden.debugmemoryview.model.HeapObjectModel;
import com.github.ethangodden.debugmemoryview.model.HeapReference;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.StackFrameModel;
import com.github.ethangodden.debugmemoryview.model.StaticsClassModel;
import com.github.ethangodden.debugmemoryview.model.VariableModel;
import com.github.ethangodden.debugmemoryview.render.HeapLayouter;
import com.github.ethangodden.debugmemoryview.render.LayoutMemory;

/** JUnit 5 tests for HeapLayouter + LayoutMemory. */
public class HeapLayouterTest {

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
            locals.add(new VariableModel("v" + i, "T", ref(rootIds[i])));
        }
        return new StackFrameModel(StackFrameModel.frameKey(0, "Demo", "main", "()V"),
                "Demo.main() line 1", 1, 0,
                false, false, true, null, locals);
    }

    private static MemorySnapshot snap(Map<Long, HeapObjectModel> heap, List<StackFrameModel> frames,
            List<StaticsClassModel> statics) {
        return new MemorySnapshot("target", "thread-1", "main", 1L, frames, 0, heap, statics);
    }

    private static List<Long> longs(long... expected) {
        List<Long> out = new ArrayList<>();
        for (long id : expected) {
            out.add(Long.valueOf(id));
        }
        return out;
    }

    // ---------- tests ----------
    @Test
    void testDeterministicOrder() {
        // roots: A(1), D(4); A -> B(2) -> C(3): single column in BFS discovery order
        MemorySnapshot s = snap(heap(node(1, 2), node(2, 3), node(3), node(4)),
                List.of(frameWithRoots(1, 4)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        assertEquals(longs(1, 4, 2, 3), order,
                "order: roots in variable order first, then BFS discovery of children");
        // same snapshot, fresh memory => identical layout
        List<Long> order2 = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        assertEquals(order, order2, "order: deterministic across runs with fresh memory");
    }

    @Test
    void testFramesBeforeStaticsRootOrder() {
        // frame root A(1); statics root S(9); frame roots enqueued first
        StaticsClassModel statics = new StaticsClassModel("app.S", "S",
                List.of(new FieldModel("s", "app.S", "T", ref(9))), 0);
        MemorySnapshot s = snap(heap(node(9), node(1)), List.of(frameWithRoots(1)), List.of(statics));
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        assertEquals(longs(1, 9), order, "order: frame roots ordered before statics roots");
    }

    @Test
    void testDeepChainDiscoveryOrder() {
        // chain 1 -> 2 -> ... -> 9: every object appears, in reference order
        HeapObjectModel[] chain = new HeapObjectModel[9];
        for (int i = 1; i <= 9; i++) {
            chain[i - 1] = i < 9 ? node(i, i + 1) : node(i);
        }
        MemorySnapshot s = snap(heap(chain), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        assertEquals(longs(1, 2, 3, 4, 5, 6, 7, 8, 9), order,
                "order: deep chains stack in discovery order, nothing dropped");
    }

    @Test
    void testUnreachableSeededDeterministically() {
        // root A(1); unreachable cluster U(50) -> V(51), in heap map order after A
        MemorySnapshot s = snap(heap(node(1), node(50, 51), node(51)),
                List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(), new LayoutMemory());
        assertEquals(longs(1, 50, 51), order,
                "unreachable: seeded in heap order after reachables, children following");
    }

    @Test
    void testSlotStabilityAcrossReassign() {
        LayoutMemory memory = new LayoutMemory();
        // step 1: root -> A(1) -> B(2)
        MemorySnapshot s1 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        List<Long> order1 = HeapLayouter.assign(s1, List.of(), memory);
        assertEquals(longs(1, 2), order1, "stability: initial layout");

        // step 2: references invert (root -> B(2) -> A(1)) and a new root C(3) appears.
        // Discovery order changes, but A and B keep their remembered positions; C appends.
        MemorySnapshot s2 = snap(heap(node(2, 1), node(1), node(3)),
                List.of(frameWithRoots(2, 3)), List.of());
        List<Long> order2 = HeapLayouter.assign(s2, List.of(), memory);
        assertEquals(longs(1, 2, 3), order2,
                "stability: existing ids keep position despite discovery-order swap; new id appends");
        assertEquals(Long.valueOf(0), memory.orderKeyOf(1), "stability: A orderKey verbatim");
        assertEquals(Long.valueOf(1), memory.orderKeyOf(2), "stability: B orderKey verbatim");
        Long keyC = memory.orderKeyOf(3);
        assertTrue(keyC != null && keyC.longValue() > memory.orderKeyOf(2).longValue(),
                "stability: new id sorts after every existing box");
    }

    @Test
    void testGhostKeepsRememberedSlot() {
        LayoutMemory memory = new LayoutMemory();
        MemorySnapshot s1 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        HeapLayouter.assign(s1, List.of(), memory); // remembers A then B

        // B deleted from the heap but rendered as a ghost: it must stay in its slot.
        MemorySnapshot s2 = snap(heap(node(1)), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s2, List.of(node(2)), memory);
        assertEquals(longs(1, 2), order, "ghost: keeps remembered slot");
        assertEquals(Long.valueOf(1), memory.orderKeyOf(2), "ghost: orderKey retained in memory");
    }

    @Test
    void testGhostNeverSeenAppends() {
        LayoutMemory memory = new LayoutMemory();
        MemorySnapshot s = snap(heap(node(1)), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s, List.of(node(99)), memory);
        assertEquals(longs(1, 99), order, "ghost: never-seen ghost appends after live objects");
    }

    @Test
    void testEviction() {
        LayoutMemory memory = new LayoutMemory();
        MemorySnapshot s1 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        HeapLayouter.assign(s1, List.of(), memory);
        assertTrue(memory.orderKeyOf(2) != null, "eviction: orderKey exists while live");

        // B gone and not a ghost: evicted from memory and absent from the column.
        MemorySnapshot s2 = snap(heap(node(1)), List.of(frameWithRoots(1)), List.of());
        List<Long> order = HeapLayouter.assign(s2, List.of(), memory);
        assertEquals(longs(1), order, "eviction: evicted id absent from the column");
        assertTrue(memory.orderKeyOf(2) == null, "eviction: orderKey removed from memory");

        // if B reappears later it is assigned fresh (appended), not restored.
        MemorySnapshot s3 = snap(heap(node(1, 2), node(2)), List.of(frameWithRoots(1)), List.of());
        List<Long> order3 = HeapLayouter.assign(s3, List.of(), memory);
        assertEquals(longs(1, 2), order3, "eviction: reappearing id re-assigned by discovery");
        assertTrue(memory.orderKeyOf(2).longValue() > memory.orderKeyOf(1).longValue(),
                "eviction: reappearing id gets a fresh orderKey");
    }

    @Test
    void testLayoutMemoryDirect() {
        LayoutMemory memory = new LayoutMemory();
        long a = memory.assign(1L);
        long b = memory.assign(2L);
        assertEquals(Long.valueOf(0), Long.valueOf(a), "memory: first assignment");
        assertEquals(Long.valueOf(1), Long.valueOf(b), "memory: second assignment increments orderKey");
        assertEquals(Long.valueOf(a), Long.valueOf(memory.assign(1L)), "memory: re-assign keeps orderKey verbatim");
        assertTrue(memory.orderKeyOf(42L) == null, "memory: unknown id has no orderKey");

        Set<Long> live = new HashSet<>();
        live.add(1L);
        memory.retainAll(live);
        assertTrue(memory.orderKeyOf(2L) == null && memory.orderKeyOf(1L) != null,
                "memory: retainAll evicts dead ids");

        memory.clear();
        assertTrue(memory.orderKeyOf(1L) == null, "memory: clear drops orderKeys");
        assertEquals(Long.valueOf(0), Long.valueOf(memory.assign(9L)), "memory: clear resets orderKey counter");
    }
}
