package eclipseview.render;

import java.util.function.DoubleConsumer;

import org.eclipse.draw2d.Cursors;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.MouseMotionListener;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.graphics.Color;

/**
 * Draggable Draw2d divider between the stack and heap columns (an SWT sash is
 * impossible with the shared connection layer). Draw2d delivers drag events to
 * the figure that received mousePressed, so the drag keeps working when the
 * pointer outruns the 6 px sash; pane contents move during the drag, anchors
 * fire, and the arrows follow live.
 */
public class SashFigure extends Figure {

    private final DoubleConsumer onRatioChanged;
    private Color lineColor;

    public SashFigure(DoubleConsumer onRatioChanged) {
        this.onRatioChanged = onRatioChanged;
        setCursor(Cursors.SIZEWE);
        addMouseListener(new MouseListener.Stub() {
            @Override
            public void mousePressed(MouseEvent me) {
                if (me.button == 1) {
                    me.consume();
                }
            }
        });
        addMouseMotionListener(new MouseMotionListener.Stub() {
            @Override
            public void mouseDragged(MouseEvent me) {
                dragTo(me.x);
            }
        });
    }

    public void setLineColor(Color lineColor) {
        this.lineColor = lineColor;
        repaint();
    }

    private void dragTo(int mouseX) {
        IFigure parent = getParent();
        if (parent == null) {
            return;
        }
        // Event coordinates are sash-relative == absolute here (no coordinate systems),
        // as is the parent's client area.
        Rectangle area = parent.getClientArea();
        int usable = area.width - ColumnsLayout.GUTTER;
        if (usable <= 0) {
            return;
        }
        double ratio = (mouseX - area.x - ColumnsLayout.GUTTER / 2.0) / usable;
        onRatioChanged.accept(Math.clamp(ratio, ColumnsLayout.MIN_RATIO, ColumnsLayout.MAX_RATIO));
    }

    @Override
    protected void paintFigure(Graphics graphics) {
        if (lineColor == null) {
            return;
        }
        Rectangle bounds = getBounds();
        graphics.setBackgroundColor(lineColor);
        graphics.fillRectangle(bounds.x + bounds.width / 2 - 1, bounds.y, 2, bounds.height);
    }
}
