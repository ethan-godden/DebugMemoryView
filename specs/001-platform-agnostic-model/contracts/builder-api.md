# Contract: Builder Ingestion API (frontend → model)

**Feature**: 001-platform-agnostic-model | **Date**: 2026-07-14

The single interface a debugger frontend uses to construct a memory diagram (FR-001). Today's Eclipse/JDT
extractor is one implementer; a future VS Code / other-language frontend is another. The builder is
JDK-only and lives in `com.github.ethangodden.debugmemoryview.model`. Signatures are indicative
(implementation may adjust names/overloads) but the **semantics below are the contract**.

## Interface (indicative)

```java
public final class MemoryDiagramBuilder {
    MemoryDiagramBuilder(String debugTargetToken, String threadToken, String threadName, long sequence);

    // --- frames (STACK section), in push order = top-of-stack first ---
    void pushFrame(String frameToken, String header, List<Variable> variables);
    void pushFrame(String frameToken, String header, String body);   // body-only (native/obsolete/unreadable)

    // --- heap boxes (HEAP section), stub-first ---
    void reserveBox(String boxToken, Section section);               // declare a cell before it is filled
    void fillBox(String boxToken, String header, List<Variable> fields,
                 boolean explored, int omittedCount);                // fill a reserved (or new) box
    // convenience: reserve+fill for the common fully-known case
    void addBox(String boxToken, String header, List<Variable> fields,
                boolean explored, int omittedCount, Section section);

    // --- references ---
    Reference reference(String boxToken);   // cell coord of a (possibly not-yet-filled) box

    MemoryDiagram build();                  // resolve refs, freeze, return immutable diagram
}
```

`Variable`, `Value` (`Primitive` | `Reference`), and `Section` are the neutral records from
`data-model.md`. A `Variable.value` of `null` is the absent/null value.

## Semantics (the binding part)

1. **Order preservation** — frames render top-of-stack first in push order; box order is preserved in the
   HEAP section; field/variable/element order within a box/frame is preserved (FR-014).
2. **Stub-first** — `reserveBox` declares a box's cell so a reference to it resolves even if the box is never
   filled (capped) or filled later. An unfilled reserved box behaves as an unexplored box (`explored=false`,
   header may be a placeholder). This mirrors today's `reference → drainHeapQueue` and guarantees Java never
   dangles.
3. **Forward references** — `reference(boxToken)` may be called before that box is reserved/filled; it is
   resolved at `build()`. A reference to a token that is never reserved/filled resolves to **dangling**
   (not an error) — the only way a Java frontend produces dangling is a bug; a future frontend produces it
   deliberately.
4. **Reference resolution at `build()`** — each `Reference(section, index)` maps to the box occupying row
   `index` of `section`. Row numbers are assigned by the builder from box order + field counts; **callers
   never compute rows** — they pass a `boxToken` to `reference(...)` and the builder returns the coordinate.
   (A future frontend that wants to author raw `(section, index)` coordinates directly — e.g. to point into
   the middle of a region — may construct `Reference` values itself; see D-open-1.)
5. **`explored` / `omittedCount`** — `explored=false` ⇒ the box has no fields and is never diffed as changed;
   `omittedCount>0` ⇒ the renderer shows a "+N not captured" row. These carry today's stub + cap behavior.
6. **Frame body vs variables** — `pushFrame(token, header, body)` sets a body-only frame; the two forms are
   mutually exclusive per frame.
7. **Immutability** — `build()` returns an immutable `MemoryDiagram`; the builder is single-use (or resets).
   No SWT/Draw2d/JFace/JDI type may appear in the API or its inputs (FR-002/FR-011).
8. **Threading** — the builder is used on the extraction worker Job (never the UI/debug-dispatch thread);
   it performs no I/O and no wire calls itself (the frontend adapter does the JDI reads and feeds strings).

## Eclipse/JDT adapter token conventions (parity)

The JDT adapter (in `core.extract`) supplies token values so diff/layout behavior matches today exactly:

| Token | JDT value |
|---|---|
| `debugTargetToken` / `threadToken` | today's `debugTargetKey` / `threadKey` |
| `frameToken` | today's `frameKey` (`depthFromBottom\|Type.method+signature`) |
| `boxToken` | `Long.toString(IJavaObject.getUniqueId())` |
| variable `symbolId` | local name (`"this"` for the receiver) — unique within the frame |
| field `symbolId` | today's `fieldKey` (`declaringType.name`) |
| array-element `symbolId` | the element index as text |

## Conformance tests (builder-only, JDK-only)

- Build a diagram with frames (variables + a body-only frame), boxes (plain/array/string/boxed/enum/statics
  as uniform fields), primitives, absent values, and references; assert `build()` yields the expected
  structure and ordering.
- Forward reference: `reference(t)` before `fillBox(t, …)` resolves to that box.
- Dangling: `reference(t)` for a never-declared `t` resolves to dangling.
- Stub: `reserveBox` without `fillBox` ⇒ `explored=false`, reference still resolves to it.
- Feed the result through `DiffEngine` and the pure layouter with no runtime (proves FR-013 / SC-003).
