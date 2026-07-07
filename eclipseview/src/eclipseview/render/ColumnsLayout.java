package eclipseview.render;

import org.eclipse.draw2d.AbstractLayout;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;

import eclipseview.ui.ViewSettings;

/**
 * Splits the columns layer into stack | 96 px gutter (sash centered) | heap
 * by the persisted sash ratio. The gutter is wide so the bezier reference
 * curves crossing it stay distinguishable. Consumes exactly the hint the
 * canvas gives (the root contents always fills the canvas), so the diagram is
 * fully responsive; a small minimum keeps tiny views degrading gracefully.
 */
public class ColumnsLayout extends AbstractLayout {

    public static final int GUTTER = 96;
    public static final int SASH_WIDTH = 6;
    public static final double MIN_RATIO = 0.2;
    public static final double MAX_RATIO = 0.8;

    private static final Dimension MINIMUM = new Dimension(360, 120);

    private final ViewSettings settings;
    private final IFigure stackColumn;
    private final IFigure sash;
    private final IFigure heapColumn;

    public ColumnsLayout(ViewSettings settings, IFigure stackColumn, IFigure sash, IFigure heapColumn) {
        this.settings = settings;
        this.stackColumn = stackColumn;
        this.sash = sash;
        this.heapColumn = heapColumn;
    }

    @Override
    protected Dimension calculatePreferredSize(IFigure container, int wHint, int hHint) {
        return new Dimension(Math.max(wHint, MINIMUM.width), Math.max(hHint, MINIMUM.height));
    }

    @Override
    public Dimension getMinimumSize(IFigure container, int wHint, int hHint) {
        return MINIMUM.getCopy();
    }

    @Override
    public void layout(IFigure container) {
        Rectangle area = container.getClientArea();
        int stackWidth = stackWidth(area.width);
        stackColumn.setBounds(new Rectangle(area.x, area.y, stackWidth, area.height));
        sash.setBounds(new Rectangle(area.x + stackWidth + (GUTTER - SASH_WIDTH) / 2, area.y,
                SASH_WIDTH, area.height));
        heapColumn.setBounds(new Rectangle(area.x + stackWidth + GUTTER, area.y,
                Math.max(0, area.width - stackWidth - GUTTER), area.height));
    }

    private int stackWidth(int totalWidth) {
        double ratio = Math.clamp(settings.sashRatio, MIN_RATIO, MAX_RATIO);
        return Math.max(0, (int) Math.round(ratio * (totalWidth - GUTTER)));
    }
}
