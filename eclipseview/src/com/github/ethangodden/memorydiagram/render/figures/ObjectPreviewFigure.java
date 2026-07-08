package com.github.ethangodden.memorydiagram.render.figures;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ToolbarLayout;

import com.github.ethangodden.memorydiagram.model.FieldModel;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel;
import com.github.ethangodden.memorydiagram.model.ValueModel;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.ArrayObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.BoxedObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.EnumObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.FieldsObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.StringObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.StubObject;
import com.github.ethangodden.memorydiagram.render.ColorPalette;
import com.github.ethangodden.memorydiagram.render.Ellipsis;
import com.github.ethangodden.memorydiagram.render.FontKit;

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
        return switch (model) {
            case StringObject str -> {
                lines.add("\"" + Ellipsis.clipChars(str.displayText(), MAX_LINE_CHARS)
                        + (str.textTruncated() ? Ellipsis.ELLIPSIS : "") + "\"");
                yield 0;
            }
            case BoxedObject box -> {
                lines.add(box.displayText() + (box.jvmCached() ? "  (JVM cache)" : ""));
                yield 0;
            }
            case StubObject stub -> {
                lines.add("(not explored)");
                yield 0;
            }
            case ArrayObject arr -> {
                lines.add("length = " + arr.arrayLength());
                int shown = Math.min(arr.elements().size(), MAX_LINES - 1);
                for (int i = 0; i < shown; i++) {
                    ValueModel element = arr.elements().get(i);
                    lines.add("[" + i + "] = " + Ellipsis.valueText(element, MAX_LINE_CHARS));
                }
                yield arr.elements().size() + arr.elementsOmitted() - shown;
            }
            case FieldsObject fields -> {
                if (fields instanceof EnumObject en && en.enumConstantName() != null) {
                    lines.add(en.enumConstantName());
                }
                int shown = Math.min(fields.fields().size(), MAX_LINES - lines.size());
                for (int i = 0; i < shown; i++) {
                    FieldModel field = fields.fields().get(i);
                    lines.add(field.name() + " = " + Ellipsis.valueText(field.value(), MAX_LINE_CHARS));
                }
                yield fields.fields().size() + fields.fieldsOmitted() - shown;
            }
        };
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
