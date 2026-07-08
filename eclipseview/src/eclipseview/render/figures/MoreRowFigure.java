package eclipseview.render.figures;

import org.eclipse.draw2d.Clickable;
import org.eclipse.draw2d.Cursors;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;

import eclipseview.render.ColorPalette;
import eclipseview.render.FontKit;

/**
 * A clickable "+ N more…" expander row; clicking records a cap override in the
 * ExpansionMemory and re-renders (rebuild is the universal update primitive).
 */
public class MoreRowFigure extends Clickable {

    public MoreRowFigure(String text, ColorPalette palette, FontKit fonts, Runnable onExpand) {
        super(mutedRow(text, palette, fonts));
        setRolloverEnabled(true);
        setCursor(Cursors.HAND);
        addActionListener(event -> onExpand.run());
    }

    /** The shared muted secondary-row label; the "+N more…" and elided-info rows use the same recipe. */
    public static Label mutedRow(String text, ColorPalette palette, FontKit fonts) {
        Label label = new Label(text);
        label.setLabelAlignment(PositionConstants.LEFT);
        label.setFont(fonts.name());
        label.setForegroundColor(palette.mutedForeground());
        label.setBorder(new MarginBorder(2, 15, 2, 4));
        return label;
    }
}
