package com.github.ethangodden.memorydiagram.render;

import org.eclipse.draw2d.TextUtilities;
import org.eclipse.swt.graphics.Font;

import com.github.ethangodden.memorydiagram.model.HeapReference;
import com.github.ethangodden.memorydiagram.model.NullValue;
import com.github.ethangodden.memorydiagram.model.PrimitiveValue;
import com.github.ethangodden.memorydiagram.model.UnreadableValue;
import com.github.ethangodden.memorydiagram.model.ValueModel;

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

    /** Pixel-based clip via TextUtilities; used where Draw2d Labels cannot self-truncate. */
    public static String clip(String s, Font font, int availableWidth) {
        if (s == null || TextUtilities.INSTANCE.getStringExtents(s, font).width <= availableWidth) {
            return s;
        }
        int ellipsisWidth = TextUtilities.INSTANCE.getStringExtents(ELLIPSIS, font).width;
        int chars = TextUtilities.INSTANCE.getLargestSubstringConfinedTo(s, font,
                Math.max(0, availableWidth - ellipsisWidth));
        return s.substring(0, chars) + ELLIPSIS;
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
