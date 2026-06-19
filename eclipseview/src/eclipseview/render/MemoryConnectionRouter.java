package eclipseview.render;

import java.util.function.Supplier;

import org.eclipse.draw2d.AbstractRouter;
import org.eclipse.draw2d.Connection;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.ViewportUtilities;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Deterministic bezier router. Cross-pane (stack -> heap) edges get control
 * points c1=(midX, start.y), c2=(midX, end.y) with midX in the gutter, varied
 * a few px per connection lane so parallel curves don't coincide. Intra-heap
 * edges (single heap viewport, statics included) leave the source box to the
 * RIGHT and arc on the right side of the column: both control points sit at
 * the rightmost box edge within the arc's vertical band (boxes align left but
 * their right edges are ragged, so clearing only the endpoints' boxes would
 * cross wider boxes in between) plus a bow proportional to the vertical
 * distance (capped at BOW_MAX, the padding the heap contents reserve on the
 * right), keeping nested arcs ordered; a self-reference's band is its own box,
 * so it degenerates to a small right hook. The point list is still set —
 * [start, hull extent point,
 * arrow-run point, end] — so bounds are right and the stock arrowhead orients
 * horizontally into the target row (approaching from the RIGHT for intra-heap
 * edges, from the LEFT for cross-pane ones); the straight segments are never
 * painted. The extent point (at midX or at the rightmost bow reach) matters:
 * scrolling can move end.x past midX, and without it the painted bulge would
 * escape the damage bounds, leaving stale arc pixels behind. Cross-pane
 * geometry is O(1) per connection per pass; intra-heap adds an O(#boxes)
 * baseline scan.
 */
public class MemoryConnectionRouter extends AbstractRouter {

    public static final int LANES = 5;
    private static final int LANE_SPACING = 6;
    private static final int ARROW_RUN = 16;
    private static final int BOW_MIN = 20;
    /** Public: the heap contents reserve this much right padding for the arcs. */
    public static final int BOW_MAX = 80;

    /** Rightmost heap box edge (absolute x) intersecting a vertical band; supplied by the controller. */
    @FunctionalInterface
    public interface ArcBaseline {
        int rightEdgeWithin(int topY, int bottomY);
    }

    private final Supplier<Rectangle> gutterAbsolute;
    private final ArcBaseline heapArcBaseline;

    public MemoryConnectionRouter(Supplier<Rectangle> gutterAbsolute, ArcBaseline heapArcBaseline) {
        this.gutterAbsolute = gutterAbsolute;
        this.heapArcBaseline = heapArcBaseline;
    }

    @Override
    public void route(Connection connection) {
        if (connection.getSourceAnchor() == null || connection.getTargetAnchor() == null) {
            return;
        }
        Point start = getStartPoint(connection).getCopy(); // absolute
        Point end = connection.getTargetAnchor().getLocation(start).getCopy();
        Viewport sourceViewport = viewportOf(connection.getSourceAnchor().getOwner());
        Viewport targetViewport = viewportOf(connection.getTargetAnchor().getOwner());

        Point c1;
        Point c2;
        PointList points = new PointList(4);
        points.addPoint(start);
        if (sourceViewport != targetViewport) {
            // Stack -> heap: S-curve swinging through the gutter.
            Rectangle gutter = gutterAbsolute.get();
            int lane = connection instanceof StateConnection stateConnection ? stateConnection.laneIndex() : 0;
            int midX = gutter.x + gutter.width / 2 + (lane % LANES - LANES / 2) * LANE_SPACING;
            c1 = new Point(midX, start.y);
            c2 = new Point(midX, end.y);
            // Extra point so the bounds cover the curve's hull even when the heap
            // pane is scrolled horizontally and end.x drops left of midX.
            points.addPoint(new Point(midX, (start.y + end.y) / 2));
            points.addPoint(new Point(end.x - ARROW_RUN, end.y)); // arrowhead enters from the LEFT
        } else {
            // Same viewport: bow right of every box in the arc's vertical band
            // (not just the endpoints' — right edges are ragged); a
            // self-reference's band is its own box, leaving a small hook.
            int bow = Math.min(BOW_MAX, BOW_MIN + Math.abs(end.y - start.y) / 4);
            int baseline = heapArcBaseline.rightEdgeWithin(Math.min(start.y, end.y), Math.max(start.y, end.y));
            int bowX = Math.max(baseline, Math.max(start.x, end.x)) + bow;
            c1 = new Point(bowX, start.y);
            c2 = new Point(bowX, end.y);
            // Extra point so the connection bounds cover the rightward hull.
            points.addPoint(new Point(bowX, (start.y + end.y) / 2));
            points.addPoint(new Point(end.x + ARROW_RUN, end.y)); // arrowhead enters from the RIGHT
        }
        points.addPoint(end);

        // Identity in this diagram (one shared coordinate system), kept for correctness.
        connection.translateToRelative(c1);
        connection.translateToRelative(c2);
        for (int i = 0; i < points.size(); i++) {
            Point p = points.getPoint(i);
            connection.translateToRelative(p);
            points.setPoint(p, i);
        }
        if (connection instanceof StateConnection stateConnection) {
            stateConnection.setControlPoints(c1, c2); // before setPoints: it triggers the repaint
        }
        connection.setPoints(points);
    }

    private static Viewport viewportOf(IFigure owner) {
        return owner == null ? null : ViewportUtilities.getNearestEnclosingViewport(owner);
    }
}
