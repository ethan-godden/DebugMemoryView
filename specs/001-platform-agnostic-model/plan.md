# Implementation Plan: Platform-Agnostic Memory Diagram Model

**Branch**: `001-platform-agnostic-model` | **Date**: 2026-07-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-platform-agnostic-model/spec.md`

## Summary

Replace the Java/JDI-shaped memory-diagram model with a **platform-agnostic model assembled
through a builder**. Today the renderer and differ read Java-shaped records directly: heap
objects are keyed by a JDI `long id`, references are `HeapReference(targetId, targetTypeName)`,
and values enumerate Java-specific shapes (null, boxed, enum, string, unreadable). The refactor
introduces a single **`MemoryDiagramBuilder`** ingestion point that any debugger frontend feeds;
the Eclipse JDT extractor is reworked to populate the builder, and the diff and render layers are
reworked to read only the neutral model.

Key design decisions, fixed during `/speckit-clarify`:

- **Uniform heap box**: every heap object is a *header + ordered list of fields*; arrays are
  positional fields, strings/boxed are a single display field, and capped/unexpanded content is
  neutral metadata (omitted-count). No language-specific box variants — the renderer infers
  presentation from neutral signals (preserving today's visuals per FR-012).
- **Opaque identity token** per heap box (and stable symbol id per variable/field) replaces the
  JDI `long id` as the diff's cross-snapshot identity. The Eclipse JDT frontend supplies the JVM
  object id *as the token value*, but diff/render treat the token opaquely.
- **Cell references**: a `Reference` addresses a **section (column: stack/heap)** and an **index
  (row within that column)**; each column numbers rows independently. An empty cell is a
  **dangling pointer** (new capability); a cell populated later is a forward reference.

The delivered scope is the neutral model + builder + migration of the existing Eclipse/JDT
frontend onto it, with behavior parity for Java and dangling-pointer support validated by
synthetic (builder-only) tests. No second editor or language is implemented now.

## Technical Context

**Language/Version**: Java 21 (JavaSE-21) — records, sealed interfaces, pattern-matching `switch`.

**Primary Dependencies**: Eclipse Platform 4.40 / 2026-06 (Draw2d canvas; SWT/JFace ViewPart);
Eclipse JDT Debug + JDI (`com.sun.jdi`), confined to `core.extract`; Apache Commons Lang3 (shipped
in the update site). Built manifest-first by Tycho 5.0.3 against the pinned p2 target definition.

**Storage**: N/A — in-memory immutable snapshots; per-view state persisted via memento (`ViewSettings`).

**Testing**: JUnit 5 via `tycho-surefire` in the `eclipse-test-plugin` fragment (`tests/`). The
model/diff/pure-layout suites are JDK-only; the new neutral model + builder MUST stay JDK-only so
those suites keep running without an Eclipse runtime.

**Target Platform**: Eclipse IDE (desktop) on the 2026-06 release train, JavaSE-21.

**Project Type**: Eclipse plug-in (desktop IDE extension); single Tycho reactor built via `mvn -f parent`.

**Performance Goals**: Interactive — one snapshot → diff → render cycle per debug suspend must feel
instant with no added latency versus today. Builder population runs on the existing `SnapshotPipeline`
worker Job, never the UI or debug-dispatch thread; `ExtractionLimits` caps bound work as today.

**Constraints** (the project's hard rules — see Constitution Check): JDI/JDT stay confined to
`core.extract`; `model` and pure layout stay JDK-only (no SWT/Draw2d); snapshots and diffs are
immutable; consumers run only on the SWT UI thread gated by a sequence check; the refactor is
behavior-preserving for the Java case.

**Scale/Scope**: Small single-view codebase; the refactor touches `model`, `model.diff`,
`core.extract`, `render`, and `tests` (exact blast radius enumerated in research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution v1.0.0 — three principles plus the hard architectural rules.

**I. Code Quality** — PASS (by design).
- The build gate (`mvn -f parent clean verify` under Java 21) remains the definition of done; a red
  build is never merged.
- Every hard rule is *preserved*, not weakened: JDI/JDT stay in `core.extract`; the neutral model and
  pure layout stay JDK-only; snapshots/diffs stay immutable; UI consumers stay on the SWT thread with
  sequence gating.
- New/changed logic (neutral model, builder, reworked diff keyed on tokens, any layout changes) is
  covered by JUnit in the existing suites, now constructed via the builder.

**II. Code Simplicity** — PASS (by design).
- The uniform header+fields box is the *simplest* representation that still supports parity — chosen
  over keeping distinct typed box kinds.
- YAGNI honored: no second frontend, editor, or language is built now; only the seam is proven.
- One-way data flow is preserved and made explicit: `frontend → MemoryDiagramBuilder → neutral model →
  DiffEngine → render`. No back-channels or shared mutable state.
- The builder is the one added abstraction; it earns its place as the sole platform-neutral ingestion
  seam (the feature's core requirement, FR-001). No unjustified abstractions — Complexity Tracking is
  empty.

**III. Learner-First Visuals** — PASS (by design).
- Behavior parity for Java (FR-012, SC-001) keeps the learner's picture identical; special visuals
  (strings/boxed/enums/arrays/stubs), change highlighting, ghosts, and sticky layout are preserved.
- The new dangling-pointer capability renders distinctly from null and from a valid reference
  (FR-004), and SC-005 ties it to learner understanding — satisfying the constitution's feature gate.

**Hard rules checklist** — all upheld:
- JDI wire calls remain on the `SnapshotPipeline` worker Job; trigger methods capture debug-model
  references only. ✅
- No SWT/Draw2d/JFace in the neutral model or `core.extract` models. ✅
- Snapshots/diffs immutable; ghosts rendered exactly once; renderer treats the diff as transient. ✅
- Consumers only via `Display.asyncExec`, gated by the sequence check. ✅
- References compare by target identity (the opaque token): retargeting is the change on the referring
  slot; a target's own mutation shows on the target box. ✅

**Initial gate: PASS** — no violations, no deviations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/001-platform-agnostic-model/
├── plan.md              # This file (/speckit-plan output)
├── research.md          # Phase 0 output — decisions + current-code map + blast radius
├── data-model.md        # Phase 1 output — neutral records, builder ops, old→new mapping
├── quickstart.md        # Phase 1 output — how to validate parity + dangling
├── contracts/           # Phase 1 output — builder API contract + model-consumer contract
│   ├── builder-api.md
│   └── model-consumer.md
├── checklists/
│   └── requirements.md   # Spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

The existing single-plugin, single-Tycho-reactor layout is kept; no modules are added. The refactor
reshapes packages under the root `com.github.ethangodden.debugmemoryview`:

```text
plugin/src/com/github/ethangodden/debugmemoryview/
├── model/                 # RESHAPED: platform-agnostic records + the builder (JDK-only)
│   ├── MemoryDiagramBuilder.java   # sole ingestion point (FR-001); today a stub → becomes real
│   ├── <neutral records>           # MemoryDiagram, Frame, Box, Variable/Field, Value{Primitive,
│   │                               #   Reference}, Section, Cell/identity token (see data-model.md)
│   └── diff/              # REWORKED: DiffEngine/MemoryDiff keyed on symbol ids + identity tokens
├── core/
│   └── extract/           # REWORKED: SnapshotExtractor becomes the Eclipse/JDT adapter that
│                          #   feeds the builder; JDI/JDT stay confined here (FR-010)
├── render/                # REWORKED: reads only the neutral model; uniform box + cell refs;
│   └── figures/           #   adds distinct dangling-pointer rendering (FR-004, FR-011)
└── ui/                    # MINIMAL: ViewPart/settings; only follows renamed model types if any

tests/                     # REWORKED: suites construct diagrams via the builder (JDK-only),
                           #   add builder-only "second frontend" + dangling-pointer tests
samples/                   # UNCHANGED: manual parity validation target (MemoryDiagramSamples)
```

**Structure Decision**: Keep the current structure. This is an internal refactor of an existing
Eclipse plug-in, not a new project. The neutral model and the builder live in the `model` package so
they stay JDK-only and directly constructible by the JDK-only test suites; the Eclipse/JDT extraction
code stays the *only* JDI/JDT consumer in `core.extract`, now emitting builder calls instead of model
records. No new Maven modules, no second frontend module — the platform seam is the builder API, and a
second frontend is future work proven here only by builder-only tests.

## Complexity Tracking

No constitution violations — the design preserves every hard rule and adds a single justified
abstraction (the builder, required by FR-001). This table is intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| _(none)_  | —          | —                                    |

## Phase 0 & 1 Outputs (generated)

- [research.md](./research.md) — current-code map, blast radius, and 9 design decisions
  (D1–D9) with rationale/alternatives, plus 3 open points flagged for confirmation and a risk table.
- [data-model.md](./data-model.md) — neutral records (`MemoryDiagram`, `Frame`, `Box`, `Variable`,
  `Value`{`Primitive`|`Reference`}, `Section`), the reworked token-keyed `MemoryDiff`, and a full
  old→new parity mapping.
- [contracts/builder-api.md](./contracts/builder-api.md) — the frontend→model ingestion contract
  (stub-first, forward refs, dangling, JDT token conventions).
- [contracts/model-consumer.md](./contracts/model-consumer.md) — the model→diff/render contract
  (uniform box, inferred presentation, token-keyed status).
- [quickstart.md](./quickstart.md) — the four validation passes (build gate, builder-only, samples
  parity, dangling/null).

**Key design decisions** (full detail in research.md): opaque `String` identity tokens replace the JDI
`long id` (D1); references are `(section, row)` cells resolved to target tokens while the renderer keeps
its sticky layout re-keyed on tokens (D2); one uniform header+fields box with `explored`/`omittedCount`
hints, arrays/strings/boxed/enums all as fields (D3); statics fold into the HEAP section (D4); `Value` =
`Primitive` | `Reference` + literal null, unreadable → `Primitive("?")` (D5); dangling pointer is a new
first-class capability rendered distinctly from null (D6); builder is JDK-only in `model`, JDI adapter
stays in `core.extract` (D7); diff unifies field + array-element status under per-box/per-field symbolIds
(D8); only the JDT frontend is migrated now (D9).

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 — **still PASS**, no new violations:

- **I. Code Quality** — the design preserves every hard rule (JDI confined to `core.extract`; neutral model
  + builder JDK-only; immutable diagram/diff; UI-thread + sequence-gated consumers) and keeps the JUnit
  suites as the regression net, ported to the builder. Build gate unchanged.
- **II. Code Simplicity** — the uniform box + collapsed 2-variant `Value` + unified diff status *reduce*
  type count versus today; the one added abstraction (builder) is required by FR-001. No new modules.
  Complexity Tracking remains empty.
- **III. Learner-First Visuals** — parity for Java is the P1 gate; the new dangling glyph is constrained to
  "distinct from null and a valid reference" (SC-005), and null-vs-dangling is called out as the top parity
  risk with a dedicated synthetic test.

Design artifacts complete; ready for `/speckit-tasks`.
