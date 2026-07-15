# Quickstart: Validating the Platform-Agnostic Memory Diagram Model

**Feature**: 001-platform-agnostic-model | **Date**: 2026-07-14

How to prove this refactor works: the existing Java experience is unchanged, the model is independently
usable without a debugger, and dangling pointers render distinctly. References: [spec.md](./spec.md),
[data-model.md](./data-model.md), [contracts/](./contracts/).

## Prerequisites

- JDK 21 (Zulu 21) — Tycho requires it:
  `export JAVA_HOME=~/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home`
- The repo opened as-is (build with `mvn -f parent`, never a root `pom.xml`).

## 1. Build + automated test gate (SC-007)

```sh
JAVA_HOME=~/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home mvn -f parent clean verify
```

Expected: green build. The `eclipse-test-plugin` fragment runs headless under `tycho-surefire`. After the
refactor, `DiffEngineTest` and `HeapLayouterTest` construct diagrams **through `MemoryDiagramBuilder`** and
still run **JDK-only** (no Eclipse runtime) — this is itself evidence of FR-013. `ColumnsLayoutTest` is
unaffected. A red build is never merged (Constitution I).

## 2. Model is independently usable — builder-only, no debugger (SC-003 / SC-004 / US2)

A JDK-only test constructs a full diagram via the builder (frames incl. a body-only frame, boxes as uniform
fields, primitives, absent values, references), then runs it through `DiffEngine` and the pure layouter.

Expected: a correct diff + layout are produced with **no** live debugger and **no** Java/JDI type in the
test — demonstrating the diff/render layers depend only on the neutral model. This same test stands in for
"a second frontend" (SC-004): if it lays out and diffs correctly, adding a real second frontend needs only
builder-feeding code. See [contracts/builder-api.md](./contracts/builder-api.md) → Conformance tests.

## 3. Java behavior parity — manual pass over the samples (SC-001 / SC-006 / US1)

In the IDE, launch the `DebugMemoryView` plug-in as an Eclipse Application with
`runtime-EclipseApplication/` as the runtime workspace; import `samples/` (MemoryDiagramSamples) once; open
the **Memory Diagram** view (Debug category); debug each sample and step through it.

Verify, against pre-refactor behavior (git-stash the branch to compare if needed):

- Stack frames, `this` + locals, heap boxes, arrays (indexed cells), strings (char cells + "(truncated)"),
  boxed values ("(JVM cache)"), enums (leading constant row), statics ("Class X" boxes), and stub
  "(not explored)" boxes all render the same.
- Reference arrows connect the same slots to the same target boxes; cross-pane vs intra-heap routing looks
  the same; caps still show "+N not captured" and the "raise the heap object cap" tooltip.
- Stepping highlights **NEW / CHANGED / DELETED** identically; deleted items appear once as translucent
  ghosts; **layout stays sticky** (boxes do not jump between suspends).

Any visible difference is a parity regression (blocks US1).

## 4. Dangling pointer + null disambiguation (SC-005 / US3) — synthetic

Because Java cannot produce a dangling pointer, validate with a synthetic diagram (builder-only or a small
render test): a reference whose target cell holds no box.

Expected:
- The dangling reference renders **distinctly** — not an ordinary arrow to a box, and **not** identical to a
  null value. (Null = empty box, no arrow; dangling = the distinct dangling glyph.)
- A reference to a cell that is empty at first but filled later resolves to the now-present box (forward
  reference), not dangling.
- Across two synthetic snapshots where a target disappears, the change is highlighted with the same
  NEW/CHANGED/DELETED vocabulary.

This is the one case that must be built by hand (or by a future non-Java frontend); it is the top parity
risk (null vs dangling must not look alike — see research.md Risks).

## Definition of done (maps to Success Criteria)

- [ ] `mvn -f parent clean verify` green under Java 21 (SC-007)
- [ ] Model/diff/pure-layout suites run JDK-only, built via the builder (FR-013)
- [ ] Builder-only diagram diffs + lays out with no debugger / no Java types (SC-003, SC-004)
- [ ] Full `samples/` pass shows no visible regression (SC-001, SC-006)
- [ ] Render + diff layers contain zero Java/JDI/JDT/editor types; JDI confined to `core.extract` (SC-002)
- [ ] Dangling renders distinctly from null and from a valid reference (SC-005)
