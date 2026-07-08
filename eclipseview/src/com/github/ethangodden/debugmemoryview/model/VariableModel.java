package com.github.ethangodden.debugmemoryview.model;

public record VariableModel(String name, String declaredTypeName, ValueModel value) {

    /** Diff key: locals are unique by name within a scope; {@code this} uses the reserved name. */
    public String variableKey(String frameKey) {
        return frameKey + "#" + name;
    }
}
