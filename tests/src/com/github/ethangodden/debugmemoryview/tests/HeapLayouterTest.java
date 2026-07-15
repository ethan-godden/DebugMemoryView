package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import com.github.ethangodden.debugmemoryview.model.Box;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagramBuilder;
import com.github.ethangodden.debugmemoryview.render.HeapLayouter;
import com.github.ethangodden.debugmemoryview.render.LayoutMemory;

/**
 * JUnit 5 tests for the token-keyed {@link HeapLayouter} + {@link LayoutMemory}.
 *
 * <p>The new layouter trusts {@link MemoryDiagram#heap()}'s box order and stabilizes it with a
 * {@link LayoutMemory} keyed on each box's {@code boxToken}: a box remembered from an earlier
 * rebuild keeps its slot (sticky orderKey) even when the diagram reorders the heap; a never-seen
 * box appends after all remembered ones; ghost boxes (present only in the ghosts list) keep or get
 * a slot; tokens absent from the latest diagram+ghosts are evicted.
 */
public class HeapLayouterTest {

    // ---------- builder helpers ----------

    /** A fresh builder for a single-frame diagram (frame content is irrelevant to heap ordering). */
    private static MemoryDiagramBuilder builder() {
        MemoryDiagramBuilder b = new MemoryDiagramBuilder("target", "thread-1", "main", 1L);
        b.pushFrame("Demo.main()V:0", "Demo.main() line 1", List.of());
        return b;
    }

    /** Add a fully-explored, field-less box with the given token as both token and header. */
    private static void box(MemoryDiagramBuilder b, String token) {
        b.addBox(token, token, List.of(), true, 0);
    }

    /** A standalone ghost box (lives only in the ghosts list, never in the heap). */
    private static Box ghost(String token) {
        return new Box(token, token, List.of(), true, 0);
    }

    // ---------- heap order ----------

    @Test
    void testOrderFollowsDiagramBoxOrder() {
        // Boxes A, B, C added in that order => that is the column order.
        MemoryDiagramBuilder b = builder();
        box(b, "A");
        box(b, "B");
        box(b, "C");
        List<String> order = HeapLayouter.assign(b.build(), List.of(), new LayoutMemory());
        assertEquals(List.of("A", "B", "C"), order,
                "order: heap column follows the diagram's box order");
    }

    @Test
    void testOrderDeterministicWithFreshMemory() {
        MemoryDiagramBuilder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        box(b1, "C");
        List<String> order1 = HeapLayouter.assign(b1.build(), List.of(), new LayoutMemory());

        MemoryDiagramBuilder b2 = builder();
        box(b2, "A");
        box(b2, "B");
        box(b2, "C");
        List<String> order2 = HeapLayouter.assign(b2.build(), List.of(), new LayoutMemory());

        assertEquals(order1, order2, "order: deterministic across runs with fresh memory");
    }

    // ---------- sticky slot stability ----------

    @Test
    void testSlotStabilityAcrossReorderingRebuild() {
        LayoutMemory memory = new LayoutMemory();

        // step 1: diagram order A, B.
        MemoryDiagramBuilder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        List<String> order1 = HeapLayouter.assign(b1.build(), List.of(), memory);
        assertEquals(List.of("A", "B"), order1, "stability: initial column order");
        Long keyA = memory.orderKeyOf("A");
        Long keyB = memory.orderKeyOf("B");

        // step 2: the diagram reorders the heap to B, A. Remembered tokens keep their earlier slots,
        // so the rendered column is unchanged (A before B) despite the diagram's swap.
        MemoryDiagramBuilder b2 = builder();
        box(b2, "B");
        box(b2, "A");
        List<String> order2 = HeapLayouter.assign(b2.build(), List.of(), memory);
        assertEquals(List.of("A", "B"), order2,
                "stability: remembered tokens keep their slots despite diagram reordering");
        assertEquals(keyA, memory.orderKeyOf("A"), "stability: A orderKey retained verbatim");
        assertEquals(keyB, memory.orderKeyOf("B"), "stability: B orderKey retained verbatim");
    }

    @Test
    void testNewBoxAppendsAfterExisting() {
        LayoutMemory memory = new LayoutMemory();

        MemoryDiagramBuilder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        HeapLayouter.assign(b1.build(), List.of(), memory);

        // step 2: a NEW box C appears (diagram lists it first) — it must append after A and B.
        MemoryDiagramBuilder b2 = builder();
        box(b2, "C");
        box(b2, "A");
        box(b2, "B");
        List<String> order = HeapLayouter.assign(b2.build(), List.of(), memory);
        assertEquals(List.of("A", "B", "C"), order,
                "append: a new box sorts after every remembered box");
        assertTrue(memory.orderKeyOf("C").longValue() > memory.orderKeyOf("B").longValue(),
                "append: new box gets an orderKey after all existing ones");
    }

    // ---------- ghosts ----------

    @Test
    void testGhostKeepsRememberedSlot() {
        LayoutMemory memory = new LayoutMemory();

        MemoryDiagramBuilder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        HeapLayouter.assign(b1.build(), List.of(), memory); // remembers A then B
        Long keyB = memory.orderKeyOf("B");

        // step 2: B is gone from the heap but rendered as a ghost — it keeps its remembered slot.
        MemoryDiagramBuilder b2 = builder();
        box(b2, "A");
        List<String> order = HeapLayouter.assign(b2.build(), List.of(ghost("B")), memory);
        assertEquals(List.of("A", "B"), order, "ghost: keeps its remembered slot");
        assertEquals(keyB, memory.orderKeyOf("B"), "ghost: orderKey retained in memory");
    }

    @Test
    void testNeverSeenGhostAppends() {
        LayoutMemory memory = new LayoutMemory();

        MemoryDiagramBuilder b = builder();
        box(b, "A");
        // A ghost never seen before appends after the live boxes.
        List<String> order = HeapLayouter.assign(b.build(), List.of(ghost("G")), memory);
        assertEquals(List.of("A", "G"), order, "ghost: a never-seen ghost appends after live boxes");
        assertTrue(memory.orderKeyOf("G").longValue() > memory.orderKeyOf("A").longValue(),
                "ghost: never-seen ghost gets an orderKey after the live boxes");
    }

    // ---------- eviction ----------

    @Test
    void testEvictionDropsAbsentTokens() {
        LayoutMemory memory = new LayoutMemory();

        MemoryDiagramBuilder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        HeapLayouter.assign(b1.build(), List.of(), memory);
        assertNotNull(memory.orderKeyOf("B"), "eviction: orderKey exists while live");

        // step 2: B is absent from both the diagram and the ghosts => evicted and off the column.
        MemoryDiagramBuilder b2 = builder();
        box(b2, "A");
        List<String> order = HeapLayouter.assign(b2.build(), List.of(), memory);
        assertEquals(List.of("A"), order, "eviction: evicted token absent from the column");
        assertNull(memory.orderKeyOf("B"), "eviction: orderKey removed from memory");

        // step 3: if B reappears it is assigned fresh (appended), not restored to its old slot.
        MemoryDiagramBuilder b3 = builder();
        box(b3, "B");
        box(b3, "A");
        List<String> order3 = HeapLayouter.assign(b3.build(), List.of(), memory);
        assertEquals(List.of("A", "B"), order3, "eviction: reappearing token re-assigned after existing");
        assertTrue(memory.orderKeyOf("B").longValue() > memory.orderKeyOf("A").longValue(),
                "eviction: reappearing token gets a fresh orderKey");
    }

    // ---------- LayoutMemory direct ----------

    @Test
    void testLayoutMemoryDirect() {
        LayoutMemory memory = new LayoutMemory();
        memory.assign("A");
        memory.assign("B");
        assertEquals(Long.valueOf(0), memory.orderKeyOf("A"), "memory: first assignment gets orderKey 0");
        assertEquals(Long.valueOf(1), memory.orderKeyOf("B"), "memory: second assignment increments orderKey");
        memory.assign("A");
        assertEquals(Long.valueOf(0), memory.orderKeyOf("A"), "memory: re-assign keeps orderKey verbatim");
        assertNull(memory.orderKeyOf("Z"), "memory: unknown token has no orderKey");

        memory.retainAll(Set.of("A"));
        assertNull(memory.orderKeyOf("B"), "memory: retainAll evicts absent tokens");
        assertNotNull(memory.orderKeyOf("A"), "memory: retainAll keeps live tokens");

        memory.clear();
        assertNull(memory.orderKeyOf("A"), "memory: clear drops orderKeys");
        memory.assign("C");
        assertEquals(Long.valueOf(0), memory.orderKeyOf("C"), "memory: clear resets the orderKey counter");
    }
}
