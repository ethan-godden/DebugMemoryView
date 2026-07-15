package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.MemoryDiagramBuilder;
import com.github.ethangodden.debugmemoryview.model.Primitive;
import com.github.ethangodden.debugmemoryview.model.Value;
import com.github.ethangodden.debugmemoryview.model.Variable;
import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.model.diff.DiffEngine;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;

/** JUnit 5 tests for {@link DiffEngine} / {@link MemoryDiff} over the neutral model. */
public class DiffEngineTest {

    // ---------- factory helpers ----------

    private static final String UNREADABLE = "?"; //$NON-NLS-1$

    /** A primitive display-string value. */
    private static Primitive prim(String text) {
        return new Primitive(text);
    }

    /** A variable row whose symbolId doubles as its identifier. */
    private static Variable var(String symbolId, Value value) {
        return new Variable(symbolId, symbolId, "int", value); //$NON-NLS-1$
    }

    /** A fresh builder for the default thread ("thread-1"). */
    private static MemoryDiagramBuilder builder(long seq) {
        return new MemoryDiagramBuilder("target", "thread-1", "main", seq); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A fresh builder pinned to a given thread token. */
    private static MemoryDiagramBuilder builderOnThread(String threadToken, long seq) {
        return new MemoryDiagramBuilder("target", threadToken, threadToken, seq); //$NON-NLS-1$
    }

    // ---------- tests ----------

    @Test
    void testInitialNullPrev() {
        MemoryDiagramBuilder b = builder(1);
        b.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        b.addBox("1", "P", List.of(var("P.a", prim("1"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        b.addBox("statics:app.Config", "Class Config", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("app.Config.host", prim("h"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$
        MemoryDiagram curr = b.build();

        MemoryDiff d = DiffEngine.diff(null, curr);
        assertEquals(-1L, d.baselineSequence(), "initial: baselineSequence is -1"); //$NON-NLS-1$
        assertEquals(ChangeStatus.NEW, d.frameStatusOf("f#main"), "initial: frame NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.variableStatusOf("f#main", "x"), "initial: variable NEW"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ChangeStatus.NEW, d.boxStatusOf("1"), "initial: heap box NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.boxStatusOf("statics:app.Config"), //$NON-NLS-1$
                "initial: statics box NEW"); //$NON-NLS-1$
        assertTrue(d.deletedFrames().isEmpty() && d.deletedBoxes().isEmpty()
                && d.deletedVariables().isEmpty(), "initial: no ghosts"); //$NON-NLS-1$
    }

    @Test
    void testThreadSwitch() {
        MemoryDiagramBuilder pb = builderOnThread("thread-A", 1); //$NON-NLS-1$
        pb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builderOnThread("thread-B", 2); //$NON-NLS-1$
        cb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(-1L, d.baselineSequence(), "thread switch: falls back to initial diff"); //$NON-NLS-1$
        assertEquals(ChangeStatus.NEW, d.frameStatusOf("f#main"), "thread switch: frame NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.variableStatusOf("f#main", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "thread switch: variable NEW"); //$NON-NLS-1$
    }

    @Test
    void testVariableChangeMarksVariableAndFrameChanged() {
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("same", prim("1")), var("mut", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.pushFrame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("same", prim("1")), var("mut", prim("9")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#run", "same"), //$NON-NLS-1$ //$NON-NLS-2$
                "var change: untouched variable UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf("f#run", "mut"), //$NON-NLS-1$ //$NON-NLS-2$
                "var change: mutated variable CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf("f#run"), //$NON-NLS-1$
                "var change: enclosing frame CHANGED"); //$NON-NLS-1$
        assertEquals(1L, d.baselineSequence(), "var change: baselineSequence is prev.sequence()"); //$NON-NLS-1$
    }

    @Test
    void testVanishedVariableIsDeletedAndFrameChanged() {
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("keep", prim("1")), var("gone", prim("3")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.pushFrame("f#run", "Demo.run() line 10", List.of(var("keep", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        List<Variable> ghosts = d.deletedVariables().get("f#run"); //$NON-NLS-1$
        assertTrue(ghosts != null && ghosts.size() == 1 && ghosts.get(0).symbolId().equals("gone"), //$NON-NLS-1$
                "vanished var: recorded in deletedVariables under surviving frame token"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf("f#run"), //$NON-NLS-1$
                "vanished var: enclosing frame CHANGED"); //$NON-NLS-1$
    }

    @Test
    void testPushedFrameNewSurvivorUnchanged() {
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        // top-of-stack first: the pushed helper precedes main.
        cb.pushFrame("f#helper", "Demo.helper() line 20", List.of(var("h", prim("5")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        cb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.NEW, d.frameStatusOf("f#helper"), "push: pushed frame NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.variableStatusOf("f#helper", "h"), //$NON-NLS-1$ //$NON-NLS-2$
                "push: pushed frame's variable NEW"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.frameStatusOf("f#main"), //$NON-NLS-1$
                "push: surviving frame UNCHANGED"); //$NON-NLS-1$
        assertTrue(d.deletedFrames().isEmpty(), "push: no deleted frames"); //$NON-NLS-1$
    }

    @Test
    void testPoppedFrameDeleted() {
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#helper", "Demo.helper() line 20", List.of(var("h", prim("5")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        pb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.DELETED, d.frameStatusOf("f#helper"), "pop: popped frame DELETED"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(d.deletedFrames().size() == 1
                && d.deletedFrames().get(0).frameToken().equals("f#helper"), //$NON-NLS-1$
                "pop: popped frame model in deletedFrames"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.frameStatusOf("f#main"), "pop: survivor UNCHANGED"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    void testHeaderChangeOnSurvivingFrameChanged() {
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.pushFrame("f#main", "Demo.main() line 11", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf("f#main"), //$NON-NLS-1$
                "header change: frame CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#main", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "header change: variable still UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testBoxFieldChange() {
        MemoryDiagramBuilder pb = builder(1);
        pb.addBox("1", "Point", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("Point.x", prim("1")), var("Point.y", prim("2"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.addBox("1", "Point", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("Point.x", prim("1")), var("Point.y", prim("3"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.CHANGED, d.boxStatusOf("1"), //$NON-NLS-1$
                "box field: field change => box CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("1", "Point.x"), //$NON-NLS-1$ //$NON-NLS-2$
                "box field: untouched field UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("1", "Point.y"), //$NON-NLS-1$ //$NON-NLS-2$
                "box field: mutated field CHANGED"); //$NON-NLS-1$
    }

    @Test
    void testArrayElementChange() {
        // Arrays are boxes whose fields use positional identifiers "0","1",...
        MemoryDiagramBuilder pb = builder(1);
        pb.addBox("10", "int[3]", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("0", prim("1")), var("1", prim("2")), var("2", prim("3"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        MemoryDiagramBuilder cb = builder(2);
        cb.addBox("10", "int[3]", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("0", prim("1")), var("1", prim("9")), var("2", prim("3"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.CHANGED, d.boxStatusOf("10"), //$NON-NLS-1$
                "array: element change => box CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("10", "1"), //$NON-NLS-1$ //$NON-NLS-2$
                "array: changed element's field CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("10", "0"), //$NON-NLS-1$ //$NON-NLS-2$
                "array: untouched element UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testReferenceRetargetedIsRowChange() {
        // prev: r -> box A ; curr: r -> box B. A target-token change on the referring row.
        MemoryDiagramBuilder pb = builder(1);
        pb.reserveBox("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        pb.pushFrame("f#run", "Demo.run() line 5", List.of(var("r", pb.reference("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.reserveBox("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        cb.reserveBox("B", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        cb.pushFrame("f#run", "Demo.run() line 5", List.of(var("r", cb.reference("B")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "retarget: referring row CHANGED"); //$NON-NLS-1$
    }

    @Test
    void testReferenceSameTargetUnchanged() {
        MemoryDiagramBuilder pb = builder(1);
        pb.reserveBox("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        pb.pushFrame("f#run", "Demo.run() line 5", List.of(var("r", pb.reference("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.reserveBox("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        cb.pushFrame("f#run", "Demo.run() line 5", List.of(var("r", cb.reference("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "same target: referring row UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testUnreadablePrimitivesCompareEqual() {
        // The unreadable mapping: UnreadableValue -> Primitive("?"). Two of them are EQUAL.
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#run", "Demo.run() line 5", List.of(var("u", prim(UNREADABLE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MemoryDiagramBuilder cb = builder(2);
        cb.pushFrame("f#run", "Demo.run() line 5", List.of(var("u", prim(UNREADABLE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#run", "u"), //$NON-NLS-1$ //$NON-NLS-2$
                "unreadable: two Primitive(\"?\") compare EQUAL (no spurious change)"); //$NON-NLS-1$
    }

    @Test
    void testUnexploredBoxNeverChanged() {
        // A reserved (stub, explored=false) box is never claimed as changed, even opposite an
        // explored box with content.
        MemoryDiagramBuilder pb = builder(1);
        pb.reserveBox("4", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        MemoryDiagramBuilder cb = builder(2);
        cb.addBox("4", "P", List.of(var("P.a", prim("1"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d1 = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.UNCHANGED, d1.boxStatusOf("4"), //$NON-NLS-1$
                "unexplored: stub->explored UNCHANGED"); //$NON-NLS-1$

        MemoryDiff d2 = DiffEngine.diff(cb.build(), pb.build());
        assertEquals(ChangeStatus.UNCHANGED, d2.boxStatusOf("4"), //$NON-NLS-1$
                "unexplored: explored->stub UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testDeletedBoxGhostedOnce() {
        MemoryDiagramBuilder pb = builder(1);
        pb.addBox("3", "P", List.of(var("P.a", prim("1"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2); // heap now empty

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.DELETED, d.boxStatusOf("3"), "deleted box: DELETED status"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(d.deletedBoxes().size() == 1 && d.deletedBoxes().get(0).boxToken().equals("3"), //$NON-NLS-1$
                "deleted box: present in deletedBoxes exactly once"); //$NON-NLS-1$
    }

    @Test
    void testStaticsBoxBehavesLikeAnyBox() {
        // A statics-class box (token "statics:<class>") diffs by field like a regular box.
        MemoryDiagramBuilder pb = builder(1);
        pb.addBox("statics:app.Config", "Class Config", List.of( //$NON-NLS-1$ //$NON-NLS-2$
                var("app.Config.host", prim("h")), //$NON-NLS-1$ //$NON-NLS-2$
                var("app.Config.port", prim("1"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$
        MemoryDiagramBuilder cb = builder(2);
        cb.addBox("statics:app.Config", "Class Config", List.of( //$NON-NLS-1$ //$NON-NLS-2$
                var("app.Config.host", prim("h")), //$NON-NLS-1$ //$NON-NLS-2$
                var("app.Config.port", prim("2"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertEquals(ChangeStatus.CHANGED, d.boxStatusOf("statics:app.Config"), //$NON-NLS-1$
                "statics: field change => box CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("statics:app.Config", "app.Config.port"), //$NON-NLS-1$ //$NON-NLS-2$
                "statics: mutated field CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("statics:app.Config", "app.Config.host"), //$NON-NLS-1$ //$NON-NLS-2$
                "statics: untouched field UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testIdenticalDiagramUnchanged() {
        MemoryDiagramBuilder pb = builder(1);
        pb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        pb.addBox("1", "P", List.of(var("P.a", prim("1"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        MemoryDiagramBuilder cb = builder(2);
        cb.pushFrame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        cb.addBox("1", "P", List.of(var("P.a", prim("1"))), true, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(pb.build(), cb.build());
        assertTrue(d.frameStatusOf("f#main") == ChangeStatus.UNCHANGED //$NON-NLS-1$
                && d.boxStatusOf("1") == ChangeStatus.UNCHANGED //$NON-NLS-1$
                && d.deletedFrames().isEmpty() && d.deletedBoxes().isEmpty()
                && d.deletedVariables().isEmpty(),
                "identical: nothing changed and no ghosts"); //$NON-NLS-1$
    }
}
