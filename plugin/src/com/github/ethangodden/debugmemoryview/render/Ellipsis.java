package com.github.ethangodden.debugmemoryview.render;

import org.apache.commons.lang3.StringUtils;

import com.github.ethangodden.debugmemoryview.model.Primitive;
import com.github.ethangodden.debugmemoryview.model.Value;

/** Text truncation and row-text composition helpers. */
public final class Ellipsis {

    public static final String ELLIPSIS = "…";

    private Ellipsis() {
    }

    /**
     * Display text of a value, char-capped. A {@link Primitive} renders its string; a reference
     * renders "→" (the arrow carries the target) and the absent/null value renders "null".
     */
    public static String valueText(Value value, int maxChars) {
        // abbreviate's width includes the marker, so maxChars + 1 keeps "maxChars chars + …".
        return StringUtils.abbreviate(fullValueText(value), ELLIPSIS, maxChars + 1);
    }

    /** Untruncated display text of a value (tooltips / previews). */
    public static String fullValueText(Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Primitive primitive) {
            return primitive.value();
        }
        return "→"; // Reference: the arrow carries the target
    }
}
