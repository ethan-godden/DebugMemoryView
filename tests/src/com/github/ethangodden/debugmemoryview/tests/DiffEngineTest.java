package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.ethangodden.debugmemoryview.model.ExtractionStats;
import com.github.ethangodden.debugmemoryview.model.FieldModel;
import com.github.ethangodden.debugmemoryview.model.HeapObjectModel;
import com.github.ethangodden.debugmemoryview.model.HeapReference;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.NullValue;
import com.github.ethangodden.debugmemoryview.model.PrimitiveValue;
import com.github.ethangodden.debugmemoryview.model.StackFrameModel;
import com.github.ethangodden.debugmemoryview.model.StaticsClassModel;
import com.github.ethangodden.debugmemoryview.model.UnreadableValue;
import com.github.ethangodden.debugmemoryview.model.ValueModel;
import com.github.ethangodden.debugmemoryview.model.VariableModel;
import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.model.diff.DiffEngine;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;

/** JUnit 5 tests for DiffEngine / MemoryDiff. */
public class DiffEngineTest {

    // ---------- model builders ----------
    private static PrimitiveValue prim(String text) {
        return new PrimitiveValue("int", text);
    }

    private static VariableModel local(String name, ValueModel v) {
        return new VariableModel(name, "int", v);
    }

    private static StackFrameModel frame(int depthFromBottom, String method, int line, VariableModel thisVar,
            VariableModel... locals) {
        String key = StackFrameModel.frameKey(depthFromBottom, "Demo", method, "()V");
        return new StackFrameModel(key, "Demo", method, "Demo." + method + "() line " + line,
                line, depthFromBottom, false, false, thisVar == null, true, thisVar, List.of(locals));
    }

    private static Map<Long, HeapObjectModel> heap(HeapObjectModel... objects) {
        Map<Long, HeapObjectModel> m = new LinkedHashMap<>();
        for (HeapObjectModel o : objects) {
            m.put(o.id(), o);
        }
        return m;
    }

    private static MemorySnapshot snap(long seq, List<StackFrameModel> frames,
            Map<Long, HeapObjectModel> heap, List<StaticsClassModel> statics) {
        return new MemorySnapshot("target", "thread-1", "main", seq, 0L, frames, 0, heap, statics,
                null, ExtractionStats.empty());
    }

    private static MemorySnapshot snapOnThread(String threadKey, long seq, List<StackFrameModel> frames) {
        return new MemorySnapshot("target", threadKey, threadKey, seq, 0L, frames, 0,
                Map.of(), List.of(), null, ExtractionStats.empty());
    }

    private static FieldModel field(String declaring, String name, ValueModel v) {
        return new FieldModel(name, declaring, "int", v);
    }

    // ---------- tests ----------
    @Test
    void testInitialNullPrev() {
        StackFrameModel main = frame(0, "main", 10, null, local("x", prim("1")));
        MemorySnapshot s = snap(1,
                List.of(main),
                heap(HeapObjectModel.plain(1L, "P", "P", List.of(field("P", "a", prim("1"))), 0)),
                List.of(new StaticsClassModel("app.Config", "Config",
                        List.of(field("app.Config", "host", prim("h"))), 0)));
        MemoryDiff d = DiffEngine.diff(null, s);
        assertEquals(-1L, d.baselineSequence(), "initial: baselineSequence is -1");
        assertEquals(ChangeStatus.NEW, d.frameStatusOf(main.frameKey()), "initial: frame NEW");
        assertEquals(ChangeStatus.NEW, d.variableStatusOf(main.frameKey() + "#x"), "initial: variable NEW");
        assertEquals(ChangeStatus.NEW, d.objectStatusOf(1L), "initial: heap object NEW");
        assertEquals(ChangeStatus.NEW, d.staticStatusOf("app.Config.host"), "initial: static field NEW");
        assertTrue(d.deletedFrames().isEmpty() && d.deletedObjects().isEmpty()
                && d.deletedVariables().isEmpty() && d.deletedStaticClasses().isEmpty()
                && d.deletedStaticFields().isEmpty(), "initial: no ghosts");
    }

    @Test
    void testInitialThreadSwitch() {
        StackFrameModel main = frame(0, "main", 10, null, local("x", prim("1")));
        MemorySnapshot prev = snapOnThread("thread-A", 1, List.of(main));
        MemorySnapshot curr = snapOnThread("thread-B", 2, List.of(main));
        MemoryDiff d = DiffEngine.diff(prev, curr);
        assertEquals(-1L, d.baselineSequence(), "thread switch: falls back to initial diff");
        assertEquals(ChangeStatus.NEW, d.frameStatusOf(main.frameKey()), "thread switch: frame NEW");
        assertEquals(ChangeStatus.NEW, d.variableStatusOf(main.frameKey() + "#x"), "thread switch: variable NEW");
    }

    @Test
    void testVariableStatusesAndGhosts() {
        VariableModel thisPrev = new VariableModel("this", "Demo", new HeapReference(7L, "Demo"));
        VariableModel thisCurr = new VariableModel("this", "Demo", new HeapReference(7L, "Demo"));
        StackFrameModel prevF = frame(0, "run", 10, thisPrev,
                local("same", prim("1")), local("mut", prim("2")), local("gone", prim("3")));
        StackFrameModel currF = frame(0, "run", 10, thisCurr,
                local("same", prim("1")), local("mut", prim("9")), local("fresh", prim("4")));
        Map<Long, HeapObjectModel> h = heap(HeapObjectModel.stub(7L, "Demo", "Demo"));
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(prevF), h, List.of()),
                snap(2, List.of(currF), h, List.of()));
        String fk = currF.frameKey();
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(fk + "#this"), "vars: this unchanged");
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(fk + "#same"), "vars: same value UNCHANGED");
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf(fk + "#mut"), "vars: mutated CHANGED");
        assertEquals(ChangeStatus.NEW, d.variableStatusOf(fk + "#fresh"), "vars: appeared NEW");
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf(fk), "vars: frame CHANGED when vars changed");
        List<VariableModel> ghosts = d.deletedVariables().get(fk);
        assertTrue(ghosts != null && ghosts.size() == 1 && ghosts.get(0).name().equals("gone"),
                "vars: vanished variable ghost recorded under surviving frameKey");
        assertEquals(1L, d.baselineSequence(), "baselineSequence is prev.sequence()");
    }

    @Test
    void testFramePushKeepsSurvivorsUnchanged() {
        StackFrameModel mainPrev = frame(0, "main", 10, null, local("x", prim("1")));
        StackFrameModel mainCurr = frame(0, "main", 10, null, local("x", prim("1")));
        StackFrameModel helper = frame(1, "helper", 20, null, local("h", prim("5")));
        assertEquals(mainPrev.frameKey(), mainCurr.frameKey(), "push: bottom-numbered keys stable");
        // frames list index 0 = top of stack
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(mainPrev), Map.of(), List.of()),
                snap(2, List.of(helper, mainCurr), Map.of(), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d.frameStatusOf(mainCurr.frameKey()),
                "push: surviving frame stays UNCHANGED");
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(mainCurr.frameKey() + "#x"),
                "push: surviving frame's variable stays UNCHANGED");
        assertEquals(ChangeStatus.NEW, d.frameStatusOf(helper.frameKey()), "push: pushed frame NEW");
        assertEquals(ChangeStatus.NEW, d.variableStatusOf(helper.frameKey() + "#h"),
                "push: pushed frame's variables NEW");
        assertTrue(d.deletedFrames().isEmpty(), "push: no deleted frames");
    }

    @Test
    void testFramePopDeletesGhostFrame() {
        StackFrameModel main = frame(0, "main", 10, null, local("x", prim("1")));
        StackFrameModel helper = frame(1, "helper", 20, null, local("h", prim("5")));
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(helper, main), Map.of(), List.of()),
                snap(2, List.of(main), Map.of(), List.of()));
        assertEquals(ChangeStatus.DELETED, d.frameStatusOf(helper.frameKey()), "pop: popped frame DELETED");
        assertTrue(d.deletedFrames().size() == 1 && d.deletedFrames().get(0).frameKey().equals(helper.frameKey()),
                "pop: popped frame model in deletedFrames ghost list");
        assertEquals(ChangeStatus.UNCHANGED, d.frameStatusOf(main.frameKey()), "pop: survivor UNCHANGED");
    }

    @Test
    void testReturnThenCallDifferentMethodSameDepth() {
        StackFrameModel main = frame(0, "main", 10, null);
        StackFrameModel foo = frame(1, "foo", 20, null, local("f", prim("1")));
        StackFrameModel bar = frame(1, "bar", 30, null, local("b", prim("2")));
        assertTrue(!foo.frameKey().equals(bar.frameKey()), "same depth, different method => different keys");
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(foo, main), Map.of(), List.of()),
                snap(2, List.of(bar, main), Map.of(), List.of()));
        assertEquals(ChangeStatus.DELETED, d.frameStatusOf(foo.frameKey()), "return+call: old frame DELETED");
        assertEquals(ChangeStatus.NEW, d.frameStatusOf(bar.frameKey()), "return+call: new frame NEW");
        assertEquals(ChangeStatus.NEW, d.variableStatusOf(bar.frameKey() + "#b"), "return+call: new frame vars NEW");
        assertTrue(d.deletedFrames().size() == 1 && d.deletedFrames().get(0).frameKey().equals(foo.frameKey()),
                "return+call: old frame in ghost list");
    }

    @Test
    void testLineNumberChangeOnly() {
        StackFrameModel prevF = frame(0, "main", 10, null, local("x", prim("1")));
        StackFrameModel currF = frame(0, "main", 11, null, local("x", prim("1")));
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(prevF), Map.of(), List.of()),
                snap(2, List.of(currF), Map.of(), List.of()));
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf(currF.frameKey()), "line change: frame CHANGED");
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(currF.frameKey() + "#x"),
                "line change: variables still UNCHANGED");
    }

    @Test
    void testHeapFieldChange() {
        HeapObjectModel prevObj = HeapObjectModel.plain(1L, "Point", "Point",
                List.of(field("Point", "x", prim("1")), field("Point", "y", prim("2"))), 0);
        HeapObjectModel currObj = HeapObjectModel.plain(1L, "Point", "Point",
                List.of(field("Point", "x", prim("1")), field("Point", "y", prim("3"))), 0);
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), heap(prevObj), List.of()),
                snap(2, List.of(), heap(currObj), List.of()));
        assertEquals(ChangeStatus.CHANGED, d.objectStatusOf(1L), "heap: field change => object CHANGED");
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf(1L, "Point.x"), "heap: untouched field UNCHANGED");
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf(1L, "Point.y"), "heap: mutated field CHANGED");
        assertTrue(d.fieldStatus().containsKey(1L), "heap: fieldStatus has an entry for the changed object");

        // identical object -> UNCHANGED
        MemoryDiff d2 = DiffEngine.diff(snap(1, List.of(), heap(prevObj), List.of()),
                snap(2, List.of(), heap(prevObj), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d2.objectStatusOf(1L), "heap: identical object UNCHANGED");
    }

    @Test
    void testHeapHotCodeReplaceFields() {
        HeapObjectModel prevObj = HeapObjectModel.plain(2L, "P", "P",
                List.of(field("P", "a", prim("1")), field("P", "b", prim("2"))), 0);
        HeapObjectModel currAdded = HeapObjectModel.plain(2L, "P", "P",
                List.of(field("P", "a", prim("1")), field("P", "b", prim("2")), field("P", "c", prim("3"))), 0);
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), heap(prevObj), List.of()),
                snap(2, List.of(), heap(currAdded), List.of()));
        assertEquals(ChangeStatus.CHANGED, d.objectStatusOf(2L), "heap: appeared field => object CHANGED");
        assertEquals(ChangeStatus.NEW, d.fieldStatusOf(2L, "P.c"), "heap: appeared field NEW");

        HeapObjectModel currRemoved = HeapObjectModel.plain(2L, "P", "P",
                List.of(field("P", "a", prim("1"))), 0);
        MemoryDiff d2 = DiffEngine.diff(snap(1, List.of(), heap(prevObj), List.of()),
                snap(2, List.of(), heap(currRemoved), List.of()));
        assertEquals(ChangeStatus.CHANGED, d2.objectStatusOf(2L), "heap: vanished field => object CHANGED");
        assertEquals(ChangeStatus.UNCHANGED, d2.fieldStatusOf(2L, "P.a"), "heap: surviving field UNCHANGED");
    }

    @Test
    void testHeapDeletedGhost() {
        HeapObjectModel obj = HeapObjectModel.plain(3L, "P", "P", List.of(field("P", "a", prim("1"))), 0);
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), heap(obj), List.of()),
                snap(2, List.of(), heap(), List.of()));
        assertEquals(ChangeStatus.DELETED, d.objectStatusOf(3L), "heap: disappeared id DELETED");
        assertTrue(d.deletedObjects().size() == 1 && d.deletedObjects().get(0).id() == 3L,
                "heap: disappeared object model in deletedObjects ghost list");
    }

    @Test
    void testHeapStubEitherSideUnchanged() {
        HeapObjectModel stub = HeapObjectModel.stub(4L, "P", "P");
        HeapObjectModel explored = HeapObjectModel.plain(4L, "P", "P", List.of(field("P", "a", prim("1"))), 0);
        MemoryDiff d1 = DiffEngine.diff(snap(1, List.of(), heap(stub), List.of()),
                snap(2, List.of(), heap(explored), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d1.objectStatusOf(4L), "heap: stub->explored UNCHANGED");
        MemoryDiff d2 = DiffEngine.diff(snap(1, List.of(), heap(explored), List.of()),
                snap(2, List.of(), heap(stub), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d2.objectStatusOf(4L), "heap: explored->stub UNCHANGED");
        MemoryDiff d3 = DiffEngine.diff(snap(1, List.of(), heap(stub), List.of()),
                snap(2, List.of(), heap(stub), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d3.objectStatusOf(4L), "heap: stub->stub UNCHANGED");
    }

    @Test
    void testArrayDiff() {
        HeapObjectModel prevArr = HeapObjectModel.array(10L, "int[]", "int[]", 3,
                List.of(prim("1"), prim("2"), prim("3")), 0);
        HeapObjectModel currArr = HeapObjectModel.array(10L, "int[]", "int[]", 3,
                List.of(prim("1"), prim("9"), prim("3")), 0);
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), heap(prevArr), List.of()),
                snap(2, List.of(), heap(currArr), List.of()));
        assertEquals(ChangeStatus.CHANGED, d.objectStatusOf(10L), "array: element change => CHANGED");
        assertTrue(d.elementChanged(10L, 1), "array: changed index set in BitSet");
        assertTrue(!d.elementChanged(10L, 0) && !d.elementChanged(10L, 2), "array: untouched indices not set");

        // length change alone (elements in common range identical)
        HeapObjectModel grown = HeapObjectModel.array(11L, "int[]", "int[]", 4,
                List.of(prim("1"), prim("2"), prim("3"), prim("4")), 0);
        HeapObjectModel small = HeapObjectModel.array(11L, "int[]", "int[]", 3,
                List.of(prim("1"), prim("2"), prim("3")), 0);
        MemoryDiff d2 = DiffEngine.diff(snap(1, List.of(), heap(small), List.of()),
                snap(2, List.of(), heap(grown), List.of()));
        assertEquals(ChangeStatus.CHANGED, d2.objectStatusOf(11L), "array: length change => CHANGED");
        assertTrue(!d2.elementChanged(11L, 0) && !d2.elementChanged(11L, 3),
                "array: length-only change sets no element bits");

        // identical -> UNCHANGED
        MemoryDiff d3 = DiffEngine.diff(snap(1, List.of(), heap(prevArr), List.of()),
                snap(2, List.of(), heap(prevArr), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d3.objectStatusOf(10L), "array: identical UNCHANGED");
    }

    @Test
    void testStringBoxedSameIdUnchanged() {
        HeapObjectModel str = HeapObjectModel.string(20L, "hello", false);
        HeapObjectModel boxed = HeapObjectModel.boxed(21L, "java.lang.Integer", "Integer", "42", true);
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), heap(str, boxed), List.of()),
                snap(2, List.of(), heap(str, boxed), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d.objectStatusOf(20L), "string: same id UNCHANGED");
        assertEquals(ChangeStatus.UNCHANGED, d.objectStatusOf(21L), "boxed: same id UNCHANGED");

        // a string appearing under a fresh id is NEW
        HeapObjectModel str2 = HeapObjectModel.string(22L, "hello", false);
        MemoryDiff d2 = DiffEngine.diff(snap(1, List.of(), heap(str), List.of()),
                snap(2, List.of(), heap(str, str2), List.of()));
        assertEquals(ChangeStatus.NEW, d2.objectStatusOf(22L), "string: fresh id NEW");
    }

    @Test
    void testEnumFieldDiff() {
        HeapObjectModel prevEnum = HeapObjectModel.enumConstant(30L, "Color", "Color",
                List.of(field("Color", "uses", prim("1"))), 0, "RED");
        HeapObjectModel currEnum = HeapObjectModel.enumConstant(30L, "Color", "Color",
                List.of(field("Color", "uses", prim("2"))), 0, "RED");
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), heap(prevEnum), List.of()),
                snap(2, List.of(), heap(currEnum), List.of()));
        assertEquals(ChangeStatus.CHANGED, d.objectStatusOf(30L), "enum: mutable field change => CHANGED");
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf(30L, "Color.uses"), "enum: field marked CHANGED");
        MemoryDiff d2 = DiffEngine.diff(snap(1, List.of(), heap(prevEnum), List.of()),
                snap(2, List.of(), heap(prevEnum), List.of()));
        assertEquals(ChangeStatus.UNCHANGED, d2.objectStatusOf(30L), "enum: identical UNCHANGED");
    }

    @Test
    void testStatics() {
        StaticsClassModel prevConfig = new StaticsClassModel("app.Config", "Config", List.of(
                field("app.Config", "host", prim("h")),
                field("app.Config", "port", prim("1")),
                field("app.Config", "legacy", prim("9"))), 0);
        StaticsClassModel gone = new StaticsClassModel("app.Gone", "Gone",
                List.of(field("app.Gone", "g", prim("0"))), 0);
        StaticsClassModel currConfig = new StaticsClassModel("app.Config", "Config", List.of(
                field("app.Config", "host", prim("h")),
                field("app.Config", "port", prim("2")),
                field("app.Config", "fresh", prim("3"))), 0);
        StaticsClassModel added = new StaticsClassModel("app.New", "New",
                List.of(field("app.New", "n", prim("7"))), 0);

        MemoryDiff d = DiffEngine.diff(snap(1, List.of(), Map.of(), List.of(prevConfig, gone)),
                snap(2, List.of(), Map.of(), List.of(currConfig, added)));
        assertEquals(ChangeStatus.UNCHANGED, d.staticStatusOf("app.Config.host"), "statics: same value UNCHANGED");
        assertEquals(ChangeStatus.CHANGED, d.staticStatusOf("app.Config.port"), "statics: mutated CHANGED");
        assertEquals(ChangeStatus.NEW, d.staticStatusOf("app.Config.fresh"), "statics: appeared field NEW");
        assertEquals(ChangeStatus.NEW, d.staticStatusOf("app.New.n"), "statics: field of new class NEW");
        assertEquals(ChangeStatus.DELETED, d.staticStatusOf("app.Config.legacy"), "statics: vanished field DELETED");
        List<FieldModel> ghostFields = d.deletedStaticFields().get("app.Config");
        assertTrue(ghostFields != null && ghostFields.size() == 1 && ghostFields.get(0).name().equals("legacy"),
                "statics: vanished field in deletedStaticFields under surviving className");
        assertTrue(d.deletedStaticClasses().size() == 1
                && d.deletedStaticClasses().get(0).className().equals("app.Gone"),
                "statics: vanished class in deletedStaticClasses");
    }

    @Test
    void testValueEqualsSemanticsViaVariables() {
        StackFrameModel prevF = frame(0, "main", 5, null,
                local("u", new UnreadableValue("gc race")),
                local("r", new HeapReference(5L, "TypeA")),
                local("rt", new HeapReference(5L, "X")),
                local("n", NullValue.INSTANCE),
                local("p", prim("1")),
                local("pt", new PrimitiveValue("int", "1")),
                local("m", prim("1")));
        StackFrameModel currF = frame(0, "main", 5, null,
                local("u", new UnreadableValue("obsolete frame")),   // different error text
                local("r", new HeapReference(5L, "TypeB")),          // same target, different type name
                local("rt", new HeapReference(6L, "X")),             // retargeted
                local("n", NullValue.INSTANCE),
                local("p", prim("2")),                               // text changed
                local("pt", new PrimitiveValue("long", "1")),        // type changed
                local("m", NullValue.INSTANCE));                     // class changed
        Map<Long, HeapObjectModel> prevHeap = heap(HeapObjectModel.stub(5L, "X", "X"));
        Map<Long, HeapObjectModel> currHeap = heap(HeapObjectModel.stub(5L, "X", "X"),
                HeapObjectModel.stub(6L, "X", "X"));
        MemoryDiff d = DiffEngine.diff(snap(1, List.of(prevF), prevHeap, List.of()),
                snap(2, List.of(currF), currHeap, List.of()));
        String fk = currF.frameKey();
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(fk + "#u"),
                "valueEquals: two UnreadableValues equal regardless of error text");
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(fk + "#r"),
                "valueEquals: HeapReference compares by targetId only");
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf(fk + "#rt"),
                "valueEquals: retargeted reference is a change");
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf(fk + "#n"), "valueEquals: nulls equal");
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf(fk + "#p"), "valueEquals: primitive text change");
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf(fk + "#pt"), "valueEquals: primitive type change");
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf(fk + "#m"), "valueEquals: class mismatch is a change");
    }

    @Test
    void testGhostCarryOnce() {
        StackFrameModel mainWithVar = frame(0, "main", 10, null, local("x", prim("1")));
        StackFrameModel mainBare = frame(0, "main", 10, null);
        StackFrameModel helper = frame(1, "helper", 20, null);
        HeapObjectModel obj = HeapObjectModel.plain(100L, "P", "P", List.of(), 0);
        StaticsClassModel statics = new StaticsClassModel("app.S", "S",
                List.of(field("app.S", "f", prim("1"))), 0);
        StaticsClassModel staticsEmpty = new StaticsClassModel("app.S", "S", List.of(), 0);

        MemorySnapshot s1 = snap(1, List.of(helper, mainWithVar), heap(obj), List.of(statics));
        MemorySnapshot s2 = snap(2, List.of(mainBare), heap(), List.of(staticsEmpty));
        MemorySnapshot s3 = snap(3, List.of(mainBare), heap(), List.of(staticsEmpty));

        MemoryDiff d12 = DiffEngine.diff(s1, s2);
        assertTrue(!d12.deletedFrames().isEmpty() && !d12.deletedObjects().isEmpty()
                && !d12.deletedVariables().isEmpty() && !d12.deletedStaticFields().isEmpty(),
                "carry-once: diff N-1->N reports the ghosts");

        MemoryDiff d23 = DiffEngine.diff(s2, s3);
        assertEquals(2L, d23.baselineSequence(), "carry-once: baseline advances");
        assertTrue(d23.deletedFrames().isEmpty(), "carry-once: no frame ghosts from N-1 in diff N->N+1");
        assertTrue(d23.deletedObjects().isEmpty(), "carry-once: no object ghosts from N-1 in diff N->N+1");
        assertTrue(d23.deletedVariables().isEmpty(), "carry-once: no variable ghosts from N-1 in diff N->N+1");
        assertTrue(d23.deletedStaticClasses().isEmpty() && d23.deletedStaticFields().isEmpty(),
                "carry-once: no static ghosts from N-1 in diff N->N+1");
        assertEquals(ChangeStatus.UNCHANGED, d23.objectStatusOf(100L),
                "carry-once: long-gone object defaults UNCHANGED");
        assertEquals(ChangeStatus.UNCHANGED, d23.frameStatusOf(helper.frameKey()),
                "carry-once: long-gone frame defaults UNCHANGED");
    }
}
