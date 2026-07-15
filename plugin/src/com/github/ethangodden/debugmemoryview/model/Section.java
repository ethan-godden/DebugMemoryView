package com.github.ethangodden.debugmemoryview.model;

/**
 * A column of the memory diagram. A cell address is a {@code (section, index)} pair where
 * {@code index} is the row within the section; each section numbers its rows independently, so an
 * empty row in one column does not shift the other. Statics classes render as {@link #HEAP} boxes.
 */
public enum Section {
    STACK, HEAP
}
