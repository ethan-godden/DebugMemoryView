package com.github.ethangodden.debugmemoryview.render;

import com.github.ethangodden.debugmemoryview.model.HeapReference;
import com.github.ethangodden.debugmemoryview.model.NullValue;
import com.github.ethangodden.debugmemoryview.model.PrimitiveValue;
import com.github.ethangodden.debugmemoryview.model.UnreadableValue;
import com.github.ethangodden.debugmemoryview.model.ValueModel;

/** Text truncation and row-text composition helpers. */
public final class Ellipsis {

    public static final String ELLIPSIS = "…";

    private Ellipsis() {
    }

    /** Hard character cap; applied before any pixel-based clipping. */
    public static String clipChars(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, Math.max(0, maxChars)) + ELLIPSIS;
    }

    /** Display text of a value, char-capped. References render as "#id" (the arrow carries the rest). */
    public static String valueText(ValueModel value, int maxChars) {
        return clipChars(fullValueText(value), maxChars);
    }

    /** Untruncated display text of a value (tooltips). */
    public static String fullValueText(ValueModel value) {
        return switch (value) {
            case PrimitiveValue primitive -> primitive.text();
            case NullValue nullValue -> "null";
            case HeapReference ref -> "#" + ref.targetId();
            case UnreadableValue unreadable -> "<unreadable>";
        };
    }
}
