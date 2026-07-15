package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.ethangodden.debugmemoryview.model.Box;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagramBuilder;
import com.github.ethangodden.debugmemoryview.model.Primitive;
import com.github.ethangodden.debugmemoryview.model.Reference;
import com.github.ethangodden.debugmemoryview.model.Value;
import com.github.ethangodden.debugmemoryview.model.Variable;
import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.model.diff.DiffEngine;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;
import com.github.ethangodden.debugmemoryview.render.HeapLayouter;
import com.github.ethangodden.debugmemoryview.render.LayoutMemory;

/**
 * Cross-cutting cutover tests, all JDK-only (no debugger, no Java-specific types) — this itself
 * demonstrates that the diff and layout layers depend only on the neutral model (US2 / SC-003 / SC-004).
 * Covers the ghost-reference remap, the model-level dangling/null/reference distinction (US3), and a
 * builder-only "second frontend" flow through diff + layout.
 */
public class NeutralModelCutoverTest {

    private static Variable var(String symbolId, Value value) {
        return new Variable(symbolId, symbolId, "T", value);
    }

    // ---------- US2: a diagram built purely via the builder diffs + lays out ----------
    @Test
    void testBuilderOnlyDiagramDiffsAndLaysOut() {
        MemoryDiagramBuilder b1 = new MemoryDiagramBuilder("t", "th", "main", 1L);
        b1.pushFrame("f", "f()", List.of(var("x", new Primitive("1"))));
        b1.addBox("P", "P #1", List.of(var("P.a", new Primitive("1"))), true, 0);
        MemoryDiagram v1 = b1.build();

        MemoryDiagramBuilder b2 = new MemoryDiagramBuilder("t", "th", "main", 2L);
        b2.pushFrame("f", "f()", List.of(var("x", new Primitive("2"))));
        b2.addBox("P", "P #1", List.of(var("P.a", new Primitive("1"))), true, 0);
        MemoryDiagram v2 = b2.build();

        MemoryDiff diff = DiffEngine.diff(v1, v2);
        assertEquals(ChangeStatus.CHANGED, diff.variableStatusOf("f", "x"),
                "second-frontend diagram: a changed local is diffed as CHANGED with no debugger");

        List<String> order = HeapLayouter.assign(v2, List.of(), new LayoutMemory());
        assertEquals(List.of("P"), order, "second-frontend diagram: the layouter orders its box tokens");
    }

    // ---------- US3: dangling vs null vs live reference are distinct in the model ----------
    @Test
    void testDanglingNullAndLiveReferenceAreDistinct() {
        MemoryDiagramBuilder b = new MemoryDiagramBuilder("t", "th", "main", 1L);
        Reference live = b.reference("R");
        Reference dangling = b.reference("ghost"); // never provided
        b.pushFrame("f", "f()", List.of(
                var("live", live),
                var("absent", null),
                var("dangling", dangling)));
        b.addBox("R", "R #1", List.of(), true, 0);
        MemoryDiagram d = b.build();

        assertTrue(d.resolve(live).isPresent(), "a live reference resolves to its target box");
        assertNull(d.frames().get(0).variables().get(1).value(), "the absent value is a null Value (not a reference)");
        assertTrue(d.resolve(dangling).isEmpty(), "a dangling reference resolves to nothing, distinct from null and from a live reference");
    }

    // ---------- ghost references are remapped into the current diagram's coordinates ----------
    @Test
    void testGhostReferenceRemapsToSurvivingTarget() {
        MemoryDiagramBuilder pb = new MemoryDiagramBuilder("t", "th", "main", 1L);
        Reference toA = pb.reference("A");
        Reference toB = pb.reference("B");
        pb.pushFrame("f", "f()", List.of(var("a", toA)));
        pb.addBox("A", "A #1", List.of(var("A.next", toB)), true, 0);
        pb.addBox("B", "B #2", List.of(), true, 0);
        MemoryDiagram prev = pb.build();

        // B survives, A is deleted; B now sits at a different slot than in prev.
        MemoryDiagramBuilder cb = new MemoryDiagramBuilder("t", "th", "main", 2L);
        cb.pushFrame("f", "f()", List.of());
        cb.addBox("B", "B #2", List.of(), true, 0);
        MemoryDiagram curr = cb.build();

        MemoryDiff diff = DiffEngine.diff(prev, curr);
        Box ghostA = diff.deletedBoxes().stream().filter(x -> x.boxToken().equals("A")).findFirst().orElseThrow();
        Value remapped = ghostA.fields().get(0).value();
        assertTrue(remapped instanceof Reference, "ghost A's outgoing value is still a reference");
        assertEquals("B", curr.resolve((Reference) remapped).orElseThrow().boxToken(),
                "ghost reference is remapped to the surviving target's CURRENT cell, not an unrelated box");
    }

    @Test
    void testGhostReferenceToGoneTargetBecomesAbsent() {
        MemoryDiagramBuilder pb = new MemoryDiagramBuilder("t", "th", "main", 1L);
        Reference toC = pb.reference("C");
        pb.pushFrame("f", "f()", List.of());
        pb.addBox("A", "A #1", List.of(var("A.next", toC)), true, 0);
        pb.addBox("C", "C #2", List.of(), true, 0);
        MemoryDiagram prev = pb.build();

        // Both A and its target C are gone; only an unrelated D remains.
        MemoryDiagramBuilder cb = new MemoryDiagramBuilder("t", "th", "main", 2L);
        cb.pushFrame("f", "f()", List.of());
        cb.addBox("D", "D #9", List.of(), true, 0);
        MemoryDiagram curr = cb.build();

        MemoryDiff diff = DiffEngine.diff(prev, curr);
        Box ghostA = diff.deletedBoxes().stream().filter(x -> x.boxToken().equals("A")).findFirst().orElseThrow();
        assertNull(ghostA.fields().get(0).value(),
                "a ghost reference whose target is also gone becomes absent (empty cell, no arrow), never a wrong-box arrow");
    }

    // ---------- an unexplored (stub) box never resolves as dangling ----------
    @Test
    void testStubTargetResolvesNotDangling() {
        MemoryDiagramBuilder b = new MemoryDiagramBuilder("t", "th", "main", 1L);
        Reference ref = b.reference("big");
        b.pushFrame("f", "f()", List.of(var("r", ref)));
        b.reserveBox("big", "Big #7"); // over-cap: reserved as an unexplored stub, never filled
        MemoryDiagram d = b.build();

        Box target = d.resolve(ref).orElseThrow();
        assertFalse(target.explored(), "an over-cap target resolves to an unexplored stub, not a dangling pointer");
    }
}
