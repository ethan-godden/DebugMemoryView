package com.github.ethangodden.debugmemoryview.render;

import org.apache.commons.lang3.StringUtils;

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

    /** Display text of a value, char-capped. References render as "#id" (the arrow carries the rest). */
    public static String valueText(ValueModel value, int maxChars) {
        // abbreviate's width includes the marker, so maxChars + 1 keeps "maxChars chars + …".
        return StringUtils.abbreviate(fullValueText(value), ELLIPSIS, maxChars + 1);
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
