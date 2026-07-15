# Phase 0 Research: Platform-Agnostic Memory Diagram Model

**Feature**: 001-platform-agnostic-model | **Date**: 2026-07-14

This document records the current-code map that the design rests on, then the design decisions
(each as Decision / Rationale / Alternatives), the open points flagged for confirmation, and the
risks. It resolves every unknown needed to write `data-model.md`, the contracts, and `quickstart.md`.

## Current architecture map (as-built)

One-way flow (unchanged by this refactor at the boundary level):

```
DebugContextTracker → SnapshotPipeline(Job) → SnapshotExtractor(JDI walk) → MemorySnapshot
                                                                                  │
MemoryDiagramView ← DiagramController(render) ← MemoryDiff ← DiffEngine ←─────────┘
   (UI thread, gated by SnapshotPipeline.publish sequence check)
```

**Identity today is a raw JDI `long id`** (`IJavaObject.getUniqueId()` → `ObjectReference.uniqueID()`),
and it is load-bearing in three independent places at once:
- **Heap map keying + aliasing/cycle termination** — `MemorySnapshot.heap()` is `Map<Long,HeapObjectModel>`;
  same id ⇒ same node ⇒ cycles terminate.
- **Reference resolution in render** — `DiagramController.objectFigures : Map<Long,HeapObjectFigure>`;
  arrows resolve by `HeapReference.targetId()` only (`targetTypeName` is dead/unused).
- **Diff reference-equality** — `DiffEngine.valueEquals` compares `HeapReference`s by `targetId` (retarget
  is the change; a target's own mutation shows on the target box). Sticky layout (`LayoutMemory.orderKeys`)
  and cap/collapse persistence (`ExpansionMemory` keys `"obj:"+id` …) also key on `long id`.

**Diff identity per level**: frames by `frameKey` (`depthFromBottom|Type.method+signature`), variables by
`name` within a frame (recorded under `variableKey = frameKey#name`), heap objects by `long id`, fields by
`fieldKey = declaringType.name`, array elements by positional index (a `BitSet` in `changedElements`),
statics at class level by `className` and field level by `fieldKey`.

**Heap shape today**: `HeapObjectModel` is a sealed 6-variant hierarchy (Stub / Array / String / Boxed /
Plain / Enum), each carrying `long id`, `typeName`, `simpleName` plus variant-specific fields
(`displayText`/`textTruncated`, `displayText`/`jvmCached`, `arrayLength`/`elements`/`elementsOmitted`,
`fields`/`fieldsOmitted`, `enumConstantName`). `ValueModel` is a sealed 4-variant hierarchy (Primitive /
Null / HeapReference / Unreadable). The renderer **pattern-matches all six heap variants twice** — once in
`DiagramController.populateObject` and again in `ObjectPreviewFigure.collectLines` (hover tooltip) — which
must be migrated in lockstep.

**Threading / immutability invariants to preserve** (hard rules): JDI touched only in
`SnapshotJob.run → SnapshotExtractor`; `DebugContextTracker` makes no wire calls; publication is gated by
`SnapshotPipeline.publish`'s `publishedSeq != seq.get()` check inside `Display.asyncExec`; snapshots/diffs
immutable; ghosts (from the single per-thread baseline diff) rendered exactly once; `MemoryDiagramView` is
nearly insulated — it touches only `MemorySnapshot` + `MemoryDiff` as opaque pass-through to the controller.

**Blast radius** (files referencing each type; from the survey): `DiagramController` (render) is the single
largest consumer — it references every model/diff type except `DiffEngine`. `SnapshotExtractor` is the sole
producer. `ValueModel` radiates widest (16 files). The UI proper (`ViewSettings`, preference page,
initializer) references **no** model types. Plugin source is 51 `.java` files. Tests: `DiffEngineTest` and
`HeapLayouterTest` build models by directly calling record constructors + `HeapObjectModel.*` factories and
are **JDK-only**; `ColumnsLayoutTest` drives bare Draw2d and never touches models.

## Design decisions

### D1 — Opaque identity token replaces the JDI `long id`

**Decision**: Every heap box carries a stable, opaque **identity token** (modeled as a `String`), and every
variable/field carries a stable **symbolId** (`String`); frames carry a stable **frame token** (`String`).
The diff and render layers treat all tokens opaquely. The Eclipse/JDT adapter supplies the token values:
box token = `Long.toString(getUniqueId())`, frame token = today's `frameKey`, variable symbolId = the local
name (unique within a frame) / `"this"`, field symbolId = today's `fieldKey`, array-element symbolId = the
index. Display of `#id` in titles is a *frontend* choice baked into the box header string, not an identity
concern.

**Rationale**: Restores exactly today's cross-snapshot identity (so diff/aliasing/sticky-layout behavior is
preserved) while removing the JVM-specific `long`. A `String` token is the most neutral choice any future
frontend can mint. Frames need a token too (the sketch omitted it) because the differ classifies frames
NEW/CHANGED/DELETED and renders ghost frames — a natural extension of FR-007.

**Alternatives**: (a) Keep `long id` — rejected, it is the JVM-specific concept FR-002 removes. (b) A typed
`record IdentityToken(String)` wrapper — viable and slightly safer than a bare `String`; deferred to
implementation taste (either satisfies the contract). (c) Structural/content identity — rejected in clarify
(fragile; identical objects collide).

**Impact**: re-key `objectFigures`, `modelById`, `LayoutMemory.orderKeys`, `ExpansionMemory` cap/collapse
keys, and all `MemoryDiff` maps from `Long` to the token. Token stability across snapshots is mandatory —
an unstable token breaks sticky layout (boxes jump) and cap/collapse persistence. JDT's `uniqueId` is stable
for a VM's lifetime, so parity holds.

### D2 — References are `(section, row)` cells; resolution maps cell → box → token; renderer keeps layout

**Decision**: A `Reference` value addresses a cell = **`(Section section, int index)`** where `section` is a
column (STACK or HEAP) and `index` is a **row within that section**, numbered **independently per section**
(an empty row in one column does not shift the other — per the clarify answer). During build, the builder
records which box occupies which rows; **reference resolution** maps a cell to the box whose row-span
contains `index` (→ its identity token), or to *nothing* (→ dangling). Two layers consume this:
- **Diff** compares references by their **resolved target token** (not by raw coordinate), preserving
  today's "retarget = change; target mutation shows on the target box" semantics.
- **Render** resolves the cell to the target figure and draws the arrow; **vertical placement and sticky
  stability remain the renderer's job** (`LayoutMemory`, now keyed on the token). The `(section, row)`
  coordinate is a within-snapshot resolution key, not an authoritative pixel position.

**Rationale**: Honors the user's positional/grid mental model at the *addressing* level (cells, per-column
independent rows, empty cell = dangling, forward references resolve to the eventual occupant) while keeping
the existing sticky-layout + BFS + heap-cap-roots-first engine that makes the learner's diagram stable
(FR-009). It also keeps the two-phase "collect refs → resolve after all boxes exist" pattern the renderer
already uses, and preserves reference-equality semantics the diff tests assert.

**Alternatives**: (a) **Frontend-authoritative absolute grid** — the model dictates exact rows/gaps and the
renderer draws them verbatim. Rejected *for now*: it moves sticky ordering, BFS, and cap-survival into every
frontend adapter, risking the P1 parity behavior and duplicating layout logic. (Revisit if a future frontend
needs pixel-exact control.) (b) **Reference = target identity token directly** (the clarify Q3 "target's
stable id" option) — rejected by the user in favor of cells; cells are what enable dangling/forward and the
memory-grid teaching model.

### D3 — Uniform "header + ordered fields" box, with a small set of neutral hints

**Decision**: Collapse the 6-variant `HeapObjectModel` into **one** uniform box: a frontend-composed
`header` string + an **ordered list of fields** (each a `Variable`: `symbolId`, `identifier`, `typeLabel`,
`value`). Everything special is expressed through the uniform fields plus a few neutral hints on the box:
- **Arrays** → fields with positional identifiers (`"0"`, `"1"`, …); component type in each field's `typeLabel`.
- **Strings** → fields, one per character (identifier = index) — this reproduces today's char-cell rendering
  through the same indexed-field mechanism; truncation → `omittedCount`.
- **Boxed** → a single field whose value is the display string (the frontend bakes `" (JVM cache)"` into it).
- **Enum** → a synthetic box-only leading field carrying the constant name, then the fields.
- **Plain / statics class** → fields directly.
- **Stub / not-fully-captured** → an **`explored`/`complete` boolean hint** (false ⇒ header only, no fields);
  the differ never claims a change on an unexplored box (preserving today's `explored()` gate).
- **Omitted content** → an **`omittedCount`** on the box (drives the "+N not captured" row).

**Rationale**: This is the clarify-chosen "uniform header+fields" model (simplest), and the render survey
confirms every current special treatment is derivable from these hints — the renderer *infers* presentation
(FR-012) instead of switching on typed variants. Arrays and strings unifying to "indexed fields" is a
natural fit for how they already render (contiguous indexed cells), which is also pedagogically apt.

**Alternatives**: (a) Keep distinct typed box kinds — rejected in clarify. (b) Fields-plus-array-only —
rejected (loses stub + single-value handling). The tradeoff accepted: the frontend now composes display
strings/headers (title logic moves into the JDT adapter), and a couple of hints (`explored`, `omittedCount`,
enum leading field) carry what the variant tags used to.

### D4 — Statics fold into the HEAP section as ordinary boxes

**Decision**: Represent each statics class as a uniform box in the **HEAP** section (header `"Class X"`,
fields = its static fields). No STATICS section is added; `Section{STACK, HEAP}` stands.

**Rationale**: The renderer *already* draws statics as container boxes at the top of the heap column, so this
is behavior-preserving and needs no new section. Static fields that reference heap objects become ordinary
`(HEAP, row)` references. This closes the sketch's "no STATICS" gap without extending the enum.

**Alternatives**: Add a third `STATICS` section/column — rejected: unnecessary (statics aren't a distinct
visual column today) and would complicate the addressing model.

### D5 — `Value` collapses to `Primitive | Reference` plus literal `null`

**Decision**: `Value` is a sealed interface permitting **`Primitive(String value)`** and
**`Reference(Section, int index)`**. A `null` `Variable.value` means the absent/uninitialized/null value.
`UnreadableValue` maps to `Primitive("?")` at the value level; native/obsolete/unreadable **frames** use the
builder's body-only frame form (`pushFrame(header, body)`). `NullValue` and `HeapReference` and
`PrimitiveValue.typeName` disappear (type goes to `Variable.typeLabel`).

**Rationale**: Matches the sketch's 2-variant `Value` and the clarify decisions. Diff parity holds:
`Primitive("?")` equals `Primitive("?")` (two unreadables compare equal, as today) and differs from a real
value (unreadable→readable reads as CHANGED, as today).

**Alternatives**: (a) A third `Unreadable` value variant — rejected to keep the 2-variant sketch; the only
parity cost is the specific error string in the value tooltip (today shown on hover). (b) Mitigation if exact
tooltip parity is wanted: add an optional `detail` string to `Primitive` or `Variable`. Flagged as an open
point (D-open-2) rather than baked in.

### D6 — Dangling pointer: new first-class concept, rendered distinctly from null and from a valid reference

**Decision**: A `Reference` whose resolved cell holds no box is **dangling** — a new capability the neutral
model enables. It MUST render distinctly from (a) a literal-null value (empty box, no arrow — unchanged) and
(b) a valid reference (arrow to a target box). The exact glyph is a Principle-III design decision deferred to
implementation, constrained to "clearly distinct"; the recommended default is an arrow terminating in a
distinct "dangling/invalid" marker (e.g. a `⌀` sink or a severed/red stub), never an ordinary arrowhead.

**Rationale**: Required by FR-004 / SC-005. The render survey confirms **no** dangling concept exists today
(the model guarantees every reference resolves), and that today *null* is drawn as "empty box + no arrow" —
so the top parity risk is that dangling and null must not look alike. Java never produces a dangling pointer,
so this is validated by synthetic builder-only diagrams (US3), not by a live Java session.

**Alternatives**: Represent dangling as null — rejected: it erases the very distinction the feature adds.

### D7 — Builder placement, constraints, and construction protocol

**Decision**: `MemoryDiagramBuilder` lives in the `model` package, is **JDK-only** (no SWT/Draw2d/JFace/JDI
imports), **order-preserving** (insertion/BFS order retained), and exposes explicit tokens, caps, and hints.
It provides a **stub-first protocol**: a "reserve/declare a box at a cell" primitive distinct from "fill the
box's fields," mirroring today's `reference`→`drainHeapQueue`, so references resolve even under caps/cycles.
Reference resolution is **two-phase**: references may be added before their target box (forward refs) and are
resolved at `build()`. The terminal `build()` yields the immutable neutral `MemoryDiagram`. The JDI→builder
adapter stays entirely inside `core.extract`.

**Rationale**: Keeps `DiffEngineTest`/`HeapLayouterTest` runtime-free (they can construct diagrams via the
builder), preserves the hard "model stays JDK-only" rule, and matches the extractor's existing stub-first +
per-scope failure-degradation structure (value→`Primitive("?")`, object→keep stub, frame→body-only,
thread/VM→cancel). Order preservation is required by the layout/order tests and heap-cap roots-first survival.

**Alternatives**: Put the builder in `core` — rejected: it must be reachable by JDK-only tests and by the
neutral model, so `model` is correct.

### D8 — Reworked diff: unify field + array-element status under per-box, per-field symbolIds

**Decision**: The new `MemoryDiff` keys on tokens/symbolIds: frame token → status; `(frame token, variable
symbolId)` → status; box token → status; **box token → (field symbolId → status)** — this single per-field
map subsumes today's separate `fieldStatus` and `changedElements` (array elements are just fields with
positional symbolIds). Reference equality = resolved-target-token equality. Ghost lists carry full neutral
models copied from the previous snapshot (rendered once). `MemoryDiff.initial` marks everything NEW.

**Rationale**: The uniform box (D3) makes array elements, string chars, and object fields all "fields," so a
single per-field status map is the natural, simpler generalization — and it preserves per-element CHANGED
highlighting (a real parity risk otherwise).

**Alternatives**: Keep a separate positional `BitSet` for arrays — rejected: redundant once elements are
uniform fields with symbolIds.

### D9 — Scope: migrate only the Eclipse/JDT frontend now

**Decision**: Deliver the neutral model + builder + the reworked JDT adapter + reworked diff/render + reworked
tests. No second editor or language is built. Dangling-pointer support is validated by synthetic builder-only
tests. A builder-only test also stands in for "a second frontend" to prove the diff/render layers depend only
on the neutral model.

**Rationale**: Matches the spec assumptions and clarify scope; proves the seam with one real frontend without
speculative work (YAGNI / Principle II).

## Open points — RESOLVED (defaults confirmed and implemented on 2026-07-14)

The user chose to proceed with the documented defaults; all three are now implemented:

- **D-open-1 (layout ownership, D2)** — RESOLVED as default: the renderer keeps sticky layout; the model's
  `(section,row)` is a resolution key, not an authoritative pixel grid. Implemented — `HeapLayouter` trusts
  `MemoryDiagram.heap()` order and stabilizes it via `LayoutMemory` keyed on the box token.
- **D-open-2 (unreadable detail, D5)** — RESOLVED as default: `UnreadableValue → Primitive("?")`, accepting
  the minor loss of the specific error string in the hover tooltip. No `detail` field added.
- **D-open-3 (statics, D4)** — RESOLVED as default: statics fold into the HEAP section as boxes (token
  `"statics:<class>"`, header `"Class <simpleName>"`), emitted first so they keep the top-of-column position.
  No dedicated statics column.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **Null vs dangling look identical** (both empty box) — top parity risk | D6 mandates a distinct dangling glyph; add a focused synthetic test asserting the three cases (null / valid / dangling) render differently. |
| **Token instability breaks sticky layout / cap persistence** | JDT token = `uniqueId` (stable for VM lifetime); test slot-stability across reassign (mirror existing `HeapLayouterTest` cases) on tokens. |
| **Two duplicated variant switches** (`populateObject` + `ObjectPreviewFigure`) drift | Migrate both to the uniform-field loop in the same change; a preview-vs-box consistency check. |
| **Array per-element / string-char rendering regresses** | D3/D8 keep them as indexed fields with symbolIds; port the existing array/string diff + cap tests onto the uniform model. |
| **Large blast radius in `DiagramController`** (touches every type) | Sequence the work: land the neutral model + builder + JDK-only diff first (fully unit-tested), then the extractor adapter, then render; validate parity against `samples/` at the end. |
| **Behavior parity is hard to prove mechanically** | Keep `DiffEngineTest`/`HeapLayouterTest` as the regression net (ported to the builder), plus the `quickstart.md` manual pass over `samples/`. |

## Output

All NEEDS CLARIFICATION resolved (none remained after `/speckit-clarify`; the above decisions resolve the
design-level unknowns the sketch left open). Proceed to Phase 1: `data-model.md`, `contracts/`, `quickstart.md`.
