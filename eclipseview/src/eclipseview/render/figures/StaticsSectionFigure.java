package eclipseview.render.figures;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.ToolbarLayout;

import eclipseview.render.ColorPalette;
import eclipseview.render.FontKit;

/**
 * Collapsible "Static fields" section pinned at the top of the heap column,
 * inside the heap ScrollPane (statics are heap roots; keeping their rows in
 * the same viewport as their targets makes those connections single-viewport).
 */
public class StaticsSectionFigure extends Figure {

    private final Figure body;

    public StaticsSectionFigure(boolean collapsed, ColorPalette palette, FontKit fonts, Runnable onToggle) {
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        setBorder(new LineBorder(palette.boxBorder(), 1));

        Label header = BoxFigures.collapsibleHeader("Static fields", !collapsed, false, palette, fonts);
        add(header);

        body = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setStretchMinorAxis(true);
        bodyLayout.setSpacing(6);
        body.setLayoutManager(bodyLayout);
        body.setBorder(new MarginBorder(4, 0, 4, 0));
        if (!collapsed) {
            add(body);
        }

        BoxFigures.attachToggle(header, onToggle);
    }

    public void addClassFigure(StaticClassFigure classFigure) {
        body.add(classFigure);
    }
}
