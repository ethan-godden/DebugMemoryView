package com.github.ethangodden.memorydiagram.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import org.eclipse.draw2d.ConnectionLayer;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.Layer;
import org.eclipse.draw2d.LayeredPane;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.TextUtilities;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;

import com.github.ethangodden.memorydiagram.model.FieldModel;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel;
import com.github.ethangodden.memorydiagram.model.HeapReference;
import com.github.ethangodden.memorydiagram.model.MemorySnapshot;
import com.github.ethangodden.memorydiagram.model.NullValue;
import com.github.ethangodden.memorydiagram.model.PrimitiveValue;
import com.github.ethangodden.memorydiagram.model.StackFrameModel;
import com.github.ethangodden.memorydiagram.model.StaticsClassModel;
import com.github.ethangodden.memorydiagram.model.UnreadableValue;
import com.github.ethangodden.memorydiagram.model.ValueModel;
import com.github.ethangodden.memorydiagram.model.VariableModel;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.ArrayObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.BoxedObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.EnumObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.FieldsObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.StringObject;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel.StubObject;
import com.github.ethangodden.memorydiagram.model.diff.ChangeStatus;
import com.github.ethangodden.memorydiagram.model.diff.MemoryDiff;
import com.github.ethangodden.memorydiagram.render.figures.ColumnFigure;
import com.github.ethangodden.memorydiagram.render.figures.ContainerFigure;
import com.github.ethangodden.memorydiagram.render.figures.HeapObjectFigure;
import com.github.ethangodden.memorydiagram.render.figures.MoreRowFigure;
import com.github.ethangodden.memorydiagram.render.figures.ObjectPreviewFigure;
import com.github.ethangodden.memorydiagram.render.figures.VariableRowFigure;
import com.github.ethangodden.memorydiagram.ui.ViewSettings;

/**
 * Owns the whole Draw2d figure tree of the memory diagram and orchestrates the
 * full rebuild per snapshot (the universal update primitive). One FigureCanvas;
 * a LayeredPane with a "columns" layer (stack | gutter+sash | heap, each column
 * an independent ScrollPane), a mouse-transparent ConnectionLayer on top, and a
 * scroll-thumb overlay layer above that (auto-hiding scrollbars, see
 * {@link ScrollThumbOverlay}).
 * Connections re-route on scroll purely via the stock anchor mechanism: the
 * pane contents figure physically moves, figureMoved fires up the tree, and
 * every AbstractConnectionAnchor re-fires — no manual re-anchoring anywhere.
 *
 * All methods must be called on the SWT UI thread.
 */
public class DiagramController {

    private static final String CAP_KEY_HEAP = "heap";
    private static final int WHEEL_STEP = 16;
    private static final int FLASH_MILLIS = 900;

    private final FigureCanvas canvas;
    private final ViewSettings settings;
    private final ColorPalette palette;
    private final FontKit fonts;

    private final LayeredPane rootPane;
    private final Layer columnsLayer;
    private final ColumnsLayout columnsLayout;
    private final ConnectionLayer connectionLayer;
    private final RunningOverlay overlay;
    private final ColumnFigure stackColumn;
    private final ColumnFigure heapColumn;
    private final ScrollPane stackPane;
    private final ScrollPane heapPane;
    private final Figure stackContents;
    private final Figure heapContents;
    private final SashFigure sash;

    private final ScrollThumbOverlay scrollThumbs;

    private final LayoutMemory layoutMemory = new LayoutMemory();
    private final ExpansionMemory expansion = new ExpansionMemory();
    private final HoverController hover;

    private final Map<Long, HeapObjectFigure> objectFigures = new HashMap<>();
    private final Map<VariableRowFigure, StateConnection> connectionsBySourceRow = new HashMap<>();
    private final Map<Long, HeapObjectModel> modelById = new HashMap<>(); // snapshot + ghosts, for previews

    private MemorySnapshot snapshot;
    private MemoryDiff diff;
    private int laneCounter;

    /** A reference row waiting for its arrow (created after all object figures exist). */
    private record PendingRef(VariableRowFigure row, long targetId, ChangeStatus status, boolean fromStack) {
    }

    public DiagramController(FigureCanvas canvas, ResourceManager resources, ViewSettings settings) {
        this.canvas = canvas;
        this.settings = settings;
        palette = new ColorPalette(resources);
        palette.refresh(canvas, settings.highlightChanges);
        fonts = new FontKit(resources);

        // The root contents fill the viewport while it is wide enough; once the
        // diagram's natural width (see ColumnsLayout) exceeds the viewport the
        // contents overflow and the whole view scrolls horizontally as one unit.
        // Height always fills — per-column vertical scrolling lives inside each
        // ScrollPane. Bars are NEVER (ScrollThumbOverlay paints the affordance);
        // the RangeModels stay live regardless, so wheel/reveal scroll works.
        canvas.setScrollBarVisibility(FigureCanvas.NEVER);
        canvas.getViewport().setContentsTracksWidth(true);
        canvas.getViewport().setContentsTracksHeight(true);

        stackContents = newVerticalContents(8);
        stackPane = new ScrollPane();
        // No stock bars anywhere: ScrollThumbOverlay paints auto-hiding thumbs instead.
        // Scrolling itself (wheel routing, RangeModels, reveal) never touches the bar figures.
        stackPane.setScrollBarVisibility(ScrollPane.NEVER);
        // Contents track the viewport width: a narrowing column SHRINKS each frame
        // to min(natural, available) — headers/identifiers ellipsize, value boxes
        // survive — instead of clipping at the pane edge. Below the figures'
        // box-preserving minimums the contents overflow again, so the horizontal
        // thumb + Shift+wheel stay wired (dormant until that extreme).
        stackPane.getViewport().setContentsTracksWidth(true);
        stackPane.setContents(stackContents);
        stackColumn = new ColumnFigure("Stack", stackPane, palette, fonts);

        heapContents = newVerticalContents(12);
        // Boxes sit flush LEFT; the intra-heap arcs bow on the RIGHT, so reserve
        // their bow width there: same-viewport connections clip to the pane's
        // client area, so the arcs must bow within the contents, not the gutter.
        heapContents.setBorder(new MarginBorder(8, 8, 8, 8 + MemoryConnectionRouter.BOW_MAX));
        heapPane = new ScrollPane();
        heapPane.setScrollBarVisibility(ScrollPane.NEVER);
        heapPane.getViewport().setContentsTracksWidth(true); // same responsive shrink as the stack
        heapPane.setContents(heapContents);
        heapColumn = new ColumnFigure("Heap", heapPane, palette, fonts);

        sash = new SashFigure();

        columnsLayer = new Layer();
        columnsLayout = new ColumnsLayout(stackColumn, sash, heapColumn, stackContents, heapContents);
        columnsLayer.setLayoutManager(columnsLayout);
        columnsLayer.add(stackColumn);
        columnsLayer.add(sash);
        columnsLayer.add(heapColumn);

        connectionLayer = new ConnectionLayer();
        connectionLayer.setEnabled(false); // transparent to mouse events; hover reaches the rows below
        connectionLayer.setAntialias(SWT.ON);
        connectionLayer.setConnectionRouter(
                new MemoryConnectionRouter(this::gutterAbsolute, this::heapArcBaseline));
        connectionLayer.setClippingStrategy(new MemoryClippingStrategy(stackPane, heapPane, this::gutterAbsolute));
        connectionLayer.setMinimumSize(new Dimension(0, 0));
        connectionLayer.setPreferredSize(new Dimension(0, 0));

        overlay = new RunningOverlay();
        overlay.setVisible(false);
        // Like the connection layer: without this, the overlay's current bounds
        // become the root pane's minimum size and the diagram can never shrink.
        overlay.setMinimumSize(new Dimension(0, 0));
        overlay.setPreferredSize(new Dimension(0, 0));

        Layer thumbLayer = new Layer();
        thumbLayer.setEnabled(false); // display-only thumbs; mouse-transparent like the connections
        thumbLayer.setMinimumSize(new Dimension(0, 0));
        thumbLayer.setPreferredSize(new Dimension(0, 0));

        rootPane = new LayeredPane();
        rootPane.add(columnsLayer, "columns");
        rootPane.add(connectionLayer, "connections");
        rootPane.add(thumbLayer, "scrollThumbs");
        rootPane.add(overlay, "overlay");

        scrollThumbs = new ScrollThumbOverlay(canvas, thumbLayer, palette::textForeground);
        // Vertical thumbs are per-column (each pane scrolls its own contents);
        // the single horizontal thumb rides the outer canvas viewport, which is
        // what scrolls the whole diagram sideways.
        scrollThumbs.track(stackPane.getViewport(), true);
        scrollThumbs.track(heapPane.getViewport(), true);
        scrollThumbs.track(canvas.getViewport(), false);

        hover = new HoverController(this);
        applyChrome();
    }

    /** The LayeredPane; the view sets it as the canvas contents. */
    public IFigure getRootFigure() {
        return rootPane;
    }

    /** Full rebuild; caches (snapshot, diff) so refresh()/toggles can re-render. */
    public void setSnapshot(MemorySnapshot newSnapshot, MemoryDiff newDiff) {
        snapshot = newSnapshot;
        diff = newDiff != null ? newDiff : MemoryDiff.initial(newSnapshot);
        rebuild();
    }

    /** Gray-out overlay without discarding figures (thread resumed). */
    public void setRunning(boolean running) {
        overlay.setVisible(running);
    }

    /** Empties the diagram and drops the cached snapshot. */
    public void clear() {
        snapshot = null;
        diff = null;
        hover.reset();
        discardFigures();
        stackColumn.header().setText("Stack");
        canvas.redraw();
    }

    /** clear() plus session-scoped memories (layout slots, expansion state). */
    public void clearSession() {
        clear();
        layoutMemory.clear();
        expansion.clear();
    }

    /** Re-renders the cached snapshot (theme switch / preference change pickup). */
    public void refresh() {
        if (snapshot != null) {
            rebuild();
        } else {
            palette.refresh(canvas, settings.highlightChanges);
            applyChrome();
            canvas.redraw();
        }
    }

    public void expandAll() {
        if (snapshot == null) {
            return;
        }
        expansion.expandAll();
        rebuild();
    }

    public void collapseAll() {
        if (snapshot == null) {
            return;
        }
        for (StackFrameModel frame : snapshot.frames()) {
            expansion.setFrameCollapsed(frame.frameKey(), true);
        }
        for (Long id : snapshot.heap().keySet()) {
            expansion.setObjectCollapsed(id.longValue(), true);
        }
        // Ghost frames/objects render too (when highlighting) — collapse them as well.
        for (StackFrameModel ghost : diff.deletedFrames()) {
            expansion.setFrameCollapsed(ghost.frameKey(), true);
        }
        for (HeapObjectModel ghost : diff.deletedObjects()) {
            expansion.setObjectCollapsed(ghost.id(), true);
        }
        for (StaticsClassModel staticsClass : snapshot.statics()) {
            expansion.setStaticClassCollapsed(staticsClass.className(), true);
        }
        for (StaticsClassModel ghostClass : diff.deletedStaticClasses()) {
            expansion.setStaticClassCollapsed(ghostClass.className(), true);
        }
        rebuild();
    }

    public void setShowStatics(boolean show) {
        settings.showStatics = show;
        if (snapshot != null) {
            rebuild();
        }
    }

    // An explicit menu cap supersedes any clicked "+N more…" override, which
    // would otherwise pin the count at MAX_VALUE for the rest of the session.

    public void clearHeapCapOverride() {
        expansion.clearCaps(CAP_KEY_HEAP);
    }

    public void clearFieldCapOverrides() {
        expansion.clearCaps("obj:");
        expansion.clearCaps("statics:");
    }

    public void clearArrayElementCapOverrides() {
        expansion.clearCaps("arr:");
        expansion.clearCaps("str:"); // STRING char cells share the array element cap
    }

    /**
     * Routed from the canvas SWT wheel listener. Draw2d dispatches wheel events to
     * the focus figure, so we drive scrolling ourselves. Shift = horizontal, which
     * is a whole-view gesture: the entire diagram scrolls sideways under the
     * viewport (the columns keep their natural width; see ColumnsLayout). A plain
     * wheel scrolls the column under the pointer vertically.
     */
    public void handleWheel(org.eclipse.swt.events.MouseEvent event) {
        int delta = -event.count * WHEEL_STEP;
        if ((event.stateMask & SWT.SHIFT) != 0) {
            scrollCanvasHorizontally(delta);
            return;
        }
        ScrollPane pane = paneAt(event.x, event.y);
        if (pane == null) {
            return;
        }
        pane.scrollVerticalTo(pane.getViewport().getVerticalRangeModel().getValue() + delta);
        scrollThumbs.show(pane.getViewport(), true);
    }

    /**
     * Native horizontal wheel (a trackpad two-finger swipe on macOS) arrives as a
     * separate SWT event that the MouseWheelListener never sees, so the view wires
     * it here: same whole-view sideways scroll as Shift+wheel.
     */
    public void handleHorizontalWheel(int count) {
        scrollCanvasHorizontally(-count * WHEEL_STEP);
    }

    private void scrollCanvasHorizontally(int delta) {
        // FigureCanvas.scrollToX clamps to the horizontal RangeModel.
        canvas.scrollToX(canvas.getViewport().getHorizontalRangeModel().getValue() + delta);
        // A clamped wheel changes no RangeModel value (so no listener fires) but
        // should still flash the thumb — the user is actively scrolling.
        scrollThumbs.show(canvas.getViewport(), false);
    }

    // ---------------------------------------------------------------- rebuild

    private void rebuild() {
        hover.reset();
        Point stackScroll = stackPane.getViewport().getViewLocation().getCopy();
        Point heapScroll = heapPane.getViewport().getViewLocation().getCopy();
        int canvasScrollX = canvas.getViewport().getViewLocation().x;
        canvas.setRedraw(false);
        try {
            palette.refresh(canvas, settings.highlightChanges);
            applyChrome();
            discardFigures();
            if (snapshot == null) {
                return;
            }
            stackColumn.header().setText("Stack — " + snapshot.threadName());
            List<PendingRef> refs = new ArrayList<>();
            buildHeap(refs); // first: object figures must exist before arrows and stack tooltips
            buildStack(refs);
            createConnections(refs);
        } finally {
            canvas.setRedraw(true);
        }
        // A rebuild can change the diagram's natural (minimum) width, and the
        // enclosing viewport derives its horizontal scroll range from that. Make
        // the relayout explicit instead of leaning on child-add revalidation
        // bubbling to the root, so the scroll range always tracks the new contents.
        columnsLayer.revalidate();
        // Restore scroll positions once layout is valid; RangeModel clamps shrunken content.
        canvas.getDisplay().asyncExec(() -> {
            if (canvas.isDisposed()) {
                return;
            }
            stackPane.getViewport().setViewLocation(stackScroll);
            heapPane.getViewport().setViewLocation(heapScroll);
            canvas.scrollToX(canvasScrollX);
        });
    }

    private void discardFigures() {
        scrollThumbs.reset(); // stale thumb geometry / timers must not outlive the figures
        stackContents.removeAll();
        heapContents.removeAll();
        connectionLayer.removeAll();
        objectFigures.clear();
        connectionsBySourceRow.clear();
        modelById.clear();
        laneCounter = 0;
    }

    private void applyChrome() {
        stackColumn.restyle(palette, fonts);
        heapColumn.restyle(palette, fonts);
        sash.setLineColor(palette.boxBorder());
    }

    // ------------------------------------------------------------------ stack

    private record FrameEntry(StackFrameModel frame, boolean ghost) {
    }

    private void buildStack(List<PendingRef> refs) {
        List<FrameEntry> entries = new ArrayList<>();
        for (StackFrameModel frame : snapshot.frames()) {
            entries.add(new FrameEntry(frame, false));
        }
        if (palette.isHighlighting()) {
            for (StackFrameModel ghost : diff.deletedFrames()) {
                entries.add(new FrameEntry(ghost, true));
            }
        }
        // Top of stack first; a popped (ghost) frame sorts above a survivor at equal depth.
        entries.sort((a, b) -> {
            int byDepth = Integer.compare(b.frame().depthFromBottom(), a.frame().depthFromBottom());
            return byDepth != 0 ? byDepth : Boolean.compare(b.ghost(), a.ghost());
        });

        for (FrameEntry entry : entries) {
            StackFrameModel frame = entry.frame();
            String frameKey = frame.frameKey();
            boolean ghost = entry.ghost();
            ChangeStatus status = ghost ? ChangeStatus.DELETED : palette.effective(diff.frameStatusOf(frameKey));
            // Every frame builds its rows eagerly; only user-collapsed frames stay shut.
            boolean expanded = !expansion.isFrameCollapsed(frameKey);
            ContainerFigure figure = new ContainerFigure(frame.label(), status, expanded, palette, fonts, () -> {
                expansion.setFrameCollapsed(frameKey, expanded);
                rebuild();
            });
            if (expanded) {
                populateFrame(figure, frame, ghost, refs);
            }
            stackContents.add(figure);
        }
        if (snapshot.framesOmitted() > 0) {
            stackContents.add(infoRow("(+" + snapshot.framesOmitted() + " frames omitted)"));
        }
    }

    private void populateFrame(ContainerFigure figure, StackFrameModel frame, boolean ghost,
            List<PendingRef> refs) {
        String frameKey = frame.frameKey();
        if (frame.obsolete()) {
            figure.addRow(infoRow("(obsolete frame)"));
        }
        if (frame.nativeFrame()) {
            figure.addRow(infoRow("(native method)"));
        }
        if (!frame.localsAvailable() && !frame.obsolete() && !frame.nativeFrame()) {
            figure.addRow(infoRow("(locals unavailable)"));
        }
        List<VariableModel> variables = frame.allVariables(); // this first, then locals
        renderCapped("frame:" + frameKey, variables.size(), settings.maxLocalsPerFrameRendered, i -> {
            VariableModel variable = variables.get(i);
            ChangeStatus status = ghost ? ChangeStatus.DELETED
                    : palette.effective(diff.variableStatusOf(variable.variableKey(frameKey)));
            return newRow(variable.name(), variable.declaredTypeName(), variable.value(), status, refs, true);
        }, figure::addRow);
        if (!ghost && palette.isHighlighting()) {
            List<VariableModel> ghostVariables = diff.deletedVariables().get(frameKey);
            if (ghostVariables != null) {
                for (VariableModel variable : ghostVariables) {
                    figure.addRow(newRow(variable.name(), variable.declaredTypeName(), variable.value(),
                            ChangeStatus.DELETED, refs, true));
                }
            }
        }
    }

    // ------------------------------------------------------------------- heap

    private void buildHeap(List<PendingRef> refs) {
        List<HeapObjectModel> ghosts = palette.isHighlighting() ? diff.deletedObjects() : List.of();
        modelById.putAll(snapshot.heap());
        for (HeapObjectModel ghost : ghosts) {
            modelById.put(Long.valueOf(ghost.id()), ghost);
        }

        List<Long> order = HeapLayouter.assign(snapshot, ghosts, layoutMemory);

        // Heap cap chosen in extraction BFS order (heap map order): roots-first survival.
        int heapCap = expansion.capOf(CAP_KEY_HEAP, settings.maxHeapObjectsRendered);
        Set<Long> rendered = new HashSet<>();
        for (Long id : snapshot.heap().keySet()) {
            if (rendered.size() >= heapCap) {
                break;
            }
            rendered.add(id);
        }
        int omitted = snapshot.heap().size() - rendered.size();
        Set<Long> ghostIds = new HashSet<>();
        for (HeapObjectModel ghost : ghosts) {
            ghostIds.add(Long.valueOf(ghost.id()));
        }

        // One vertical column of boxes; ~16 px between OBJECTS (rows inside a box
        // stack with zero spacing — they read as contiguous memory cells).
        Figure heapBody = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setSpacing(16);
        bodyLayout.setStretchMinorAxis(false); // boxes take natural width <= 320
        heapBody.setLayoutManager(bodyLayout);

        // Statics are heap roots: each class with static fields renders as its own
        // container box at the top of the column, above the objects.
        boolean hasStatics = !snapshot.statics().isEmpty()
                || (palette.isHighlighting()
                        && (!diff.deletedStaticClasses().isEmpty() || !diff.deletedStaticFields().isEmpty()));
        if (settings.showStatics && hasStatics) {
            buildStatics(refs, heapBody);
        }

        for (Long id : order) {
            boolean ghost = ghostIds.contains(id);
            if (!ghost && !rendered.contains(id)) {
                continue;
            }
            HeapObjectModel model = modelById.get(id);
            if (model == null) {
                continue;
            }
            heapBody.add(buildObjectFigure(model, ghost, refs));
        }
        if (omitted > 0) {
            heapBody.add(unrenderedBox(omitted));
        }
        heapContents.add(heapBody);
    }

    private HeapObjectFigure buildObjectFigure(HeapObjectModel model, boolean ghost, List<PendingRef> refs) {
        long id = model.id();
        ChangeStatus status = ghost ? ChangeStatus.DELETED : palette.effective(diff.objectStatusOf(id));
        boolean collapsed = expansion.isObjectCollapsed(id);
        HeapObjectFigure figure = new HeapObjectFigure(id, objectTitle(model), status, collapsed, palette, fonts,
                () -> {
                    expansion.setObjectCollapsed(id, !collapsed);
                    rebuild();
                });
        if (model instanceof StringObject str) {
            // Full quoted content on the header (rows are char cells); works collapsed too.
            String content = str.displayText() != null ? str.displayText() : "";
            figure.setHeaderToolTip(tooltipLabel(
                    "\"" + content + (str.textTruncated() ? Ellipsis.ELLIPSIS : "") + "\""));
        }
        if (!collapsed) {
            populateObject(figure, model, ghost, refs);
        }
        objectFigures.put(Long.valueOf(id), figure); // aliasing: same id -> same figure instance
        return figure;
    }

    private void populateObject(HeapObjectFigure figure, HeapObjectModel model, boolean ghost,
            List<PendingRef> refs) {
        long id = model.id();
        ChangeStatus plainStatus = ghost ? ChangeStatus.DELETED : ChangeStatus.UNCHANGED;
        switch (model) {
            case StringObject str -> {
                // Char-array rendering: one indexed cell per extracted character
                // ("0 : [h]"), capped like an array. Strings are immutable-by-identity
                // in the diff (no per-element statuses); rows stay UNCHANGED-styled.
                // The full quoted content lives in the header tooltip (buildObjectFigure).
                String content = str.displayText() != null ? str.displayText() : "";
                // Chars are capped like an array; only unrendered EXTRACTED chars count toward "+N more".
                renderCapped("str:" + id, content.length(), settings.maxArrayElementsRendered, i -> {
                    char c = content.charAt(i);
                    VariableRowFigure row = new VariableRowFigure(Integer.toString(i),
                            String.valueOf(c), null, plainStatus, palette, fonts);
                    hover.hookRow(row);
                    row.setToolTip(tooltipLabel("char : '" + c + "'"));
                    return row;
                }, figure::addRow);
                if (str.textTruncated()) {
                    figure.addRow(infoRow("(truncated)"));
                }
            }
            case BoxedObject box -> {
                String value = box.displayText() != null ? box.displayText() : "?";
                String text = value + (box.jvmCached() ? "  (JVM cache)" : "");
                VariableRowFigure row = new VariableRowFigure(null, text, null, plainStatus, palette, fonts);
                hover.hookRow(row);
                figure.addRow(row);
            }
            case StubObject stub -> figure.addRow(infoRow("(not explored)"));
            case ArrayObject arr -> {
                String componentType = componentTypeOf(arr.typeName());
                // The index is the identifier ("0 : <box>"); length lives in the header.
                // The component type plays the declared type in the row tooltips.
                populateMembers(figure, "arr:" + id, arr.elements().size(),
                        settings.maxArrayElementsRendered, i -> {
                            ChangeStatus status = ghost ? ChangeStatus.DELETED
                                    : palette.effective(diff.elementChanged(id, i) ? ChangeStatus.CHANGED
                                            : ChangeStatus.UNCHANGED);
                            return newRow(Integer.toString(i), componentType, arr.elements().get(i),
                                    status, refs, false);
                        }, arr.elementsOmitted());
            }
            case FieldsObject fields -> {
                if (fields instanceof EnumObject en && en.enumConstantName() != null) {
                    VariableRowFigure constantRow = new VariableRowFigure(null, en.enumConstantName(),
                            null, plainStatus, palette, fonts);
                    hover.hookRow(constantRow);
                    figure.addRow(constantRow);
                }
                populateMembers(figure, "obj:" + id, fields.fields().size(),
                        settings.maxFieldsPerObjectRendered, i -> {
                            FieldModel field = fields.fields().get(i);
                            ChangeStatus status = ghost ? ChangeStatus.DELETED
                                    : palette.effective(diff.fieldStatusOf(id, field.fieldKey()));
                            return newRow(field.name(), field.declaredTypeName(), field.value(), status, refs, false);
                        }, fields.fieldsOmitted());
            }
        }
    }

    /** "int[]" -> "int", "demo.Point[][]" -> "demo.Point[]"; null when not an array type name. */
    private static String componentTypeOf(String arrayTypeName) {
        if (arrayTypeName != null && arrayTypeName.endsWith("[]")) {
            return arrayTypeName.substring(0, arrayTypeName.length() - 2);
        }
        return null;
    }

    private static String objectTitle(HeapObjectModel model) {
        if (model instanceof ArrayObject arr) {
            String simple = arr.simpleName() != null ? arr.simpleName() : "?[]";
            int bracket = simple.indexOf("[]");
            String base = bracket >= 0 ? simple.substring(0, bracket) : simple;
            return base + "[" + arr.arrayLength() + "] #" + arr.id();
        }
        return model.simpleName() + " #" + model.id();
    }

    private IFigure unrenderedBox(int omitted) {
        Figure box = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        box.setLayoutManager(layout);
        box.setOpaque(true);
        box.setBackgroundColor(palette.boxBackground());
        box.setBorder(new org.eclipse.draw2d.LineBorder(palette.boxBorder(), 1));
        box.add(new MoreRowFigure("+ " + omitted + " objects not rendered…", palette, fonts, () -> {
            expansion.raiseCap(CAP_KEY_HEAP);
            rebuild();
        }));
        return box;
    }

    // ---------------------------------------------------------------- statics

    private void buildStatics(List<PendingRef> refs, Figure heapBody) {
        for (StaticsClassModel staticsClass : snapshot.statics()) {
            heapBody.add(buildStaticClass(staticsClass, false, refs));
        }
        if (palette.isHighlighting()) {
            for (StaticsClassModel ghostClass : diff.deletedStaticClasses()) {
                heapBody.add(buildStaticClass(ghostClass, true, refs));
            }
        }
    }

    /** One static-fields class as a container box titled "Class <name>" — same chrome as an object box. */
    private ContainerFigure buildStaticClass(StaticsClassModel staticsClass, boolean ghost, List<PendingRef> refs) {
        // Header shows the simple name; the fully-qualified className stays the
        // stable key for collapse state, caps, and ghost-field lookup.
        String className = staticsClass.className();
        ChangeStatus status = ghost ? ChangeStatus.DELETED : ChangeStatus.UNCHANGED;
        boolean collapsed = expansion.isStaticClassCollapsed(className);
        ContainerFigure figure = new ContainerFigure("Class " + staticsClass.simpleName(), status, !collapsed,
                palette, fonts,
                () -> {
                    expansion.setStaticClassCollapsed(className, !collapsed);
                    rebuild();
                });
        if (collapsed) {
            return figure;
        }
        if (ghost) {
            // Whole class removed: every field renders as a DELETED ghost row.
            for (FieldModel field : staticsClass.fields()) {
                figure.addRow(newRow(field.name(), field.declaredTypeName(), field.value(),
                        ChangeStatus.DELETED, refs, false));
            }
            return figure;
        }
        populateMembers(figure, "statics:" + className, staticsClass.fields().size(),
                settings.maxFieldsPerObjectRendered, i -> {
                    FieldModel field = staticsClass.fields().get(i);
                    ChangeStatus fieldStatus = palette.effective(diff.staticStatusOf(field.fieldKey()));
                    return newRow(field.name(), field.declaredTypeName(), field.value(), fieldStatus, refs, false);
                }, staticsClass.fieldsOmitted());
        if (palette.isHighlighting()) {
            List<FieldModel> ghostFields = diff.deletedStaticFields().get(className);
            if (ghostFields != null) {
                for (FieldModel field : ghostFields) {
                    figure.addRow(newRow(field.name(), field.declaredTypeName(), field.value(),
                            ChangeStatus.DELETED, refs, false));
                }
            }
        }
        return figure;
    }

    // ------------------------------------------------------------------- rows

    /**
     * "identifier : <box>" — no type text (types live in the heap box headers).
     * The box holds the primitive text; it is empty for references (the arrow
     * tail sits inside it) and nulls, "?" for unreadables. The declared type
     * moves into the tooltip.
     */
    private VariableRowFigure newRow(String name, String declaredTypeName, ValueModel value, ChangeStatus status,
            List<PendingRef> refs, boolean fromStack) {
        Long targetId = value instanceof HeapReference ref ? Long.valueOf(ref.targetId()) : null;
        VariableRowFigure row = new VariableRowFigure(name, boxTextOf(value), targetId, status, palette, fonts);
        hover.hookRow(row); // every row hover-tints; reference rows add click/preview/target outline
        if (targetId != null) {
            refs.add(new PendingRef(row, targetId.longValue(), status, fromStack));
        } else if (value instanceof PrimitiveValue primitive) {
            row.setToolTip(tooltipLabel(typedTooltip(declaredTypeName, primitive.text())));
        } else if (value instanceof NullValue) {
            row.setToolTip(tooltipLabel(typedTooltip(declaredTypeName, "null")));
        } else if (value instanceof UnreadableValue unreadable) {
            row.setToolTip(tooltipLabel(unreadable.error()));
        }
        return row;
    }

    /** In-box text: primitives verbatim (char-capped), "?" for unreadables, else empty. */
    private String boxTextOf(ValueModel value) {
        if (value instanceof PrimitiveValue primitive) {
            return Ellipsis.clipChars(primitive.text(), settings.maxValueChars);
        }
        if (value instanceof UnreadableValue) {
            return "?";
        }
        return ""; // HeapReference / NullValue: an empty cell
    }

    private static String typedTooltip(String declaredTypeName, String fullValue) {
        return declaredTypeName == null ? fullValue : declaredTypeName + " : " + fullValue;
    }

    /**
     * Renders up to {@code cap(capKey)} of {@code total} rows — {@code rowFor.apply(i)} builds each
     * and {@code addRow} appends it — then a "+N more…" expander when the list is capped. Returns
     * the number of rows shown so the caller can append its own trailing info rows.
     */
    private int renderCapped(String capKey, int total, int defaultMax,
            IntFunction<IFigure> rowFor, Consumer<IFigure> addRow) {
        int shown = Math.min(total, expansion.capOf(capKey, defaultMax));
        for (int i = 0; i < shown; i++) {
            addRow.accept(rowFor.apply(i));
        }
        if (shown < total) {
            addRow.accept(moreRow(total - shown, capKey));
        }
        return shown;
    }

    /**
     * A member box's body: up to the render cap of {@code count} rows (built by
     * {@code rowFor}, with a "+N more…" expander when the cap bites), then a
     * "(+N not captured)" info row for the {@code omitted} members dropped at
     * extraction. Shared by object fields, array elements, and static fields.
     */
    private void populateMembers(ContainerFigure figure, String capKey, int count, int defaultMax,
            IntFunction<IFigure> rowFor, int omitted) {
        renderCapped(capKey, count, defaultMax, rowFor, figure::addRow);
        if (omitted > 0) {
            figure.addRow(infoRow("(+" + omitted + " not captured)"));
        }
    }

    private MoreRowFigure moreRow(int hidden, String capKey) {
        return new MoreRowFigure("+ " + hidden + " more…", palette, fonts, () -> {
            expansion.raiseCap(capKey);
            rebuild();
        });
    }

    private Label infoRow(String text) {
        return MoreRowFigure.mutedRow(text, palette, fonts);
    }

    private Label tooltipLabel(String text) {
        Label tip = new Label(" " + Ellipsis.clipChars(text, 300) + " ");
        tip.setFont(fonts.value());
        return tip;
    }

    // ------------------------------------------------------------ connections

    private void createConnections(List<PendingRef> refs) {
        for (PendingRef ref : refs) {
            HeapObjectFigure target = objectFigures.get(Long.valueOf(ref.targetId()));
            if (target == null) {
                // Target elided by the heap cap: no arrow, explain on the row instead.
                ref.row().setToolTip(
                        tooltipLabel("Target #" + ref.targetId() + " not shown — raise the heap object cap"));
                continue;
            }
            // Round-robin lanes for cross-pane edges, assigned in build order (top of
            // stack first), so parallel curves spread across the gutter.
            int lane = ref.fromStack() ? laneCounter++ % MemoryConnectionRouter.LANES : 0;
            StateConnection connection = new StateConnection(ref.status(), lane, palette);
            connection.setSourceAnchor(new RowEdgeAnchor(ref.row().valueBox(), Rectangle::getCenter));
            // Cross-pane arrows land on the row's LEFT edge (facing the gutter);
            // same-viewport ones (heap/statics sources) land on its RIGHT edge,
            // matching the router's right-side arcs.
            connection.setTargetAnchor(ref.fromStack()
                    ? new RowEdgeAnchor(target.getReferenceTargetFigure(), Rectangle::getLeft)
                    : new RowEdgeAnchor(target.getReferenceTargetFigure(), Rectangle::getRight));
            connectionsBySourceRow.put(ref.row(), connection);
            connectionLayer.add(connection);
        }
    }

    // ---------------------------------------------------- hover/reveal support

    ColorPalette palette() {
        return palette;
    }

    StateConnection connectionFor(VariableRowFigure row) {
        return connectionsBySourceRow.get(row);
    }

    HeapObjectFigure objectFigureFor(long id) {
        return objectFigures.get(Long.valueOf(id));
    }

    /** Re-adds the connection so it paints on top of its siblings while hovered. */
    void raiseConnection(StateConnection connection) {
        connectionLayer.add(connection);
    }

    /** Lazy tooltip body for a reference row; null when the target model is unknown. */
    IFigure buildPreview(VariableRowFigure row) {
        if (row.targetId() == null) {
            return null;
        }
        HeapObjectModel model = modelById.get(row.targetId());
        return model == null ? null : new ObjectPreviewFigure(model, palette, fonts);
    }

    /** Click-to-reveal: scroll the heap pane to the target and flash its outline. */
    void revealTarget(VariableRowFigure row) {
        if (row.targetId() == null) {
            return;
        }
        long id = row.targetId().longValue();
        HeapObjectFigure target = objectFigures.get(Long.valueOf(id));
        if (target == null) {
            return;
        }
        RevealUtil.reveal(heapPane, target); // vertical within the heap pane
        RevealUtil.revealHorizontally(canvas.getViewport(), target); // bring the heap column into the window
        scrollThumbs.show(heapPane.getViewport(), true);
        scrollThumbs.show(canvas.getViewport(), false);
        target.setHoverHighlight(true);
        canvas.getDisplay().timerExec(FLASH_MILLIS, () -> {
            if (canvas.isDisposed()) {
                return;
            }
            // Only un-flash if this figure is still current and not hover-held.
            if (objectFigures.get(Long.valueOf(id)) == target && !hover.isCurrentTarget(target)) {
                target.setHoverHighlight(false);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private Rectangle gutterAbsolute() {
        Rectangle stackBounds = stackColumn.getBounds().getCopy();
        Rectangle heapBounds = heapColumn.getBounds().getCopy();
        stackColumn.translateToAbsolute(stackBounds);
        heapColumn.translateToAbsolute(heapBounds); // sibling of stackColumn: same coordinate space
        return new Rectangle(stackBounds.right(), stackBounds.y,
                Math.max(0, heapBounds.x - stackBounds.right()), stackBounds.height);
    }

    /**
     * Rightmost heap box edge (absolute) intersecting the [topY, bottomY] band —
     * the intra-heap arcs' bow baseline. Boxes align left but their right edges
     * are ragged, so an arc must clear every box it passes, not just its
     * endpoints'. The heap contents hold the heap body, whose children are the
     * static-class boxes (at the top) and the object boxes.
     */
    private int heapArcBaseline(int topY, int bottomY) {
        int right = Integer.MIN_VALUE;
        for (IFigure child : heapContents.getChildren()) {
            for (IFigure box : child.getChildren()) {
                Rectangle bounds = box.getBounds().getCopy();
                box.translateToAbsolute(bounds);
                if (bounds.bottom() >= topY && bounds.y <= bottomY) {
                    right = Math.max(right, bounds.right());
                }
            }
        }
        return right;
    }

    private ScrollPane paneAt(int x, int y) {
        IFigure contents = canvas.getContents();
        if (contents != null) {
            IFigure hit = contents.findFigureAt(x, y);
            for (IFigure figure = hit; figure != null; figure = figure.getParent()) {
                if (figure instanceof ScrollPane scrollPane) {
                    return scrollPane;
                }
            }
        }
        return x < gutterAbsolute().getCenter().x ? stackPane : heapPane;
    }

    private static Figure newVerticalContents(int spacing) {
        Figure contents = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setSpacing(spacing);
        layout.setStretchMinorAxis(false); // frames / heap boxes hug their content width
        contents.setLayoutManager(layout);
        contents.setBorder(new MarginBorder(8));
        return contents;
    }

    /** Translucent veil + centered "Running…" label; toggled by setRunning. */
    private final class RunningOverlay extends Layer {

        RunningOverlay() {
            setEnabled(false);
        }

        @Override
        protected void paintFigure(Graphics graphics) {
            Rectangle bounds = getBounds();
            graphics.setAlpha(150);
            graphics.setBackgroundColor(palette.columnBackground());
            graphics.fillRectangle(bounds);
            graphics.setAlpha(230);
            graphics.setFont(fonts.header());
            graphics.setForegroundColor(palette.textForeground());
            String text = "Running…";
            Dimension extent = TextUtilities.INSTANCE.getStringExtents(text, fonts.header());
            graphics.drawString(text, bounds.x + (bounds.width - extent.width) / 2,
                    bounds.y + (bounds.height - extent.height) / 2);
        }
    }
}
