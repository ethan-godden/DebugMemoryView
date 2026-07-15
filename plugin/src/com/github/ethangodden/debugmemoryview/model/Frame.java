package com.github.ethangodden.debugmemoryview.model;

import java.util.List;

/**
 * A stack entry: a display {@code header} plus EITHER an ordered list of {@code variables}
 * ({@code this} first, then locals) OR a {@code body} string shown in place of variables (for
 * native/obsolete/unreadable frames). Exactly one of the two forms is populated. {@code frameToken}
 * is the stable, opaque identity used to diff frames across snapshots.
 */
public record Frame(String frameToken, String header, List<Variable> variables, String body) {

    public Frame {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }

    /** A normal frame with ordered variable rows ({@code this} first, then locals). */
    public static Frame withVariables(String frameToken, String header, List<Variable> variables) {
        return new Frame(frameToken, header, variables, null);
    }

    /** A body-only frame (native/obsolete/unreadable): {@code body} is shown instead of variables. */
    public static Frame withBody(String frameToken, String header, String body) {
        return new Frame(frameToken, header, List.of(), body);
    }

    /** True when this frame shows a body string instead of variable rows. */
    public boolean hasBody() {
        return body != null;
    }
}
