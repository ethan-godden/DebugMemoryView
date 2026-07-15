package com.github.ethangodden.debugmemoryview.model;

/**
 * One row in a frame or box. {@code identifier} and {@code typeLabel} are display text (a local/field
 * name or an array index, and an optional declared/component type). {@code symbolId} is a stable,
 * opaque, frontend-assigned identity used only for cross-snapshot diffing — unique within its owning
 * frame or box. {@code value} is the cell's {@link Value}, or {@code null} for the absent/null value.
 */
public record Variable(String symbolId, String identifier, String typeLabel, Value value) {
}
