package eclipseview.render.figures;

import org.eclipse.draw2d.Border;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.swt.SWT;

import eclipseview.model.diff.ChangeStatus;
import eclipseview.render.ColorPalette;
import eclipseview.render.FontKit;

/**
 * One stack frame: collapsible header + variable rows. DELETED frames render
 * as ghosts (italic header, dashed border, whole figure at alpha 110).
 */
public class StackFrameFigure extends Figure {

    private final Figure body;
    private final boolean ghost;

    public StackFrameFigure(String title, ChangeStatus status, boolean expanded,
            ColorPalette palette, FontKit fonts, Runnable onToggle) {
        ghost = status == ChangeStatus.DELETED;
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        setBorder(borderFor(status, palette));

        Label header = BoxFigures.collapsibleHeader(title, expanded, ghost, palette, fonts);
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

    private static Border borderFor(ChangeStatus status, ColorPalette palette) {
        return switch (status) {
            case NEW, CHANGED -> new LineBorder(palette.statusForeground(status), 2);
            case DELETED -> new LineBorder(palette.deletedForeground(), 1, SWT.LINE_DASH);
            case UNCHANGED -> new LineBorder(palette.boxBorder(), 1);
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
