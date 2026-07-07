package eclipseview.render;

import org.eclipse.draw2d.AbstractConnectionAnchor;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Mirror of {@link RowLeftAnchor} for same-viewport (intra-heap and
 * statics -> heap) edges: pinned to the RIGHT-edge midpoint of the target
 * box's first body row, so arrows that leave a source box to the right and
 * arc on the right side of the column land on the near edge. Same stock
 * ancestor-listener tracking as {@link ValueBoxAnchor}.
 */
public class RowRightAnchor extends AbstractConnectionAnchor {

    public RowRightAnchor(IFigure owner) {
        super(owner);
    }

    @Override
    public Point getLocation(Point reference) {
        Rectangle bounds = getOwner().getBounds().getCopy();
        getOwner().translateToAbsolute(bounds);
        return bounds.getRight();
    }
}
