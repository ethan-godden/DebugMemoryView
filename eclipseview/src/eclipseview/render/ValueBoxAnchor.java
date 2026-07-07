package eclipseview.render;

import org.eclipse.draw2d.AbstractConnectionAnchor;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Source anchor pinned to the center of a row's {@link
 * eclipseview.render.figures.ValueBoxFigure}, so the connection's tail (and
 * its filled dot) visibly sits inside the value cell — a pointer living in the
 * variable's box. Extending AbstractConnectionAnchor gives us the stock
 * ancestor-listener chain: a pane scroll physically moves the contents figure,
 * fires figureMoved up the tree, and re-routes every attached connection with
 * no manual re-anchoring.
 */
public class ValueBoxAnchor extends AbstractConnectionAnchor {

    public ValueBoxAnchor(IFigure valueBox) {
        super(valueBox);
    }

    @Override
    public Point getLocation(Point reference) {
        Rectangle bounds = getOwner().getBounds().getCopy();
        getOwner().translateToAbsolute(bounds);
        return bounds.getCenter();
    }
}
