# Feature Specification: Platform-Agnostic Memory Diagram Model

**Feature Branch**: `001-platform-agnostic-model`

**Created**: 2026-07-14

**Status**: Draft

**Input**: User description: "I want to refactor the model so that it is platform agnostic. I added MemoryDiagramBuilder.java which contains the types and methods I am thinking. The goal of this is to create a model I can use in future languages I support and no matter if its an eclipse plugin, vscode plugin, etc. Eclipse JDT would feed info into this builder, and when we build the figures, it would use info in this class. This refactor also introduces the idea of a dangling pointer, which doesnt really happen in Java."

## Overview

Today the memory-diagram model is shaped around one debugger and one language: heap
objects are addressed by a JVM object id, references carry a Java target id and type
name, and values enumerate Java-specific shapes (null, boxed primitive, enum, string,
unreadable). The rendering and diff layers read those Java-shaped types directly, so the
picture a student sees is welded to the Java Debug Interface.

This feature replaces that Java-shaped model with a **platform-agnostic memory-diagram
model** that describes *what to draw* — frames, boxes, fields, and reference arrows — in
terms every language and every editor share. A **builder** is the single ingestion point:
a debugger frontend (Eclipse JDT today; VS Code or another language's debugger later) feeds
the builder, and the figure/diff layers read only the built model. References address a
**cell in the diagram** — a section (column) and a row index within that column — rather
than an object's identity, which lets the model express things Java can never produce —
most notably a **dangling pointer**: a reference to a location that holds no object.

The primary audience remains new students learning to code; this refactor is the
foundation that lets the same learner-first diagram serve more languages and editors
without rewriting the drawing or the change-highlighting logic.

## Clarifications

### Session 2026-07-14

- Q: How should the diff engine decide a heap box is the *same object* across consecutive snapshots, now that the neutral model has no JVM object ids? → A: Each heap box carries a stable, frontend-supplied **identity token** (Eclipse JDT uses the JVM object id as the token value); the diff keys NEW / CHANGED / DELETED on that token, which the diff and rendering layers treat as opaque.
- Q: How should the neutral model represent the heap box shapes that render specially today (plain/enum fields, arrays, strings, boxed values, capped stubs)? → A: A single **uniform box** — a header plus an ordered list of fields. Arrays use positional field identifiers; single-value objects (strings, boxed primitives) are a box with one display field; content omitted for caps or a referenced-but-unexpanded target is carried as neutral metadata (an omitted-member count / unexpanded marker). No distinct language-specific box kinds; the renderer infers presentation from these neutral signals.
- Q: How does a reference address its target cell? → A: A reference names a **section (column)** and an **index (the row within that column)**. Each section/column keeps its own **independent row numbering**, so an empty slot added in one column does not shift or create rows in the other. An index with no occupant is a dangling reference; an index populated later is a forward reference.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Existing Java diagram works unchanged, now driven by the neutral model (Priority: P1)

A student debugs a small Java program in Eclipse and opens the Memory Diagram view. As they
step, they see the same stack frames, heap boxes, strings, boxed values, enums, arrays, and
reference arrows as before, and the same NEW / CHANGED / DELETED highlighting and translucent
ghosts — except the diagram is now produced by feeding the platform-agnostic builder from
Eclipse JDT instead of by rendering Java-shaped model types directly.

**Why this priority**: This is the MVP and the safety net. The refactor has zero user-facing
value unless the existing, shipped Java experience is preserved exactly. It also proves the
builder seam works end-to-end with a real debugger before any second platform exists.

**Independent Test**: Debug the programs in `samples/` (MemoryDiagramSamples), step through
each, and confirm the rendered diagram — boxes, arrows, special value displays, change
highlighting, ghosts, and sticky layout — matches the pre-refactor behavior. The model, diff,
and pure-layout unit suites pass unchanged in intent.

**Acceptance Scenarios**:

1. **Given** a suspended Java thread with locals, `this`, arrays, strings, boxed values, and
   enums, **When** the diagram is built from the neutral model, **Then** every element renders
   with the same visual treatment it had before the refactor.
2. **Given** two consecutive suspends of the same thread, **When** a local is reassigned or an
   object field mutates, **Then** the affected element is highlighted CHANGED, newly appeared
   elements are NEW, and vanished elements render exactly once as a translucent ghost — the same
   as before the refactor.
3. **Given** a reference variable and the object it points to, **When** the arrow is drawn,
   **Then** it connects the variable's slot to the target box exactly as before.
4. **Given** extraction hits a cap (an object referenced but not expanded), **When** the diagram
   is built, **Then** that target still appears as a box drawn with its type/header only, and the
   reference still connects to it — no reference is left unresolved for the Java case.

---

### User Story 2 - A maintainer adds a new debugger frontend by feeding the builder (Priority: P2)

A maintainer wants the memory diagram to support a second source — a different editor's debug
adapter, or a different language. They implement only the code that reads that debugger's state
and calls the builder. They write no new rendering code and no new diff code; the existing figure
and change-highlighting layers draw the result unchanged.

**Why this priority**: This is the whole point of "platform agnostic." It is second because it
depends on the neutral model existing (US1) and is validated without shipping a full second
integration.

**Independent Test**: Construct a memory diagram entirely through the builder in a test (no live
debugger, no Java-specific types), covering frames, heap boxes, fields, primitives, references,
and a body-only frame. Run it through the diff and layout layers and confirm a correct diagram is
produced — demonstrating those layers depend only on the neutral model.

**Acceptance Scenarios**:

1. **Given** a program that builds a diagram purely through the builder API, **When** the figure
   and diff layers process it, **Then** they compile and run without referencing any Java- or
   JDI-specific type.
2. **Given** the same logical diagram built two ways (via the Java frontend, and via direct
   builder calls that reproduce it), **When** each is diffed and laid out, **Then** the results are
   equivalent.
3. **Given** a frame that the frontend cannot express as variables (e.g. a native or otherwise
   opaque frame), **When** the frontend supplies a header plus a body string, **Then** the frame
   renders that body in place of a variable list.

---

### User Story 3 - The diagram represents and clearly shows a dangling pointer (Priority: P3)

A reference points at a diagram location that holds no object — a dangling pointer. This never
happens in Java but is common in languages with manual memory management (a pointer to freed or
uninitialized memory). The model can represent it, and a student sees it drawn distinctly so they
can tell it apart from a null reference and from a valid reference.

**Why this priority**: It is a new capability the neutral model unlocks, valuable for future
non-Java languages, but not exercised by today's Java-only usage, so it is lowest priority and
validated with synthetic diagrams.

**Independent Test**: Build a diagram in which a reference targets an empty cell, run it through
layout and rendering, and confirm the dangling reference is drawn distinctly from both a null value
and a reference to a populated cell.

**Acceptance Scenarios**:

1. **Given** a reference whose target cell holds no object, **When** the diagram is rendered,
   **Then** the reference is shown as dangling, visually distinct from a null value and from a valid
   reference.
2. **Given** a reference to a cell that is empty at build time but becomes populated as the diagram
   is built further, **When** rendering completes, **Then** the reference connects to the now-present
   target rather than showing as dangling.
3. **Given** consecutive snapshots where a reference target disappears, **When** the diagram diffs
   the two, **Then** the change is highlighted using the same NEW / CHANGED / DELETED vocabulary as
   every other change.

---

### Edge Cases

- **Forward reference**: a reference to a (section, index) cell is added before that cell is populated;
  the built diagram must resolve it to the eventual occupant of that row.
- **Empty-cell reference (dangling)**: a reference whose target (section, index) cell is never populated
  must be representable and render as dangling, not as an error and not as null.
- **Independent per-column rows**: leaving or adding an empty row slot in one section (column) must not
  shift or create rows in the other section; each column's row indices are numbered independently.
- **Uninitialized / null value**: a variable or field with no value is represented as an explicit
  absence (literal null), visually distinct from a dangling reference.
- **Self-reference and cycles**: an object that references itself, or a cycle of references, must
  build, diff, and render without infinite loops.
- **Aliasing**: two references to the same target cell both connect to the one box; the target's own
  mutation is shown on the target box, not duplicated on every inbound arrow.
- **Body-only frame**: a frame with a header and a body string but no variables (native/opaque
  frames) renders the body.
- **Capped / omitted content**: objects referenced but not expanded, or fields/elements omitted for
  caps, must be represented so the diagram still conveys "there is more here" as it does today.
- **Diff identity across snapshots**: an element keeps its identity across suspends via a stable
  symbol id so the diff reports the same element as CHANGED rather than DELETED+NEW when it moves or
  is reassigned.
- **Deleted target**: when a referenced object disappears between snapshots, the deletion renders as
  a ghost exactly once, consistent with current behavior.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a single builder as the sole ingestion point for constructing a
  memory diagram, exposing operations to add a stack frame (as a header plus an ordered list of
  variables), add a body-only frame (a header plus a display string in place of variables), and add a
  heap object (a header plus an ordered list of fields).
- **FR-002**: The shared memory-diagram model MUST be expressed only in language- and editor-neutral
  concepts (frames, boxes, headers, variables/fields, primitive display values, references, and cell
  positions). It MUST NOT expose Java-, JDI-, or JVM-specific types or semantics (typed JVM object
  ids, boxed-primitive identity, enum-constant identity, Java string identity, "unreadable value",
  JDT types) to the diff or rendering layers. Cross-snapshot identity is carried only as an opaque,
  frontend-assigned token (see FR-007), never as a JVM id type.
- **FR-003**: A reference MUST address a **cell in the diagram**, identified by a **section (column:
  stack or heap)** and an **index (the row within that column)**, rather than the identity of a
  target object. Each section MUST maintain its own independent row numbering — an empty slot added
  in one column does not shift or create rows in the other.
- **FR-004**: The model MUST allow a reference whose target cell holds no object (a **dangling
  pointer**), and the rendering MUST show a dangling reference distinctly from a null value and from a
  reference to a populated cell.
- **FR-005**: The model MUST represent an absent value (uninitialized or null) as an explicit literal
  absence, visually distinct from a dangling reference.
- **FR-006**: A reference MUST resolve to whatever object occupies its target cell at the end of
  building, even if the reference was added before that object (forward references).
- **FR-007**: Each variable/field MUST carry a stable **symbol id**, and each **heap box MUST carry a
  stable identity token**, that identifies it across consecutive snapshots so the diff engine can
  classify it as NEW, CHANGED, UNCHANGED, or DELETED without relying on Java object ids. The token is
  opaque to the diff and rendering layers; the frontend assigns it (Eclipse JDT uses the JVM object
  id as the token value).
- **FR-008**: The diff engine MUST continue to classify frames, variables, objects, fields, and array
  elements as NEW / CHANGED / UNCHANGED / DELETED, and MUST continue to treat reference retargeting as
  the change on the referring slot while a target's own mutation is reported on the target box.
- **FR-009**: Deleted elements MUST continue to render exactly once as translucent ghosts, and the
  layout MUST remain sticky across suspends so boxes do not jump between snapshots.
- **FR-010**: The Eclipse JDT frontend MUST be reworked to populate the diagram exclusively through the
  builder, and MUST remain the only place JDI/JDT/debug-model types are referenced.
- **FR-011**: The rendering (figure) layer and the diff layer MUST read only the neutral model and MUST
  NOT reference any debugger-, language-, or editor-specific type.
- **FR-012**: The existing Java memory-diagram behavior — the set of things drawn, their special visual
  treatments (strings, boxed values, enums, arrays, capped/stub boxes), change highlighting, ghosts, and
  sticky layout — MUST be preserved for the Java case after the refactor. The renderer reconstructs those
  treatments by inferring presentation from neutral signals (type label, single-value vs named/positional
  fields, omitted-member metadata), not from typed model variants.
- **FR-013**: The neutral model MUST be constructible and fully exercisable in tests without a running
  editor or debugger, so the diff and layout suites remain JDK-only.
- **FR-014**: The model MUST preserve the ordering the frontend supplies (frame order top-of-stack
  first, variable/field order, array element order) because that order is part of the diagram's meaning.
- **FR-015**: Every heap object MUST be represented uniformly as a header plus an ordered list of fields,
  with no distinct language-specific box kinds: arrays are fields with positional identifiers, and
  single-value objects (strings, boxed primitives) are a box carrying a single display field. Content
  omitted for caps, or a target referenced but not expanded, MUST be conveyed as neutral metadata (an
  omitted-member count / unexpanded marker) so the renderer can reproduce today's capped/stub presentation.

### Key Entities

- **Memory Diagram**: the built, immutable description of one suspended program state — an ordered set
  of stack frames, an ordered set of heap boxes, and (as today) static/class content — everything the
  renderer and differ need, with nothing debugger-specific.
- **Builder**: the single object a frontend calls to assemble a Memory Diagram; hides construction
  details (cell assignment, reference resolution) from callers.
- **Frame**: a stack entry rendered as a header plus either an ordered list of variables or a body
  string (for frames that cannot be expressed as variables).
- **Heap Object (Box)**: a positioned box rendered uniformly as a header plus an ordered list of fields,
  carrying a stable identity token (for diffing) and its cell position. Arrays are fields with positional
  identifiers; single-value objects (strings, boxed primitives) carry a single display field; capped or
  unexpanded content is neutral metadata (an omitted-member count). There are no distinct
  language-specific box kinds — the renderer infers presentation from these neutral signals.
- **Variable / Field**: a named slot carrying an identifier, a type label, a stable symbol id (diff
  identity), and a value (which may be absent/null).
- **Value**: either a **Primitive** (a display string), a **Reference** (a cell), or an explicit
  absence (null). This closed set replaces the Java-specific value shapes.
- **Reference**: a value that points at a **cell** — a **section (column)** plus an **index (the row
  within that column)** — where each section has independent row numbering. The cell may be empty
  (dangling), populated, or populated later (forward reference).
- **Section**: the **column** a cell lives in — stack or heap — each column with its own independent
  row numbering.
- **Symbol Id / Identity Token**: a stable, opaque, frontend-assigned token identifying a variable/field
  — and a heap box — across snapshots; the basis for diffing independent of any platform's object
  identity. Eclipse JDT uses the JVM object id as the token value for boxes.
- **Change Status**: NEW / CHANGED / UNCHANGED / DELETED, unchanged in meaning from today.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A student debugging the `samples/` Java programs sees a memory diagram identical in
  content, special visual treatments, change highlighting, ghosts, and layout stability to the
  pre-refactor version — no visible regression across the full sample set.
- **SC-002**: The rendering layer and the diff layer contain zero references to Java-, JDI-, JDT-, or
  editor-specific types; all debugger-specific code is confined to the frontend that feeds the builder.
- **SC-003**: A complete memory diagram can be constructed through the builder in a test with no live
  debugger and no Java-specific types, and is correctly diffed and laid out — demonstrating the model is
  independently usable.
- **SC-004**: Adding a hypothetical second frontend requires implementing only builder-feeding code; no
  change to the diff or figure layers is needed to render its diagrams (validated via a builder-only
  test standing in for a second frontend).
- **SC-005**: A dangling pointer is representable in the model and renders distinctly from both a null
  value and a valid reference, verified by a synthetic diagram, since Java cannot produce one.
- **SC-006**: The change-highlighting behavior (NEW / CHANGED / UNCHANGED / DELETED, reference
  retargeting semantics, ghosts) produces the same results as before for equivalent Java snapshots.
- **SC-007**: The model, diff, and pure-layout test suites run without a running editor or debugger, and
  the full build (`mvn -f parent clean verify` under Java 21) passes.

## Assumptions

These reasonable defaults were chosen where the description left room; correct any that are wrong via
`/speckit-clarify` before planning.

- **Scope of frontends delivered now**: This refactor delivers the neutral model, the builder, and the
  migration of the existing **Eclipse/JDT** frontend onto it. No second editor (VS Code) or second
  language is implemented in this feature — the goal is to make future ones possible and to prove the
  seam with one real frontend plus builder-only tests.
- **Behavior-preserving for Java**: For the existing Java case this is a behavior-preserving refactor.
  Because the model uses a single uniform box shape (per Clarifications), the visual distinctions that
  exist today (strings, boxed values, enums, arrays, capped/stub boxes) are preserved by carrying enough
  neutral information (headers, type labels, single vs positional/named fields, omitted-member metadata)
  for the renderer to infer and present them as it does now.
- **Dangling pointer is delivered as model + distinct rendering**, validated with synthetic diagrams
  (Java cannot produce one). Its exact visual form is a design decision deferred to planning, constrained
  only by "distinct from null and from a valid reference" and by the learner-first visual principle.
- **`MemoryDiagramBuilder.java` is the design sketch**, not the final contract. Its method and type
  names (builder operations, `Variable`, `Value`/`Reference`/`Primitive`, `Section`, cell index) capture
  intent. The cell-addressing model is now fixed (section = column, index = row, independent per-column
  rows — see Clarifications); exact record signatures, naming, and how row indices map to physical layout
  positions are settled during planning.
- **Statics/class content stays in the model.** The current static-fields feature is carried into the
  neutral model (as neutral frames/boxes or an equivalent), preserving today's behavior.
- **Diff identity moves to opaque tokens.** Diffing no longer relies on JVM object ids directly; a stable
  symbol id per variable/field and a stable identity token per heap box provide cross-snapshot identity.
  The tokens are opaque to the diff/render layers; the Eclipse JDT frontend uses the JVM object id as a
  box's token value (see Clarifications).
- **One-way data flow and the hard architectural rules are retained**: builder population runs off the UI/
  debug-dispatch threads as today, the neutral model stays immutable and JDK-only, and JDI/JDT stays
  confined to the frontend.
