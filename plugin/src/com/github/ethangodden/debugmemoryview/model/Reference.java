package com.github.ethangodden.debugmemoryview.model;

/**
 * A reference to a cell: a {@code (section, index)} coordinate where {@code index} is the row within
 * {@code section}. It resolves (via {@link MemoryDiagram#resolve}) to the box occupying that cell,
 * or to nothing — a <em>dangling</em> pointer — if the cell holds no box. A reference may be created
 * before its target box exists (a forward reference) and is resolved when the diagram is built.
 */
public record Reference(Section section, int index) implements Value {
}
