package eclipseview.render;

import org.eclipse.draw2d.TextUtilities;
import org.eclipse.swt.graphics.Font;

import eclipseview.model.HeapReference;
import eclipseview.model.NullValue;
import eclipseview.model.PrimitiveValue;
import eclipseview.model.UnreadableValue;
import eclipseview.model.ValueModel;

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

    /** "java.util.ArrayList" -> "ArrayList", "demo.Outer$Inner[]" -> "Inner[]". */
    public static String simpleTypeName(String qualified) {
        if (qualified == null || qualified.isEmpty()) {
            return "?";
        }
        int bracket = qualified.indexOf('[');
        String base = bracket >= 0 ? qualified.substring(0, bracket) : qualified;
        String suffix = bracket >= 0 ? qualified.substring(bracket) : "";
        int dot = base.lastIndexOf('.');
        if (dot >= 0) {
            base = base.substring(dot + 1);
        }
        int dollar = base.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < base.length()) {
            base = base.substring(dollar + 1);
        }
        return base + suffix;
    }

    /** Display text of a value, char-capped. References render as "#id" (the arrow carries the rest). */
    public static String valueText(ValueModel value, int maxChars) {
        return clipChars(fullValueText(value), maxChars);
    }

    /** Untruncated display text of a value (tooltips). */
    public static String fullValueText(ValueModel value) {
        if (value instanceof PrimitiveValue primitive) {
            return primitive.text();
        }
        if (value instanceof NullValue) {
            return "null";
        }
        if (value instanceof HeapReference ref) {
            return "#" + ref.targetId();
        }
        if (value instanceof UnreadableValue) {
            return "<unreadable>";
        }
        return String.valueOf(value);
    }
}
