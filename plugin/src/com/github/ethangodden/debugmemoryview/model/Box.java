package com.github.ethangodden.debugmemoryview.model;

import java.util.List;

/**
 * A uniform heap object — a plain/array/string/boxed/enum object, or a statics class — rendered as a
 * frontend-composed {@code header} plus an ordered list of {@code fields}. Arrays are fields with
 * positional identifiers; single-value objects (strings, boxed primitives) carry a single field. No
 * language-specific box kinds exist; the renderer infers presentation from these neutral fields.
 *
 * <p>{@code boxToken} is the stable, opaque identity used for diffing, reference resolution, and
 * sticky layout. {@code explored} is {@code false} for a not-fully-captured box (header only, no
 * fields) — the differ never claims a change on it. {@code omittedCount} is how many fields/elements
 * were dropped for caps, driving the "+N not captured" row.
 */
public record Box(String boxToken, String header, List<Variable> fields, boolean explored, int omittedCount) {

    public Box {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
