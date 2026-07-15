# Phase 1 Data Model: Platform-Agnostic Memory Diagram Model

**Feature**: 001-platform-agnostic-model | **Date**: 2026-07-14

Neutral, JDK-only records + the builder that produces them. Names are indicative; exact record shapes
are settled here to drive `contracts/` and `tasks.md`. All types live under
`com.github.ethangodden.debugmemoryview.model`; the diff types under `...model.diff`. Nothing here may
import SWT/Draw2d/JFace/JDI (hard rule).

## Entity overview

```
MemoryDiagram
├── frames : List<Frame>              # STACK section, top-of-stack first
├── heap   : List<Box>                # HEAP section, in build/BFS order (statics classes included)
└── (identity/threading carry-over: debugTargetToken, threadToken, threadName, sequence)

Frame  = header + (variables | body) + frameToken            # a stack entry
Box    = boxToken + header + fields + explored + omittedCount # a uniform heap object (incl. statics class)
Cell   = Variable (a row): symbolId + identifier + typeLabel + value
Value  = Primitive(String) | Reference(Section, int index) | (absent = null)
Section = STACK | HEAP
```

Ordering is meaningful and preserved (FR-014): frame order (top first), field/variable order, array-element
order. `MemoryDiagram` is immutable once `build()` returns.

## Records

### `Section` (enum)
`STACK, HEAP` — the two columns. A cell address is `(section, index)`; each section numbers its rows
independently (D2). Statics classes are HEAP boxes (D4).

### `Value` (sealed interface) — permits `Primitive`, `Reference`
- **`Primitive(String value)`** — a display string (a primitive literal, an inlined string/boxed value, or
  `"?"` for an unreadable value — D5). No type name (type lives on the owning `Variable.typeLabel`).
- **`Reference(Section section, int index)`** — a cell coordinate (D2). Resolves to the box occupying row
  `index` of `section`; if none, the reference is **dangling** (D6). May be added before its target box
  exists (forward reference), resolved at `build()`.
- **absent** — a `Variable.value` of `null` means the null/uninitialized value (D5), rendered distinctly
  from a dangling reference.

### `Variable` (record) — a row/cell
`Variable(String symbolId, String identifier, String typeLabel, Value value)`
- `symbolId` — stable, opaque, frontend-assigned identity for diffing across snapshots (D1). Unique within
  its owning frame/box.
- `identifier` — display name (local name, field name, or array index as text).
- `typeLabel` — optional display type (declared type / array component type). Not an identity component.
- `value` — a `Value`, or `null` for the absent value.

### `Frame` (record)
`Frame(String frameToken, String header, List<Variable> variables, String body)`
- `frameToken` — stable identity for diffing frames (D1); JDT uses today's `frameKey`.
- `header` — display label (e.g. `"Demo.main() line 12"`), frontend-composed.
- `variables` — ordered rows (`this` first, then locals), OR empty when `body` is used.
- `body` — non-null only for a body-only frame (native/obsolete/unreadable frame): a display string shown in
  place of variables (D5). Exactly one of `variables`/`body` is populated.

### `Box` (record) — a uniform heap object (incl. a statics class)
`Box(String boxToken, String header, List<Variable> fields, boolean explored, int omittedCount)`
- `boxToken` — stable, opaque identity for diffing + reference resolution + sticky layout (D1); JDT uses
  `Long.toString(uniqueId)`.
- `header` — frontend-composed title (e.g. `"String #123"`, `"int[3] #10"`, `"Class Config"`).
- `fields` — ordered rows. Object fields, array elements (positional `identifier`), string chars (one per
  char), the boxed single value, and the enum leading constant row are ALL `Variable`s here (D3).
- `explored` — false for a stub/not-fully-captured box (header only, no fields); the differ never claims a
  change on an unexplored box (preserves today's `explored()` gate — D3/D8).
- `omittedCount` — count of fields/elements omitted for caps (drives the "+N not captured" row — D3).

### `MemoryDiagram` (record) — the built model (replaces `MemorySnapshot`)
`MemoryDiagram(String debugTargetToken, String threadToken, String threadName, long sequence,
List<Frame> frames, List<Box> heap)`
- Threading/identity carry-over fields keep the pipeline's per-thread baseline + sequence gating working
  unchanged. `frames` = STACK section (top first); `heap` = HEAP section (build order, statics included).
- Provides **reference resolution**: `resolve(Reference) → Box | dangling` by mapping `(section, index)` to
  the box whose row-span contains `index` (D2). (Rows are assigned during build from box order + field
  counts; the exact row-span bookkeeping is a build-time detail, not a stored per-cell field.)

## Builder API (`MemoryDiagramBuilder`)

Sole ingestion point (FR-001), JDK-only, order-preserving, stub-first, two-phase resolution (D7). Indicative
verbs (final signatures in `contracts/builder-api.md`):

- `pushFrame(String frameToken, String header, List<Variable> variables)` — a normal stack frame.
- `pushFrame(String frameToken, String header, String body)` — a body-only frame (native/obsolete/unreadable).
- `declareBox(String boxToken, Section section) → cell handle` / `reserveStub(...)` — **reserve** a box's cell
  (stub-first), so references resolve before the box is filled or if it is capped/unexplored.
- `fillBox(String boxToken, String header, List<Variable> fields, boolean explored, int omittedCount)` —
  fill a previously reserved box (the stub→real replacement).
- (Convenience) `addBox(...)` — reserve+fill in one call for the common case.
- `reference(String boxToken) → Reference` — obtain the cell coordinate of a (possibly not-yet-filled) box,
  for placing in a `Variable.value`; forward references allowed.
- `MemoryDiagram build()` — finalize: resolve all references to their occupant box (or dangling), freeze
  ordering, return the immutable diagram.

Construction guarantees the builder must uphold: insertion order preserved; a reference to an
unfilled/unreserved cell is **dangling**, not an error; a reference to a reserved-but-capped (unexplored)
box still resolves to that box (arrow lands on it); `build()` is the only place `(section,index)` rows are
assigned, so callers never compute row numbers by hand.

## Reworked diff (`MemoryDiff`, `DiffEngine`, `ChangeStatus`)

`ChangeStatus` unchanged: `NEW, CHANGED, UNCHANGED, DELETED`.

New `MemoryDiff` (keys are tokens/symbolIds, not `Long`) — D8:
- `baselineSequence : long`
- `frameStatus : Map<String, ChangeStatus>` — frameToken → status
- `variableStatus : Map<String, ChangeStatus>` — `frameToken + "#" + variable.symbolId` → status
- `boxStatus : Map<String, ChangeStatus>` — boxToken → status
- `fieldStatus : Map<String, Map<String, ChangeStatus>>` — boxToken → (field symbolId → status);
  **subsumes today's `changedElements` BitSet** (array elements are fields with positional symbolIds).
- deleted (ghost) lists: `List<Frame> deletedFrames`, `Map<String, List<Variable>> deletedVariables`
  (surviving frameToken → vanished rows), `List<Box> deletedBoxes`. (Statics are boxes, so no separate
  statics ghost lists.)

`DiffEngine.diff(prev, curr)`:
- Guard: null prev or different `threadToken` ⇒ `MemoryDiff.initial(curr)` (all NEW).
- Frames by `frameToken`; variables by `symbolId` within a frame; boxes by `boxToken`; fields by `symbolId`
  within a box; unexplored box on either side ⇒ UNCHANGED (never claim a change).
- **Reference equality** = resolved-target-token equality: `resolveA(refA).boxToken == resolveB(refB).boxToken`
  (two danglings compare equal; a dangling vs a resolved ref is a change). `Primitive` equal iff same string;
  absent(null) equal iff both absent (D5, D8). Retargeting shows on the referring slot; a target's own
  mutation shows on the target box (preserved).

## Old → new mapping (parity reference)

| Current (Java-shaped) | Neutral replacement |
|---|---|
| `MemorySnapshot(…, frames, Map<Long,HeapObjectModel> heap, List<StaticsClassModel> statics)` | `MemoryDiagram(…, List<Frame> frames, List<Box> heap)` — statics folded into `heap` (D4) |
| `long id` (JDI uniqueId) everywhere | opaque `boxToken`/`symbolId`/`frameToken` (String) — D1 |
| `HeapReference(long targetId, String targetTypeName)` | `Reference(Section, int index)` — cell coord; `targetTypeName` dropped (was unused) — D2 |
| `NullValue.INSTANCE` | `Variable.value == null` (absent) — D5 |
| `PrimitiveValue(typeName, text)` | `Primitive(text)`; type → `Variable.typeLabel` — D5 |
| `UnreadableValue(error)` | `Primitive("?")` at value level; body-only frame at frame level — D5 (open: D-open-2) |
| `HeapObjectModel.StubObject` | `Box` with `explored=false`, header only — D3 |
| `HeapObjectModel.ArrayObject(arrayLength, elements, elementsOmitted)` | `Box` with positional-identifier fields + `omittedCount` — D3 |
| `HeapObjectModel.StringObject(displayText, textTruncated)` | `Box` with one field per char + `omittedCount` (truncation) — D3 |
| `HeapObjectModel.BoxedObject(displayText, jvmCached)` | `Box` with a single field; `" (JVM cache)"` baked into the value string — D3 |
| `HeapObjectModel.PlainObject(fields, fieldsOmitted)` | `Box` with fields + `omittedCount` — D3 |
| `HeapObjectModel.EnumObject(…, enumConstantName)` | `Box` with a synthetic leading constant-name field + fields — D3 |
| `StaticsClassModel(className, simpleName, fields, fieldsOmitted)` | `Box` (HEAP) header `"Class X"` + fields + `omittedCount` — D4 |
| `StackFrameModel(frameKey, label, …, thisVariable, locals)` | `Frame(frameToken, header, variables[, body])`; `this` is `variables[0]` — D5 |
| `VariableModel(name, declaredTypeName, value)` | `Variable(symbolId=name, identifier=name, typeLabel=declaredTypeName, value)` |
| `FieldModel(name, declaringTypeName, declaredTypeName, value)` | `Variable(symbolId=declaringType.name, identifier=name, typeLabel=declaredTypeName, value)` |
| `MemoryDiff` (Long-keyed maps + `changedElements` BitSet + statics ghosts) | token-keyed maps; `fieldStatus` subsumes elements; statics ghosts fold into `deletedBoxes` — D8 |

## Validation rules (from requirements)

- Every `Reference` resolves to a box or to **dangling** at `build()`; a Java frontend never produces
  dangling (extractor stub-first guarantee), so dangling is exercised only by synthetic diagrams (FR-004/US3).
- A `Frame` has exactly one of `variables` / `body` (FR-001).
- `explored=false` ⇒ no fields and never diffed as changed (FR-012 stub parity).
- Ordering preserved end-to-end (FR-014).
- The neutral model + builder + diff are constructible and runnable with no editor/debugger (FR-013) — the
  JDK-only test suites build diagrams via the builder.
- No JDI/JDT/SWT/Draw2d types appear on any neutral type or in the diff (FR-002/FR-011).
