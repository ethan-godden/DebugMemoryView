package eclipseview.render.figures;

import org.eclipse.draw2d.Border;
import org.eclipse.draw2d.CompoundBorder;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.swt.SWT;

import eclipseview.model.diff.ChangeStatus;
import eclipseview.render.ColorPalette;
import eclipseview.render.FontKit;

/**
 * One heap object box: header ("Type #id") + field / element / value rows.
 * The border encodes the object's change status; DELETED objects render as
 * ghosts (alpha 110 + dashed border). Single-click on the ▾/▸ header toggles
 * the box collapsed to header-only (same affordance as StackFrameFigure).
 * Aliasing shows structurally: every reference row
 * with the same target id anchors to the same figure's first body row (see
 * {@link #getReferenceTargetFigure()}).
 */
public class HeapObjectFigure extends Figure {

    /** Preferred-size floor only; the minimum may go lower so a narrow column can squeeze the box. */
    public static final int MIN_WIDTH = 112;
    public static final int MAX_WIDTH = 320;

    private final long id;
    private final Label header;
    private final Figure body;
    private final boolean ghost;
    private final Border baseBorder;
    private final ColorPalette palette;
    private boolean hoverHighlight;

    public HeapObjectFigure(long id, String title, ChangeStatus status, boolean collapsed,
            ColorPalette palette, FontKit fonts, Runnable onToggle) {
        this.id = id;
        this.palette = palette;
        ghost = status == ChangeStatus.DELETED;
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        baseBorder = borderFor(status, palette);
        setBorder(baseBorder);

        header = BoxFigures.collapsibleHeader(title, !collapsed, ghost, palette, fonts);
        add(header);

        body = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setStretchMinorAxis(true);
        body.setLayoutManager(bodyLayout);
        if (!collapsed) {
            add(body);
        }

        BoxFigures.attachToggle(header, onToggle);
    }

    public long id() {
        return id;
    }

    public void addRow(IFigure row) {
        body.add(row);
    }

    /** Tooltip on the header label (STRING boxes: the full quoted content). */
    public void setHeaderToolTip(IFigure tip) {
        header.setToolTip(tip);
    }

    /**
     * Where reference arrowheads land: the first variable row of the body (first
     * field / array element / STRING char cell / BOXED content row) — a pointer to the start
     * of the allocation. Falls back to the header when there are no such rows
     * (STUB, empty object, user-collapsed box).
     */
    public IFigure getReferenceTargetFigure() {
        if (body.getParent() == this) {
            for (IFigure child : body.getChildren()) {
                if (child instanceof VariableRowFigure row) {
                    return row;
                }
            }
        }
        return header;
    }

    public void setHoverHighlight(boolean on) {
        if (hoverHighlight == on) {
            return;
        }
        hoverHighlight = on;
        setBorder(on ? new LineBorder(palette.hoverAccent(), 2) : baseBorder);
        repaint();
    }

    // Every border reserves 2px of insets (the 1px lines pad with a margin), so
    // the hover swap to a 2px accent border never changes the box's geometry.
    private static Border borderFor(ChangeStatus status, ColorPalette palette) {
        return switch (status) {
            case NEW, CHANGED -> new LineBorder(palette.statusForeground(status), 2);
            case DELETED -> new CompoundBorder(
                    new LineBorder(palette.deletedForeground(), 1, SWT.LINE_DASH), new MarginBorder(1));
            case UNCHANGED -> new CompoundBorder(new LineBorder(palette.boxBorder(), 1), new MarginBorder(1));
        };
    }

    @Override
    public Dimension getPreferredSize(int wHint, int hHint) {
        Dimension size = super.getPreferredSize(wHint, hHint).getCopy();
        size.width = Math.max(MIN_WIDTH, Math.min(size.width, MAX_WIDTH));
        return size;
    }

    // ToolbarLayout sizes children by max(minWidth, min(clientArea, prefWidth)), so an
    // unclamped minimum (e.g. a long String content label) would override the MAX_WIDTH
    // clamp above and blow the box past the column. No MIN_WIDTH floor here: the real
    // minimum is the rows' box-preserving minimum (header/identifiers at their ellipsis
    // width, value boxes natural), so a narrowing column shrinks the box instead of
    // clipping it at the pane edge.
    @Override
    public Dimension getMinimumSize(int wHint, int hHint) {
        Dimension size = super.getMinimumSize(wHint, hHint).getCopy();
        size.width = Math.min(size.width, MAX_WIDTH);
        return size;
    }

    @Override
    public void paint(Graphics graphics) {
        if (ghost) {
            graphics.setAlpha(110); // inherits to header and rows
        }
        super.paint(graphics);
    }
}
