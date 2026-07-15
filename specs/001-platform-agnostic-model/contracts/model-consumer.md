# Contract: Model-Consumer API (model → diff / render)

**Feature**: 001-platform-agnostic-model | **Date**: 2026-07-14

What the diff and render layers may read. The point of the refactor: consumers depend **only** on the
neutral `MemoryDiagram` + `MemoryDiff` (FR-011) — zero Java/JDI/JDT/editor types. This contract replaces
today's six-way `HeapObjectModel` variant switch (in both `DiagramController.populateObject` and
`ObjectPreviewFigure.collectLines`) with a single uniform-box read.

## What a consumer reads

### From `MemoryDiagram`
- `frames()` — ordered `Frame`s (top-of-stack first). Each: `header`, `frameToken`, and either
  `variables()` (ordered rows) or `body()` (a display string).
- `heap()` — ordered `Box`es (statics classes included, D4). Each: `boxToken`, `header`, `fields()`
  (ordered rows), `explored()`, `omittedCount()`.
- `resolve(Reference) → Box | dangling` — the reference-resolution table (replaces `heap().get(targetId)`
  and the `objectFigures` `Long`→figure map). Consumers MUST resolve references through this, keyed on the
  box's identity **token**.
- `threadName()`, `sequence()`, `debugTargetToken()`, `threadToken()` — pass-through/plumbing.

### From a `Variable` (row)
- `identifier()` (display name), `typeLabel()` (display type / tooltip), `symbolId()` (diff key only),
  `value()` — one of:
  - `Primitive(value)` → draw `value` (char-capped) in the value box.
  - `Reference(section, index)` → resolve; if it resolves to a box, draw an arrow to it; if it resolves to
    **dangling**, draw the distinct dangling glyph (D6).
  - `null` → the absent/null value: empty box, no arrow (distinct from dangling).

### From `MemoryDiff`
- `frameStatusOf(frameToken)`, `variableStatusOf(frameToken, symbolId)`, `boxStatusOf(boxToken)`,
  `fieldStatusOf(boxToken, symbolId)` — all default to `UNCHANGED` for missing keys. `fieldStatusOf`
  covers object fields, array elements, and string chars uniformly (D8).
- ghost lists: `deletedFrames()`, `deletedVariables()` (surviving frameToken → vanished rows),
  `deletedBoxes()`. Rendered exactly once, translucent (transient per render — never accumulated).

## Presentation is inferred, not switched (FR-012 parity)

The renderer reproduces today's visuals from neutral signals only:

| Today's special case | Neutral signal the renderer keys on |
|---|---|
| single-value box (boxed) vs multi-row | a `Box` with exactly one field |
| array indexed cells | fields whose `identifier` is an index; component type in `typeLabel` |
| string char cells + "(truncated)" | fields (one per char) + `omittedCount` |
| enum leading constant row | the synthetic leading box-only field |
| stub "(not explored)" | `explored() == false` |
| "+N not captured" | `omittedCount()` |
| box title (`base[len] #id`, `simpleName #id`, `Class X`) | the frontend-composed `header` (+ token) |
| "(JVM cache)" | baked into the field's `Primitive` value string by the frontend |
| per-array-element CHANGED | `fieldStatusOf(boxToken, elementSymbolId)` |
| arrow target | `resolve(Reference)` → box; anchor as today (box's first row / header fallback) |
| cross-pane (stack→heap) vs intra-heap routing | source row's `Section` (STACK vs HEAP) |

## Invariants a consumer must honor (unchanged hard rules)

- Read only on the SWT UI thread (render), or on the worker Job (diff) — never mutate the diagram/diff.
- Treat `MemoryDiff` as transient per render; never accumulate ghosts.
- Reference equality is by **resolved target token**, not by raw `(section,index)` (so retargeting is the
  change; a target's mutation shows on the target box).
- Sticky layout and cap/collapse memory key on the **token** (String), not `long id`.

## Conformance tests

- Port `DiffEngineTest` onto the token-keyed `MemoryDiff` (frames/variables/boxes/fields/elements NEW/
  CHANGED/UNCHANGED/DELETED; reference retarget; two-unreadable-equal via `Primitive("?")`; ghost-once).
- Port `HeapLayouterTest` to token-keyed sticky ordering (slot stability across reassign, ghost slot,
  eviction, BFS discovery order).
- New: null vs valid-reference vs dangling render distinctly (D6 / SC-005).
- New: a builder-only diagram (no JDT) diffs + lays out correctly (SC-003/SC-004).
