package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.ethangodden.debugmemoryview.model.Box;
import com.github.ethangodden.debugmemoryview.model.Frame;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagramBuilder;
import com.github.ethangodden.debugmemoryview.model.Primitive;
import com.github.ethangodden.debugmemoryview.model.Reference;
import com.github.ethangodden.debugmemoryview.model.Section;
import com.github.ethangodden.debugmemoryview.model.Value;
import com.github.ethangodden.debugmemoryview.model.Variable;

/** JUnit 5 tests for {@link MemoryDiagramBuilder} — JDK-only, no debugger or editor. */
public class MemoryDiagramBuilderTest {

    // ---------- model builders ----------
    private static MemoryDiagramBuilder builder() {
        return new MemoryDiagramBuilder("target", "thread-1", "main", 1L);
    }

    private static Variable var(String symbolId, String identifier, Value value) {
        return new Variable(symbolId, identifier, "int", value);
    }

    // ---------- frames ----------
    @Test
    void testPushFrameWithVariablesKeepsOrderThisFirst() {
        MemoryDiagramBuilder b = builder();
        b.pushFrame("f0", "Demo.main() line 3",
                List.of(var("this", "this", new Primitive("Demo")), var("x", "x", new Primitive("1"))));
        MemoryDiagram d = b.build();
        Frame f = d.frames().get(0);
        assertEquals(2, f.variables().size(), "frame keeps both variable rows");
        assertEquals("this", f.variables().get(0).identifier(), "this row comes first, in supplied order");
    }

    @Test
    void testPushFrameTopOfStackFirst() {
        MemoryDiagramBuilder b = builder();
        b.pushFrame("top", "Demo.inner() line 9", List.of());
        b.pushFrame("bottom", "Demo.main() line 3", List.of());
        MemoryDiagram d = b.build();
        assertEquals("top", d.frames().get(0).frameToken(), "frames render in push order (top-of-stack first)");
    }

    @Test
    void testBodyOnlyFrame() {
        MemoryDiagramBuilder b = builder();
        b.pushFrame("native0", "Thread.sleep()", "(native method)");
        Frame f = b.build().frames().get(0);
        assertTrue(f.hasBody(), "a body-only frame reports hasBody");
        assertEquals("(native method)", f.body(), "body text is preserved");
    }

    // ---------- boxes ----------
    @Test
    void testAddBoxKeepsFieldsAndAppearsInHeap() {
        MemoryDiagramBuilder b = builder();
        b.addBox("p1", "P #1", List.of(var("P.a", "a", new Primitive("7"))), true, 0);
        Box box = b.build().heap().get(0);
        assertEquals("p1", box.boxToken(), "the added box is in the heap section");
        assertEquals("a", box.fields().get(0).identifier(), "box keeps its field rows");
    }

    @Test
    void testHeapOrderIsFirstMentionOrder() {
        MemoryDiagramBuilder b = builder();
        b.addBox("a", "A", List.of(), true, 0);
        b.addBox("b", "B", List.of(), true, 0);
        List<Box> heap = b.build().heap();
        assertEquals("a", heap.get(0).boxToken(), "heap boxes keep first-mention (slot) order: a before b");
    }

    @Test
    void testOmittedCountAndExploredCarry() {
        MemoryDiagramBuilder b = builder();
        b.addBox("arr", "int[5] #2", List.of(var("0", "0", new Primitive("9"))), true, 4);
        Box box = b.build().heap().get(0);
        assertEquals(4, box.omittedCount(), "omittedCount (capped elements) is carried on the box");
    }

    // ---------- references, stubs, dangling, forward ----------
    @Test
    void testReferenceResolvesToTargetBox() {
        MemoryDiagramBuilder b = builder();
        Reference ref = b.reference("t");
        b.fillBox("t", "T #5", List.of(), true, 0);
        MemoryDiagram d = b.build();
        assertEquals("t", d.resolve(ref).orElseThrow().boxToken(), "a reference resolves to its target box");
    }

    @Test
    void testForwardReferenceResolvesAfterFill() {
        MemoryDiagramBuilder b = builder();
        // Place the reference in a variable BEFORE the target box is filled.
        Reference ref = b.reference("late");
        b.pushFrame("f0", "Demo.main()", List.of(var("r", "r", ref)));
        b.fillBox("late", "Late #8", List.of(), true, 0);
        MemoryDiagram d = b.build();
        assertTrue(d.resolve(ref).isPresent(), "a forward reference resolves to the box filled after it");
    }

    @Test
    void testReserveBoxIsUnexploredStubThatStillResolves() {
        MemoryDiagramBuilder b = builder();
        Reference ref = b.reference("stub");
        b.reserveBox("stub", "Big #9");
        MemoryDiagram d = b.build();
        Box stub = d.resolve(ref).orElseThrow();
        assertFalse(stub.explored(), "a reserved-but-unfilled box is an unexplored stub");
    }

    @Test
    void testDanglingReferenceResolvesToNothing() {
        MemoryDiagramBuilder b = builder();
        Reference ref = b.reference("ghost"); // never reserved or filled
        MemoryDiagram d = b.build();
        assertTrue(d.resolve(ref).isEmpty(), "a reference to a never-provided cell is dangling (resolves to nothing)");
    }

    @Test
    void testRawReferenceToEmptyCellIsDangling() {
        MemoryDiagram d = builder().build();
        assertTrue(d.resolve(new Reference(Section.HEAP, 3)).isEmpty(), "a raw coordinate with no box is dangling");
    }

    // ---------- values ----------
    @Test
    void testAbsentValueIsNull() {
        MemoryDiagramBuilder b = builder();
        b.pushFrame("f0", "Demo.main()", List.of(var("n", "n", null)));
        Variable v = b.build().frames().get(0).variables().get(0);
        assertNull(v.value(), "an absent/uninitialized value is represented as a null Value");
    }
}
