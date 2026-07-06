# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Eclipseview is an Eclipse plug-in that renders a live **memory diagram** of a
suspended Java debug session: the stack (frames + locals), the heap (objects,
arrays, strings, boxed values, enums), and static fields, drawn as boxes and
reference arrows on a Draw2d canvas. Between suspends it diffs consecutive
snapshots of the same thread and highlights changes (NEW / CHANGED / DELETED);
deleted items render exactly once as translucent "ghosts".

- `eclipseview/` — the plug-in (plain PDE project, JavaSE-21, singleton bundle).
  Target platform is Eclipse 4.40 at `/Applications/Eclipse.app`.
- `eclipseview-tests/` — headless test suites: plain `main()`-based classes
  (`DiffEngineTest`, `HeapLayouterTest`) with tiny assert helpers; they print a
  summary and exit 1 on any failure. No JUnit, no OSGi — they only touch
  `eclipseview.model` and the pure render classes.
- `runtime-EclipseApplication/` — the runtime workspace used when launching the
  plug-in as an Eclipse Application; contains the `FooApp` demo project
  (`MyApp.java`) to debug against. Its `.metadata` is gitignored.
- The repo root is itself an Eclipse workspace; open it in Eclipse to develop.

## Build & test (headless, no Tycho/Maven)

```sh
sh .context/compile/compile.sh
```

The helper is workspace-local (`.context/` is not committed); recreate it if
missing. It compiles `eclipseview/src` with the Zulu 21 JDK
(`~/Library/Java/JavaVirtualMachines/azul-21.0.11`) against the real
target-platform jars in `/Applications/Eclipse.app/Contents/Eclipse/plugins/`,
then compiles `eclipseview-tests/src` and runs both test mains. Gotcha:
`org.eclipse.jdt.debug` is a *directory* bundle — its classes live in
`jdimodel.jar` inside the bundle directory.

In the IDE: run the `eclipseview` project as an Eclipse Application with
`runtime-EclipseApplication/` as the runtime workspace, debug `FooApp`, and
open the "Memory Diagram" view (Debug category). `render/DevFixture` provides a
hard-coded snapshot + diff pair to exercise the renderer without a debugger.

## Architecture

Data flows one way:

```
DebugContextTracker → SnapshotPipeline → SnapshotExtractor → MemorySnapshot
        (listeners)      (debounced Job)        (JDI walk)      (immutable)
                                                                    │
MemoryDiagramView ← DiagramController ← MemoryDiff ← DiffEngine ←──┘
      (ViewPart)      (Draw2d figures)     (per-thread baseline diff)
```

- `eclipseview.model` — immutable records only (snapshot, frames, variables,
  heap objects, values). `model.diff` computes `MemoryDiff` from two snapshots;
  ghosts carry full models copied from the previous snapshot.
- `eclipseview.core` — `DebugContextTracker` (debug-context + debug-event
  listeners), `SnapshotPipeline` (debounce, per-thread baselines, suspend
  generations, publish gating), `core.extract.SnapshotExtractor` (the JDI walk;
  stub-first BFS with caps from `ExtractionLimits`).
- `eclipseview.render` — pure layout (`HeapLayouter` + sticky `LayoutMemory`),
  theming (`ColorPalette`, `FontKit`), figures (`render.figures`), bezier
  connection routing/clipping, hover/reveal, `ExpansionMemory` (cap overrides).
- `eclipseview.ui` — the ViewPart, preference page/initializer, `ViewSettings`
  (memento-persisted per-view state).

## Hard rules (enforced by review, checked by the layering of imports)

- **JDI/JDT-debug imports are confined to `eclipseview.core[.extract]`.**
  Every JDI wire call happens inside `SnapshotPipeline`'s Job on a worker
  thread (`SnapshotExtractor` does the walk; the pipeline resolves frame keys)
  — never on the UI or debug event dispatch thread. Trigger methods capture
  debug-model references only.
- **No SWT/Draw2d/JFace in `model` or `core.extract` models.** `model` and
  `HeapLayouter`/`LayoutMemory` stay JDK-only so the headless tests can run.
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
- Tests mirror the style of the existing suites: build snapshots with the small
  factory helpers, one `check`/`checkEq` per behavioral claim, run via `main`.
- After any source change, run the headless harness before committing.
