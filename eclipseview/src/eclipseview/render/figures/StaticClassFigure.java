package eclipseview.render.figures;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ToolbarLayout;

import eclipseview.render.ColorPalette;
import eclipseview.render.FontKit;

/** One class inside the statics section: class-name header + static field rows. */
public class StaticClassFigure extends Figure {

    private final boolean ghost;

    public StaticClassFigure(String className, boolean ghost, ColorPalette palette, FontKit fonts) {
        this.ghost = ghost;
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);

        Label header = new Label(className);
        header.setLabelAlignment(PositionConstants.LEFT);
        header.setFont(ghost ? fonts.deleted() : fonts.header());
        header.setForegroundColor(ghost ? palette.deletedForeground() : palette.textForeground());
        header.setBorder(new MarginBorder(2, 6, 2, 6));
        add(header);
    }

    public void addRow(IFigure row) {
        add(row);
    }

    @Override
    public void paint(Graphics graphics) {
        if (ghost) {
            graphics.setAlpha(110);
        }
        super.paint(graphics);
    }
}
