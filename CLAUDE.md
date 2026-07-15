# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Eclipseview is an Eclipse plug-in that renders a live **memory diagram** of a
suspended Java debug session: the stack (frames + locals), the heap (objects,
arrays, strings, boxed values, enums), and static fields, drawn as boxes and
reference arrows on a Draw2d canvas. Between suspends it diffs consecutive
snapshots of the same thread and highlights changes (NEW / CHANGED / DELETED);
deleted items render exactly once as translucent "ghosts".

- `plugin/` — the plug-in (JavaSE-21, singleton bundle `DebugMemoryView`,
  root package `com.github.ethangodden.debugmemoryview`). Built manifest-first by
  Tycho against the Eclipse 4.40 / 2026-06 target platform.
- `tests/` — an `eclipse-test-plugin` **fragment** of the plug-in with JUnit 5
  suites (`DiffEngineTest`, `HeapLayouterTest`, `ColumnsLayoutTest`); a fragment so the
  tests reach the plug-in's *internal* `model`/`render` packages without exporting them.
  They stay JDK-only, except `ColumnsLayoutTest` which drives bare Draw2d figures (nothing
  paints).
- `targetplatform/` — the Tycho target definition (`targetplatform.target`) pinning the
  p2 release train `https://download.eclipse.org/releases/2026-06`.
- `feature/`, `repository/` — the `eclipse-feature` and the `eclipse-repository` p2 update
  site (`category.xml`, emitted to `repository/target/repository/` and archived to
  `repository/target/repository-<version>.zip`). Distribution is that zip: users install it
  into an *existing* Eclipse via Help > Install New Software > Add > Archive. (There is
  intentionally no pre-built product, release, or hosted site — the audience already runs
  Eclipse.)
- `runtime-EclipseApplication/` — the runtime workspace used when launching the
  plug-in as an Eclipse Application; create a small Java project here to debug
  against. The directory itself is gitignored — Eclipse recreates it on first launch.
- `samples/` — a plain Eclipse Java project (`MemoryDiagramSamples`, JavaSE-21) of
  small programs to debug against the Memory Diagram view. **Not** a Maven module and
  **not** in the reactor — it's tracked in git but never built by `mvn -f parent`.
  Import it once (in place) into the runtime workspace; the import persists across
  launches (`clearws=false`). See `samples/README.md`.
- `parent/` — the Maven reactor parent (`pom.xml`), with no source; aggregates all sibling modules via `../` paths.
- The repo root has no `pom.xml` (it doubles as the Eclipse workspace, kept flat so every
  module plus `parent/` imports as a non-overlapping project); build with `mvn -f parent`
  (equivalently `cd parent && mvn`). Open the repo root in Eclipse to develop.

## Build & test (Maven + Tycho)

Tycho 5.0.3 requires **Java 21** to run (the default `mvn` here is on Java 17), so point
`JAVA_HOME` at the Zulu 21 JDK:

```sh
JAVA_HOME=~/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home mvn -f parent clean verify
```

The reactor builds, in order, the target definition, the plug-in (`eclipse-plugin`), the
JUnit 5 test fragment (`eclipse-test-plugin`, run headless by `tycho-surefire`), the
feature, and the p2 update site (`eclipse-repository`, emitted to
`repository/target/repository/`). The first build downloads the target
platform from download.eclipse.org into `~/.m2`. The old workspace-local
`.context/compile/compile.sh` is superseded.

CI (`.github/workflows/build.yml`) runs the same `mvn -f parent clean verify` on
`ubuntu-latest` (JDK 21, cached `~/.m2`) for every push/PR and uploads the archived update
site (`repository/target/repository-<version>.zip`) as the `update-site-zip` artifact. That
zip is how the plug-in ships: install it into an existing Eclipse via Help > Install New
Software > Add > Archive.

In the IDE: run the `DebugMemoryView` plug-in project as an Eclipse Application
with `runtime-EclipseApplication/` as the runtime workspace, debug a small Java program there, and open the
"Memory Diagram" view (Debug category).

## Architecture

Data flows one way. The model is **platform-agnostic**: the Eclipse/JDT extractor feeds a
`MemoryDiagramBuilder` and the diff/render layers read only the neutral `MemoryDiagram`, so a
future editor/language frontend needs only a new builder-feeding adapter.

```
DebugContextTracker → SnapshotPipeline → SnapshotExtractor → MemoryDiagramBuilder → MemoryDiagram
        (listeners)      (debounced Job)   (JDI walk, adapter)     (ingestion)         (immutable)
                                                                                            │
MemoryDiagramView ← DiagramController ← MemoryDiff ← DiffEngine ←────────────────────────────┘
      (ViewPart)      (Draw2d figures)     (per-thread baseline diff)
```

Packages below sit under the root `com.github.ethangodden.debugmemoryview`.

- `model` — immutable, JDK-only neutral records + the `MemoryDiagramBuilder` (sole ingestion
  point). A `MemoryDiagram` is STACK `Frame`s + HEAP `Box`es (statics classes are HEAP boxes); a
  `Box` is a uniform header + ordered `Variable` fields (arrays/strings/boxed/enums are all fields,
  with `explored`/`omittedCount` hints). Identity is an opaque token (box/frame token, variable/field
  symbol id) — never a JVM id. A `Value` is `Primitive | Reference` (or `null` = the absent/null
  value); a `Reference(section, index)` addresses a cell (an index into a section's rows) and resolves
  to a `Box` or to nothing (a **dangling pointer**). `model.diff` computes `MemoryDiff` from two
  diagrams; ghosts carry full models copied from the previous diagram, with their references remapped
  into the current diagram's coordinates.
- `core` — `DebugContextTracker` (debug-context + debug-event
  listeners), `SnapshotPipeline` (debounce, per-thread baselines, suspend
  generations, publish gating), `core.extract.SnapshotExtractor` (the JDI walk, now the JDT→builder
  adapter that feeds `MemoryDiagramBuilder`; stub-first BFS with caps from `ExtractionLimits`).
- `render` — pure layout (`HeapLayouter` + sticky `LayoutMemory`),
  theming (`ColorPalette`, `FontKit`), figures (`render.figures`), bezier
  connection routing/clipping, hover/reveal, `ExpansionMemory` (cap overrides).
- `ui` — the ViewPart, preference page/initializer, `ViewSettings`
  (memento-persisted per-view state).

## Hard rules (enforced by review, checked by the layering of imports)

- **JDI/JDT-debug imports are confined to `core[.extract]`.**
  Every JDI wire call happens inside `SnapshotPipeline`'s Job on a worker
  thread (`SnapshotExtractor` does the walk) — never on the UI or debug event
  dispatch thread. Trigger methods capture debug-model references only.
- **No SWT/Draw2d/JFace in `model` or `core.extract` models.** `model` (neutral records + builder)
  and `HeapLayouter`/`LayoutMemory` stay JDK-only so those test suites need no runtime.
- Diagrams and diffs are immutable; renderers must treat a `MemoryDiff` as
  transient per render and never accumulate ghosts.
- Consumers are called only on the SWT UI thread (`Display.asyncExec`), gated
  by a sequence check so superseded diagrams are never displayed.
- Reference values compare by **resolved target token** (`DiffEngine.valueEquals`):
  retargeting an arrow is the change; a target's mutation shows on the target
  box, not on every inbound arrow. Two dangling references are equal, and two unreadable values
  (both mapped to `Primitive("?")`) are equal.

## Conventions

- Java 21 (records, pattern matching, switch expressions). Non-NLS strings are
  tagged `//$NON-NLS-1$`.
- SWT colors/fonts come from the canvas-scoped `ResourceManager`
  (`ColorPalette`/`FontKit`); never `new Color(...)` without disposal.
- Tests mirror the style of the existing JUnit 5 suites: build snapshots with the
  small factory helpers, one `assertEquals`/`assertTrue` (with a message) per
  behavioral claim.
- After any source change, run `mvn -f parent clean verify` (under Java 21) before committing.
