package com.github.ethangodden.debugmemoryview.model;

/** A primitive value; also used for inlined strings/boxed values when inlining is enabled. */
public record PrimitiveValue(String typeName, String text) implements ValueModel {
}
