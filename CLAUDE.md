# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Eclipseview is an Eclipse plug-in that renders a live **memory diagram** of a
suspended Java debug session: the stack (frames + locals), the heap (objects,
arrays, strings, boxed values, enums), and static fields, drawn as boxes and
reference arrows on a Draw2d canvas. Between suspends it diffs consecutive
snapshots of the same thread and highlights changes (NEW / CHANGED / DELETED);
deleted items render exactly once as translucent "ghosts".

- `EclipseMemoryDiagram/` — the plug-in (JavaSE-21, singleton bundle `DebugMemoryView`,
  root package `com.github.ethangodden.debugmemoryview`). Built manifest-first by
  Tycho against the Eclipse 4.40 / 2026-06 target platform.
- `tests/` — an `eclipse-test-plugin` **fragment** of the plug-in with JUnit 5
  suites (`DiffEngineTest`, `HeapLayouterTest`, `ColumnsLayoutTest`); a fragment so the
  tests reach the plug-in's *internal* `model`/`render` packages without exporting them.
  They stay JDK-only, except `ColumnsLayoutTest` which drives bare Draw2d figures (nothing
  paints).
- `targetplatform/` — the Tycho target definition (`targetplatform.target`) pinning the
  p2 release train `https://download.eclipse.org/releases/2026-06`.
- `feature/`, `repository/` — the `eclipse-feature` and the
  `eclipse-repository` p2 update site.
- `runtime-EclipseApplication/` — the runtime workspace used when launching the
  plug-in as an Eclipse Application; create a small Java project here to debug
  against. Its `.metadata` is gitignored.
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

In the IDE: run the `DebugMemoryView` plug-in project as an Eclipse Application
with `runtime-EclipseApplication/` as the runtime workspace, debug a small Java program there, and open the
"Memory Diagram" view (Debug category). `render/DevFixture` provides a hard-coded snapshot
+ diff pair to exercise the renderer without a debugger.

## Architecture

Data flows one way:

```
DebugContextTracker → SnapshotPipeline → SnapshotExtractor → MemorySnapshot
        (listeners)      (debounced Job)        (JDI walk)      (immutable)
                                                                    │
MemoryDiagramView ← DiagramController ← MemoryDiff ← DiffEngine ←──┘
      (ViewPart)      (Draw2d figures)     (per-thread baseline diff)
```

Packages below sit under the root `com.github.ethangodden.debugmemoryview`.

- `model` — immutable records only (snapshot, frames, variables,
  heap objects, values). `model.diff` computes `MemoryDiff` from two snapshots;
  ghosts carry full models copied from the previous snapshot.
- `core` — `DebugContextTracker` (debug-context + debug-event
  listeners), `SnapshotPipeline` (debounce, per-thread baselines, suspend
  generations, publish gating), `core.extract.SnapshotExtractor` (the JDI walk;
  stub-first BFS with caps from `ExtractionLimits`).
- `render` — pure layout (`HeapLayouter` + sticky `LayoutMemory`),
  theming (`ColorPalette`, `FontKit`), figures (`render.figures`), bezier
  connection routing/clipping, hover/reveal, `ExpansionMemory` (cap overrides).
- `ui` — the ViewPart, preference page/initializer, `ViewSettings`
  (memento-persisted per-view state).

## Hard rules (enforced by review, checked by the layering of imports)

- **JDI/JDT-debug imports are confined to `core[.extract]`.**
  Every JDI wire call happens inside `SnapshotPipeline`'s Job on a worker
  thread (`SnapshotExtractor` does the walk; the pipeline resolves frame keys)
  — never on the UI or debug event dispatch thread. Trigger methods capture
  debug-model references only.
- **No SWT/Draw2d/JFace in `model` or `core.extract` models.** `model` and
  `HeapLayouter`/`LayoutMemory` stay JDK-only so those test suites need no runtime.
- Snapshots and diffs are immutable; renderers must treat a `MemoryDiff` as
  transient per render and never accumulate ghosts.
- Consumers are called only on the SWT UI thread (`Display.asyncExec`), gated
  by a sequence check so superseded snapshots are never displayed.
- Reference values compare by target id (`DiffEngine.valueEquals`):
  retargeting an arrow is the change; a target's mutation shows on the target
  box, not on every inbound arrow. Two `UnreadableValue`s are always equal.

## Conventions

- Java 21 (records, pattern matching, switch expressions). Non-NLS strings are
  tagged `//$NON-NLS-1$`.
- SWT colors/fonts come from the canvas-scoped `ResourceManager`
  (`ColorPalette`/`FontKit`); never `new Color(...)` without disposal.
- Tests mirror the style of the existing JUnit 5 suites: build snapshots with the
  small factory helpers, one `assertEquals`/`assertTrue` (with a message) per
  behavioral claim.
- After any source change, run `mvn -f parent clean verify` (under Java 21) before committing.
