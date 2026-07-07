package eclipseview.render.figures;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ToolbarLayout;

import eclipseview.model.FieldModel;
import eclipseview.model.HeapObjectModel;
import eclipseview.model.ValueModel;
import eclipseview.render.ColorPalette;
import eclipseview.render.Ellipsis;
import eclipseview.render.FontKit;

/**
 * Tooltip body for a reference row: "Type #id" header plus the first few
 * content lines of the target object. Built lazily on first hover from the
 * cached snapshot — lets the user read what an arrow points to without
 * scrolling the heap pane.
 */
public class ObjectPreviewFigure extends Figure {

    private static final int MAX_LINES = 5;
    private static final int MAX_LINE_CHARS = 80;

    public ObjectPreviewFigure(HeapObjectModel model, ColorPalette palette, FontKit fonts) {
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        setBorder(new LineBorder(palette.boxBorder(), 1));

        Label header = new Label(model.simpleName() + " #" + model.id());
        header.setLabelAlignment(PositionConstants.LEFT);
        header.setFont(fonts.header());
        header.setForegroundColor(palette.textForeground());
        header.setBorder(new MarginBorder(3, 6, 3, 6));
        add(header);

        List<String> lines = new ArrayList<>();
        int omitted = collectLines(model, lines);
        for (String line : lines) {
            add(bodyLine(line, palette, fonts));
        }
        if (omitted > 0) {
            Label more = bodyLine("… +" + omitted + " more", palette, fonts);
            more.setForegroundColor(palette.mutedForeground());
            add(more);
        }
    }

    /** Fills up to MAX_LINES display lines; returns how many content lines were omitted. */
    private static int collectLines(HeapObjectModel model, List<String> lines) {
        switch (model.kind()) {
            case STRING -> {
                lines.add("\"" + Ellipsis.clipChars(model.displayText(), MAX_LINE_CHARS)
                        + (model.textTruncated() ? Ellipsis.ELLIPSIS : "") + "\"");
                return 0;
            }
            case BOXED -> {
                lines.add(model.displayText() + (model.jvmCached() ? "  (JVM cache)" : ""));
                return 0;
            }
            case STUB -> {
                lines.add("(not explored)");
                return 0;
            }
            case ARRAY -> {
                lines.add("length = " + model.arrayLength());
                int shown = Math.min(model.elements().size(), MAX_LINES - 1);
                for (int i = 0; i < shown; i++) {
                    ValueModel element = model.elements().get(i);
                    lines.add("[" + i + "] = " + Ellipsis.valueText(element, MAX_LINE_CHARS));
                }
                return model.elements().size() + model.elementsOmitted() - shown;
            }
            case ENUM, PLAIN -> {
                if (model.enumConstantName() != null) {
                    lines.add(model.enumConstantName());
                }
                int shown = Math.min(model.fields().size(), MAX_LINES - lines.size());
                for (int i = 0; i < shown; i++) {
                    FieldModel field = model.fields().get(i);
                    lines.add(field.name() + " = " + Ellipsis.valueText(field.value(), MAX_LINE_CHARS));
                }
                return model.fields().size() + model.fieldsOmitted() - shown;
            }
        }
        return 0;
    }

    private static Label bodyLine(String text, ColorPalette palette, FontKit fonts) {
        Label label = new Label(text);
        label.setLabelAlignment(PositionConstants.LEFT);
        label.setFont(fonts.value());
        label.setForegroundColor(palette.textForeground());
        label.setBorder(new MarginBorder(1, 6, 1, 6));
        return label;
    }
}
