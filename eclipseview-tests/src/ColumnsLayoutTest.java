import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;

import java.util.ArrayList;
import java.util.List;

import eclipseview.render.ColumnsLayout;

/**
 * Headless main()-based tests for {@link ColumnsLayout}'s width-surrender ladder:
 * the stack keeps its natural (guarded) width, surplus shows as trailing
 * whitespace to the right of the heap, the gutter yields next, and the reported
 * minimum width ({@code stackNeed + MIN_GUTTER + heapNeed}) is what the enclosing
 * viewport overflows to trigger whole-view horizontal scrolling. Draw2d Figures
 * are driven directly (no SWT Display needed — nothing paints). Exits 1 on any
 * failure.
 */
public final class ColumnsLayoutTest {

    // Mirrors the private ColumnsLayout guard; a stack wider than this ellipsizes.
    private static final int MAX_PROTECTED_WIDTH = 360;

    // ---------- tiny assert helpers ----------
    private static final List<String> failures = new ArrayList<>();
    private static int checks = 0;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            failures.add(msg);
        }
    }

    private static void checkEq(int expected, int actual, String msg) {
        checks++;
        if (expected != actual) {
            failures.add(msg + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ---------- fixture ----------
    private final IFigure stackColumn = new Figure();
    private final IFigure sash = new Figure();
    private final IFigure heapColumn = new Figure();
    private final IFigure stackContents = new Figure();
    private final IFigure heapContents = new Figure();
    private final IFigure container = new Figure();
    private final ColumnsLayout layout =
            new ColumnsLayout(stackColumn, sash, heapColumn, stackContents, heapContents);

    private ColumnsLayoutTest(int stackNatural, int heapNatural) {
        stackContents.setPreferredSize(new Dimension(stackNatural, 100));
        heapContents.setPreferredSize(new Dimension(heapNatural, 100));
    }

    private void layoutAt(int width) {
        container.setBounds(new Rectangle(0, 0, width, 400));
        layout.layout(container);
    }

    private static Rectangle b(IFigure f) {
        return f.getBounds();
    }

    // ---------- tests ----------

    /** Wide viewport: gutter is full, stack is natural, surplus is heap-right whitespace. */
    private static void testWideLeavesTrailingWhitespaceRightOfHeap() {
        ColumnsLayoutTest t = new ColumnsLayoutTest(200, 300);
        t.layoutAt(800);
        checkEq(200, b(t.stackColumn).width, "wide: stack keeps its natural width");
        checkEq(0, b(t.stackColumn).x, "wide: stack sits at the left edge");
        checkEq(ColumnsLayout.GUTTER, gutterOf(t), "wide: gutter is full");
        checkEq(800, b(t.heapColumn).right(), "wide: heap column fills to the right edge");
        // Heap boxes are flush-left (ToolbarLayout ALIGN_TOPLEFT), so the column's
        // surplus over its content width is whitespace on the RIGHT of the boxes.
        check(b(t.heapColumn).width > 300, "wide: heap column is wider than its content (trailing whitespace)");
    }

    /** Narrowing past the whitespace shrinks the gutter next, never the columns. */
    private static void testGutterYieldsAfterWhitespace() {
        ColumnsLayoutTest t = new ColumnsLayoutTest(200, 300);
        // naturalMin = 200 + 16 + 300 = 516; at 560 the gutter must be mid-range.
        t.layoutAt(560);
        checkEq(200, b(t.stackColumn).width, "medium: stack still natural");
        checkEq(60, gutterOf(t), "medium: gutter absorbed the shrink (560-500)");
        checkEq(300, b(t.heapColumn).width, "medium: heap column is exactly its content (no trailing whitespace)");
        check(gutterOf(t) < ColumnsLayout.GUTTER && gutterOf(t) > ColumnsLayout.MIN_GUTTER,
                "medium: gutter is between MIN_GUTTER and GUTTER");
    }

    /** At the natural minimum width the gutter bottoms out; below it the viewport scrolls. */
    private static void testAtMinimumGutterBottomsOut() {
        ColumnsLayoutTest t = new ColumnsLayoutTest(200, 300);
        t.layoutAt(516);
        checkEq(200, b(t.stackColumn).width, "min: stack natural");
        checkEq(ColumnsLayout.MIN_GUTTER, gutterOf(t), "min: gutter at MIN_GUTTER");
        checkEq(300, b(t.heapColumn).width, "min: heap exactly its content");
        checkEq(516, b(t.heapColumn).right(), "min: everything fits with nothing to spare");
    }

    /** getMinimumSize is stackNeed + MIN_GUTTER + heapNeed — the scroll trigger. */
    private static void testMinimumSizeDrivesScroll() {
        ColumnsLayoutTest t = new ColumnsLayoutTest(200, 300);
        Dimension min = t.layout.getMinimumSize(t.container, -1, -1);
        checkEq(200 + ColumnsLayout.MIN_GUTTER + 300, min.width, "min width = stackNeed + MIN_GUTTER + heapNeed");
    }

    /** A frame wider than the guard is capped (it ellipsizes) so it can't grow the scroll region unbounded. */
    private static void testPathologicalStackIsGuarded() {
        ColumnsLayoutTest t = new ColumnsLayoutTest(500, 300);
        Dimension min = t.layout.getMinimumSize(t.container, -1, -1);
        checkEq(MAX_PROTECTED_WIDTH + ColumnsLayout.MIN_GUTTER + 300, min.width,
                "guard: over-wide stack is capped at MAX_PROTECTED_WIDTH in the min width");
        t.layoutAt(1200);
        checkEq(MAX_PROTECTED_WIDTH, b(t.stackColumn).width, "guard: stack column never exceeds the guard width");
    }

    /** The divider is centered in the gutter, between the two columns. */
    private static void testSashCentersInGutter() {
        ColumnsLayoutTest t = new ColumnsLayoutTest(200, 300);
        t.layoutAt(800);
        checkEq(ColumnsLayout.SASH_WIDTH, b(t.sash).width, "sash: fixed narrow width");
        int sashCenter = b(t.sash).x + b(t.sash).width / 2;
        int gutterCenter = b(t.stackColumn).right() + gutterOf(t) / 2;
        checkEq(gutterCenter, sashCenter, "sash: centered in the gutter");
        check(b(t.sash).x >= b(t.stackColumn).right() && b(t.sash).right() <= b(t.heapColumn).x,
                "sash: sits between the columns");
    }

    /** The gutter is the horizontal gap the layout leaves between the two columns. */
    private static int gutterOf(ColumnsLayoutTest t) {
        return b(t.heapColumn).x - b(t.stackColumn).right();
    }

    public static void main(String[] args) {
        testWideLeavesTrailingWhitespaceRightOfHeap();
        testGutterYieldsAfterWhitespace();
        testAtMinimumGutterBottomsOut();
        testMinimumSizeDrivesScroll();
        testPathologicalStackIsGuarded();
        testSashCentersInGutter();

        System.out.println("ColumnsLayoutTest: " + checks + " checks, " + failures.size() + " failures");
        for (String f : failures) {
            System.out.println("  FAIL: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
