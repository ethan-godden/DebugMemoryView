package com.github.ethangodden.debugmemoryview.model;

/**
 * A display string for a cell: a primitive literal, an inlined string/boxed value, or {@code "?"}
 * for an unreadable value. Carries no type name — the type lives on the owning {@link Variable}'s
 * {@code typeLabel}. Two primitives compare equal (for diffing) iff their strings are equal.
 */
public record Primitive(String value) implements Value {
}
