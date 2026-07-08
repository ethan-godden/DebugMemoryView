package eclipseview.render;

import org.eclipse.draw2d.AbstractLayout;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;

import eclipseview.ui.ViewSettings;

/**
 * Splits the columns layer into stack | center gutter (sash centered) | heap.
 *
 * Width is surrendered in priority order as the view narrows, so the diagrams
 * stay on top of the center band instead of vanishing under it:
 * <ol>
 * <li>first the trailing slack beside the boxes (the empty column whitespace),</li>
 * <li>then the center gutter, from {@link #GUTTER} down to {@link #MIN_GUTTER},</li>
 * <li>and only once both are exhausted do the boxes themselves shrink / clip.</li>
 * </ol>
 * The gutter is wide by default so the bezier reference curves crossing it stay
 * distinguishable; a small minimum keeps tiny views degrading gracefully.
 */
public class ColumnsLayout extends AbstractLayout {

    public static final int GUTTER = 96;
    /** The gutter never shrinks below this: room for the 6 px sash plus a thin arrow lane. */
    public static final int MIN_GUTTER = 16;
    public static final int SASH_WIDTH = 6;
    public static final double MIN_RATIO = 0.2;
    public static final double MAX_RATIO = 0.8;

    // Past this a column's boxes ellipsize rather than forcing the gutter to
    // collapse — keeps one very wide stack frame from eating the whole center band.
    private static final int MAX_PROTECTED_WIDTH = 360;
    private static final Dimension MINIMUM = new Dimension(360, 120);

    private final ViewSettings settings;
    private final IFigure stackColumn;
    private final IFigure sash;
    private final IFigure heapColumn;
    private final IFigure stackContents;
    private final IFigure heapContents;

    private int currentGutter = GUTTER;

    public ColumnsLayout(ViewSettings settings, IFigure stackColumn, IFigure sash, IFigure heapColumn,
            IFigure stackContents, IFigure heapContents) {
        this.settings = settings;
        this.stackColumn = stackColumn;
        this.sash = sash;
        this.heapColumn = heapColumn;
        this.stackContents = stackContents;
        this.heapContents = heapContents;
    }

    @Override
    protected Dimension calculatePreferredSize(IFigure container, int wHint, int hHint) {
        return new Dimension(Math.max(wHint, MINIMUM.width), Math.max(hHint, MINIMUM.height));
    }

    @Override
    public Dimension getMinimumSize(IFigure container, int wHint, int hHint) {
        return MINIMUM.getCopy();
    }

    /** Current gutter width (px); the sash reads this to map a drag back onto the ratio. */
    public int currentGutter() {
        return currentGutter;
    }

    @Override
    public void layout(IFigure container) {
        Rectangle area = container.getClientArea();
        // Stack frames are unbounded, so cap their protected width (they ellipsize past it);
        // heap boxes are already clamped to a max, so protect their natural width in full.
        int stackNeed = Math.min(MAX_PROTECTED_WIDTH, contentWidth(stackContents));
        int heapNeed = contentWidth(heapContents);

        // The gutter yields before the boxes: full width only while both boxes still fit.
        currentGutter = Math.clamp((long) area.width - stackNeed - heapNeed, MIN_GUTTER, GUTTER);
        int band = Math.max(0, area.width - currentGutter);

        double ratio = Math.clamp(settings.sashRatio, MIN_RATIO, MAX_RATIO);
        int stackWidth = (int) Math.round(ratio * band);
        if (band >= stackNeed + heapNeed) {
            // Room for both boxes: the divider may slide within the slack, but never
            // pushes either box into clipping (the trailing slack absorbs the change first).
            stackWidth = Math.clamp((long) stackWidth, stackNeed, band - heapNeed);
        }
        stackWidth = Math.clamp((long) stackWidth, 0, band);

        stackColumn.setBounds(new Rectangle(area.x, area.y, stackWidth, area.height));
        sash.setBounds(new Rectangle(area.x + stackWidth + (currentGutter - SASH_WIDTH) / 2, area.y,
                SASH_WIDTH, area.height));
        heapColumn.setBounds(new Rectangle(area.x + stackWidth + currentGutter, area.y,
                Math.max(0, area.width - stackWidth - currentGutter), area.height));
    }

    /**
     * The column contents' natural width — measured with no width hint, so it reflects the
     * boxes' un-ellipsized width regardless of the current (viewport-tracked) column width.
     */
    private static int contentWidth(IFigure contents) {
        return contents.getPreferredSize(-1, -1).width;
    }
}
