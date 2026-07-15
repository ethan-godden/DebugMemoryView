package com.github.ethangodden.debugmemoryview.model;

/**
 * A value shown in a diagram cell: a {@link Primitive} display string or a {@link Reference} to a
 * cell. A {@code null} {@link Variable#value()} is the absent/uninitialized value, rendered
 * distinctly from a dangling reference (a {@link Reference} whose cell holds no box).
 */
public sealed interface Value permits Primitive, Reference {
}
