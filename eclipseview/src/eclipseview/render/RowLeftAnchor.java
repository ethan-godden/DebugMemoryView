package eclipseview.render;

import org.eclipse.draw2d.AbstractConnectionAnchor;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Target anchor pinned to the left-edge midpoint of a heap box's first body
 * row (see HeapObjectFigure#getReferenceTargetFigure), replicating a pointer
 * to the start of the allocation. Same stock ancestor-listener tracking as
 * {@link ValueBoxAnchor}.
 */
public class RowLeftAnchor extends AbstractConnectionAnchor {

    public RowLeftAnchor(IFigure owner) {
        super(owner);
    }

    @Override
    public Point getLocation(Point reference) {
        Rectangle bounds = getOwner().getBounds().getCopy();
        getOwner().translateToAbsolute(bounds);
        return bounds.getLeft();
    }
}
