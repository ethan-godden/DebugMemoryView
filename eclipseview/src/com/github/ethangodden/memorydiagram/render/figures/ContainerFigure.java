package com.github.ethangodden.memorydiagram.render.figures;

import org.eclipse.draw2d.Border;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;

import com.github.ethangodden.memorydiagram.model.diff.ChangeStatus;
import com.github.ethangodden.memorydiagram.render.ColorPalette;
import com.github.ethangodden.memorydiagram.render.FontKit;

/**
 * A variable container: an opaque, bordered box with a collapsible "▾/▸ title"
 * header and a body of variable rows. Stack frames are exactly this; heap
 * objects and arrays extend it ({@link HeapObjectFigure}) with id / hover /
 * width-clamp behaviour.
 *
 * The status border sits FLUSH against the rows — no inner margin — so a row's
 * value box touches the container border, matching a stack frame. Single left-
 * click on the ▾/▸ header toggles the box collapsed to header-only. DELETED
 * containers render as ghosts (dashed border, whole figure at alpha 110).
 */
public class ContainerFigure extends Figure {

    protected final ColorPalette palette;
    protected final ChangeStatus status;
    protected final Label header;
    protected final Figure body;
    private final boolean ghost;

    public ContainerFigure(String title, ChangeStatus status, boolean expanded,
            ColorPalette palette, FontKit fonts, Runnable onToggle) {
        this.palette = palette;
        this.status = status;
        ghost = status == ChangeStatus.DELETED;

        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        setBorder(borderFor(status, palette, false));

        header = BoxFigures.collapsibleHeader(title, expanded, ghost, palette, fonts);
        add(header);

        body = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setStretchMinorAxis(true);
        body.setLayoutManager(bodyLayout);
        if (expanded) {
            add(body);
        }

        BoxFigures.attachToggle(header, onToggle);
    }

    public void addRow(IFigure row) {
        body.add(row);
    }

    /**
     * The status border, flush against the rows: 1 px, thickened to 2 px for
     * NEW/CHANGED emphasis and dashed for DELETED. {@code hover} recolors it to
     * the accent while keeping the SAME width and style, so a hover or reveal
     * never shifts the box geometry (no inner margin needed to reserve the swap).
     */
    static Border borderFor(ChangeStatus status, ColorPalette palette, boolean hover) {
        Color color = hover ? palette.hoverAccent() : switch (status) {
            case NEW, CHANGED -> palette.statusForeground(status);
            case DELETED -> palette.deletedForeground();
            case UNCHANGED -> palette.boxBorder();
        };
        return switch (status) {
            case NEW, CHANGED -> new LineBorder(color, 2);
            case DELETED -> new LineBorder(color, 1, SWT.LINE_DASH);
            case UNCHANGED -> new LineBorder(color, 1);
        };
    }

    @Override
    public void paint(Graphics graphics) {
        if (ghost) {
            graphics.setAlpha(110); // inherits to header and rows
        }
        super.paint(graphics);
    }
}
