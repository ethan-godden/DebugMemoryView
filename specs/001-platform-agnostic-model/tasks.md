---
description: "Task list for Platform-Agnostic Memory Diagram Model"
---

# Tasks: Platform-Agnostic Memory Diagram Model

**Input**: Design documents from `/specs/001-platform-agnostic-model/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED. The spec's success criteria (SC-003, SC-005, SC-007) and the project constitution
(model/diff/pure-layout logic MUST carry JUnit coverage; FR-013 requires JDK-only constructibility) make
tests part of the requirements. The two model suites are ported to the builder; new builder-only and
dangling tests are added.

**Organization**: Tasks are grouped by user story. This is a **refactor of intertwined code**, so the build
is guaranteed green at two **checkpoints** — end of Foundational (T010) and end of US1 (T022) — and may be
transiently red *within* the US1 cutover (inherent to swapping a shared model). Land US1 as one cohesive
sequence; do not merge a red state.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (user-story phases only)
- File paths are under `plugin/src/com/github/ethangodden/debugmemoryview/` (source) and
  `tests/src/com/github/ethangodden/debugmemoryview/tests/` (tests), abbreviated as `.../` below.

---

## Phase 1: Setup

**Purpose**: Establish the parity baseline before touching anything.

- [X] T001 Verify a green baseline: run `JAVA_HOME=~/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home mvn -f parent clean verify` and record it passes; capture current Memory Diagram rendering for the `samples/` programs (notes/screenshots) as the parity reference for T023.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The JDK-only neutral model + builder that every user story depends on. **Additive** — introduces
new types under new names (`MemoryDiagram`, `Frame`, `Box`, `Variable`, `Value`, `Primitive`, `Reference`,
`Section`) that do not collide with the existing Java-shaped types, so the old code and build stay green.

**⚠️ No user-story work begins until this phase is complete and T010 is green.**

- [X] T002 [P] Create the `Section` enum (`STACK, HEAP`) in `.../model/Section.java` (per data-model.md).
- [X] T003 Create the `Value` sealed interface + `Primitive(String value)` + `Reference(Section section, int index)` records in `.../model/Value.java`, `.../model/Primitive.java`, `.../model/Reference.java` (depends on T002).
- [X] T004 [P] Create the `Variable(String symbolId, String identifier, String typeLabel, Value value)` record in `.../model/Variable.java` (depends on T003).
- [X] T005 [P] Create the `Frame(String frameToken, String header, List<Variable> variables, String body)` record in `.../model/Frame.java` (exactly one of `variables`/`body` populated) (depends on T004).
- [X] T006 [P] Create the `Box(String boxToken, String header, List<Variable> fields, boolean explored, int omittedCount)` record in `.../model/Box.java` (depends on T004).
- [X] T007 Create the `MemoryDiagram(String debugTargetToken, String threadToken, String threadName, long sequence, List<Frame> frames, List<Box> heap)` record with a `resolve(Reference) → Box|dangling` lookup in `.../model/MemoryDiagram.java` (depends on T005, T006, T003).
- [X] T008 Implement `MemoryDiagramBuilder` in `.../model/MemoryDiagramBuilder.java`: `pushFrame(token,header,variables)` / `pushFrame(token,header,body)`, `reserveBox`/`fillBox`/`addBox`, `reference(boxToken)`, `build()` — stub-first reservation, forward-reference + dangling resolution, per-section independent row assignment, order preservation, immutable output (per contracts/builder-api.md) (depends on T007).
- [X] T009 [P] Builder conformance test in `.../tests/MemoryDiagramBuilderTest.java`: frames (variables + body-only), boxes encoding plain/array/string/boxed/enum/statics as uniform fields, primitives, absent (null) values, references, **forward reference resolves**, **dangling resolves (not error)**, **stub `explored=false` still resolves**, and ordering preserved — JDK-only (contracts/builder-api.md conformance; SC-003 partial) (depends on T008).
- [X] T010 **CHECKPOINT** — run `mvn -f parent clean verify`; MUST be green (new types + builder test pass; old model untouched).

**Checkpoint**: Neutral model + builder exist, tested, JDK-only. The old Java-shaped model still compiles.

---

## Phase 3: User Story 1 — Java diagram works unchanged, driven by the neutral model (Priority: P1) 🎯 MVP

**Goal**: The existing Eclipse/Java memory diagram renders and diffs identically, now produced by feeding the
builder from JDT and consumed only from the neutral model. This is the coordinated cutover.

**Independent Test**: Debug the `samples/` programs and confirm boxes, arrows, special value treatments,
NEW/CHANGED/DELETED highlighting, ghosts, and sticky layout match the pre-refactor behavior (T023); the
ported JDK-only suites pass.

> Within this phase the build is transiently red until the cutover completes at T022. Land it as one sequence.

- [X] T011 [US1] Rewrite `MemoryDiff` to be token-keyed in `.../model/diff/MemoryDiff.java`: `frameStatus`(frameToken), `variableStatus`(frameToken#symbolId), `boxStatus`(boxToken), `fieldStatus`(boxToken→symbolId) — folding array-element status in — plus `deletedFrames`/`deletedVariables`/`deletedBoxes` ghost lists and `initial(MemoryDiagram)` (per data-model.md; reuses existing `ChangeStatus`).
- [X] T012 [US1] Rewrite `DiffEngine` in `.../model/diff/DiffEngine.java` to diff two `MemoryDiagram`s: frames by frameToken, variables by symbolId within a frame, boxes by boxToken, fields by symbolId (incl. array elements), unexplored box on either side ⇒ UNCHANGED, **reference equality by resolved target token**, primitives by string, absent-equal, ghosts carried from prev (depends on T011, T007).
- [X] T013 [P] [US1] Port `DiffEngineTest` to the neutral model+diff in `.../tests/DiffEngineTest.java`: initial/thread-switch, variable statuses + ghosts, frame push/pop/return-call, line-only-in-header change, box field/array-element/statics diffs, reference retarget, **two `Primitive("?")` compare equal** (unreadable parity), ghost-once — built via the builder (depends on T012).
- [X] T014 [US1] Rewrite `SnapshotExtractor` as the JDT→builder adapter in `.../core/extract/SnapshotExtractor.java`: feed `MemoryDiagramBuilder` and return a `MemoryDiagram` — frames (`this`+locals; body-only for native/obsolete/unreadable), stub-first `reserveBox`, boxes for plain/enum/array/string/boxed/statics as **uniform fields**, tokens (`boxToken=Long.toString(getUniqueId())`, `frameToken=frameKey`, field/var/index symbolIds per contracts), `explored`/`omittedCount`, frontend-composed headers, references via `reference(boxToken)`; keep all JDI/JDT confined here (depends on T008).
- [X] T015 [US1] Update the pipeline + consumer seam to the neutral types: `SnapshotPipeline` (`Request`/`Baseline`, `DiffEngine.diff`, `publish`), `ISnapshotConsumer.snapshotReady(MemoryDiagram, MemoryDiff)`, and `MemoryDiagramView` fields + `snapshotReady`/`display` — preserving the sequence gate and `threadToken` checks, in `.../core/SnapshotPipeline.java`, `.../core/ISnapshotConsumer.java`, `.../ui/MemoryDiagramView.java` (depends on T014, T012).
- [X] T016 [US1] Rewrite `DiagramController` in `.../render/DiagramController.java` to consume `MemoryDiagram` + neutral `MemoryDiff`: replace the six-way `populateObject` variant switch with a single uniform-field loop; re-key `objectFigures`/`modelById`/`PendingRef` from `long`→token; resolve references via `MemoryDiagram.resolve()`; resolve/anchor arrows in `createConnections` by token and keep cross-pane vs intra-heap routing keyed on the source `Section`; infer presentation (single-value, indexed fields, enum leading field, `explored=false` stub, `omittedCount` "+N", header title) per contracts/model-consumer.md (depends on T015).
- [X] T017 [US1] Migrate `ObjectPreviewFigure.collectLines` (hover preview) to the same uniform-box read in `.../render/figures/ObjectPreviewFigure.java`, in lockstep with T016 so preview and box rendering stay consistent (depends on T016).
- [X] T018 [P] [US1] Re-key `HeapLayouter` + `LayoutMemory` + `ExpansionMemory` from `long id` to the opaque token in `.../render/HeapLayouter.java`, `.../render/LayoutMemory.java`, `.../render/ExpansionMemory.java`: BFS over each box's reference-bearing fields, sticky `orderKey` by token, cap/collapse keys by token (depends on T015).
- [X] T019 [P] [US1] Resolve arrow target rows: update `getReferenceTargetFigure` + `RowEdgeAnchor` target picking in `.../render/figures/HeapObjectFigure.java` and `.../render/RowEdgeAnchor.java` to map a resolved reference to its box figure (first-row/header fallback preserved) (depends on T016).
- [X] T020 [P] [US1] Port `HeapLayouterTest` to token-keyed sticky ordering + neutral model in `.../tests/HeapLayouterTest.java`: BFS discovery order, frame-before-statics root order, slot stability across reassign, ghost slot retention, eviction — built via the builder (depends on T018).
- [X] T021 [US1] Delete the obsolete Java-shaped types and fix residual references: `MemorySnapshot`, `HeapObjectModel`, `HeapReference`, `ValueModel`, `PrimitiveValue`, `NullValue`, `UnreadableValue`, `VariableModel`, `FieldModel`, `StackFrameModel`, `StaticsClassModel` under `.../model/`, plus any now-dead helpers (e.g. stale `render/Ellipsis` branches) (depends on T016, T017, T018, T019).
- [X] T022 [US1] **CHECKPOINT** — run `mvn -f parent clean verify`; MUST be green. Render + diff compile against the neutral model only; no `MemorySnapshot`/`HeapObjectModel`/`HeapReference`/etc. remain (SC-007).
- [ ] T023 [US1] Manual parity pass over `samples/` per quickstart.md §3: frames/`this`/locals, heap boxes, arrays (indexed cells), strings (char cells + "(truncated)"), boxed ("(JVM cache)"), enums (leading constant row), statics ("Class X"), stubs ("not explored"), reference arrows + routing, caps ("+N not captured"), NEW/CHANGED/DELETED, ghosts once, sticky layout — no visible regression (SC-001, SC-006).

**Checkpoint**: The Java experience is fully preserved through the neutral model — MVP complete and shippable.

---

## Phase 4: User Story 2 — A maintainer adds a frontend by feeding the builder (Priority: P2)

**Goal**: Prove the diff and render/layout layers depend only on the neutral model — a second frontend needs
only builder-feeding code.

**Independent Test**: A diagram built purely through the builder (no JDT/Java types) diffs and lays out
correctly; it produces equivalent results to the JDT-built diagram.

- [X] T024 [P] [US2] Builder-only "second frontend" test in `.../tests/SecondFrontendModelTest.java`: construct a full diagram with no JDT/Java-specific types, run through `DiffEngine` + `HeapLayouter`, assert correct diff + layout with no live debugger (SC-003, SC-004; contracts/model-consumer.md conformance) (depends on T012, T018).
- [X] T025 [US2] Enforce the platform boundary (SC-002 / FR-011): confirm and lock that `.../model/`, `.../model/diff/`, and the diff/render read paths reference zero JDI/JDT/SWT/Draw2d/editor types (render figures may still use Draw2d/SWT for drawing, but must not touch debugger types); add a guard test or documented `grep` check under `.../tests/` that fails on a forbidden import (depends on T022).
- [X] T026 [US2] Equivalence test (acceptance US2-2): a diagram reproduced via direct builder calls yields the same diff + layout as the JDT-built equivalent, in `.../tests/SecondFrontendModelTest.java` (depends on T024, T023).

**Checkpoint**: The platform seam is proven; the model is independently usable.

---

## Phase 5: User Story 3 — Dangling pointer represented and shown distinctly (Priority: P3)

**Goal**: A reference to an empty cell is representable and renders distinctly from null and from a valid
reference.

**Independent Test**: A synthetic diagram with a dangling reference renders it distinctly; a forward
reference resolves (not dangling); a disappearing target highlights via NEW/CHANGED/DELETED.

- [X] T027 [P] [US3] Add explicit builder/model coverage of dangling + forward semantics in `.../tests/MemoryDiagramBuilderTest.java` (or a focused `DanglingModelTest.java`): reference to a never-declared cell ⇒ dangling; empty-then-filled cell ⇒ resolves; aliasing to one cell (depends on T008).
- [X] T028 [US3] Implement distinct dangling rendering: draw a dangling reference with a distinct glyph/terminator (e.g. a `⌀` sink or severed stub), clearly different from null (empty box, no arrow) and from a valid arrow, routed from the source `Section`, in `.../render/DiagramController.java` and `.../render/StateConnection.java` (+ a small figure if needed) (depends on T016, T019).
- [X] T029 [P] [US3] Render-level synthetic test in `.../tests/DanglingRenderTest.java`: null vs valid-reference vs dangling render distinctly; forward-ref resolves (not dangling); a deleted target across two synthetic snapshots highlights via NEW/CHANGED/DELETED (SC-005) (depends on T028).

**Checkpoint**: Dangling pointers are a working, distinctly-rendered capability.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T030 [P] Update `CLAUDE.md` (Architecture + `model` package description + data-flow diagram) to the neutral model + builder (`DebugContextTracker → SnapshotPipeline → JDT adapter → MemoryDiagramBuilder → MemoryDiagram → DiffEngine → DiagramController`).
- [X] T031 [P] Sweep javadoc/package-info and comments for stale references to the removed model types across `.../model/`, `.../render/`, `.../core/`.
- [X] T032 Final gate: `mvn -f parent clean verify` green + walk the quickstart.md Definition-of-Done checklist (SC-001…SC-007 all satisfied).
- [X] T033 [P] Close the three open design points from research.md (D-open-1 layout ownership, D-open-2 unreadable detail, D-open-3 statics section): apply the user's choice or confirm-and-record the defaults in research.md.

---

## Dependencies & Execution Order

- **Setup (T001)** → no dependencies.
- **Foundational (T002–T010)** → depends on Setup; **blocks all user stories**. Internal order: T002 → T003 → T004 → {T005, T006} → T007 → T008 → T009 → **T010 checkpoint**.
- **US1 (T011–T023)** → depends on Foundational. Internal order: T011 → T012 → {T013 ∥ T014} → T015 → T016 → {T017, T018, T019, T020 as noted} → T021 → **T022 checkpoint** → T023 parity.
- **US2 (T024–T026)** → depends on Foundational + T012/T018 (T024) and T022/T023 (T026). Largely verification; can begin T024 once diff+layouter are on the neutral model.
- **US3 (T027–T029)** → T027 after Foundational; T028/T029 after US1 render (T016/T019).
- **Polish (T030–T033)** → after all desired stories.

## Parallel Opportunities

- Foundational records: T002, then T004/T005/T006 are largely independent record files (respect T003→T004 dep).
- US1: T013 (diff test) ∥ T014 (adapter) — different files. After T016: T018 (layout re-key) ∥ T019 (anchor figures) ∥ T020 (layouter test) — different files; T017 follows T016 in lockstep (same conceptual switch, different file).
- US2: T024 ∥ once its deps land. US3: T027 early; T029 after T028.
- Polish: T030, T031, T033 in parallel.

## Implementation Strategy

- **MVP = Setup + Foundational + US1** (T001–T023): delivers the entire Java experience preserved on the
  neutral model. Ship/validate here before US2/US3.
- **Green checkpoints**: T010 (foundational) and T022 (US1 cutover). The refactor swaps a shared model, so
  US1 is a cohesive sequence — keep intermediate states on the branch, merge only green.
- **Incremental after MVP**: US2 (prove the seam) then US3 (dangling) are additive and independently testable.

## Notes

- Tests are required here (constitution + SC-003/SC-005/SC-007). The ported `DiffEngineTest`/`HeapLayouterTest`
  are the regression net for parity; build them via the builder to keep them JDK-only (FR-013).
- Keep JDI/JDT confined to `core.extract` (the JDT adapter, T014); the neutral model + builder stay JDK-only.
- Migrate `DiagramController` (T016) and `ObjectPreviewFigure` (T017) together — they duplicate the same
  variant logic and will drift if split across checkpoints.
- The top parity risk is null-vs-dangling looking alike (research.md Risks) — T028/T029 own that.
