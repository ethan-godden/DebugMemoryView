package com.github.ethangodden.debugmemoryview.render.figures;

import org.eclipse.draw2d.BorderLayout;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ScrollPane;

import com.github.ethangodden.debugmemoryview.render.ColorPalette;
import com.github.ethangodden.debugmemoryview.render.FontKit;

/** A diagram column: header label on top, ScrollPane filling the rest. */
public class ColumnFigure extends Figure {

    private final Label header;
    private final ScrollPane scrollPane;

    public ColumnFigure(String title, ScrollPane pane, ColorPalette palette, FontKit fonts) {
        this.scrollPane = pane;
        setLayoutManager(new BorderLayout());
        setOpaque(true);
        header = new Label(title);
        header.setLabelAlignment(PositionConstants.LEFT);
        header.setOpaque(true);
        header.setBorder(new MarginBorder(4, 8, 4, 8));
        add(header, BorderLayout.TOP);
        add(pane, BorderLayout.CENTER);
        restyle(palette, fonts);
    }

    public Label header() {
        return header;
    }

    public ScrollPane scrollPane() {
        return scrollPane;
    }

    /** Chrome persists across rebuilds; re-apply theme colors on every render. */
    public void restyle(ColorPalette palette, FontKit fonts) {
        setBackgroundColor(palette.columnBackground());
        header.setFont(fonts.header());
        header.setBackgroundColor(palette.headerBackground());
        header.setForegroundColor(palette.textForeground());
    }
}
