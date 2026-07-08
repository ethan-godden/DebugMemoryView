package com.github.ethangodden.debugmemoryview.model;

/** A value that could not be read (GC race, obsolete frame, JDWP error). */
public record UnreadableValue(String error) implements ValueModel {
}
