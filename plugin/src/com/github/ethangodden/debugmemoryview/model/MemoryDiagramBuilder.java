package com.github.ethangodden.debugmemoryview.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sole ingestion point for constructing a {@link MemoryDiagram}. A debugger frontend (the Eclipse
 * JDT adapter today, another editor/language later) feeds it; the diff and render layers read only the
 * built diagram. JDK-only — no SWT/Draw2d/JFace/JDI types appear here or in its inputs — so the model
 * can be constructed and exercised in tests with no editor or debugger.
 *
 * <p>Construction protocol:
 * <ul>
 *   <li><b>Order-preserving</b> — frames render in push order (top-of-stack first); heap boxes keep
 *       their slot (first-mention) order; field/variable order is preserved.</li>
 *   <li><b>Stub-first</b> — {@link #reserveBox} declares a box's cell (as an unexplored stub) so a
 *       reference resolves even before the box is filled or if it is capped; {@link #fillBox} replaces
 *       the stub with the real box.</li>
 *   <li><b>Forward references</b> — {@link #reference} may be called before a box is reserved/filled;
 *       it returns the box's cell coordinate, resolved at {@link #build}. A reference to a token that
 *       is never reserved or filled resolves to a <em>dangling</em> pointer (not an error).</li>
 * </ul>
 */
public final class MemoryDiagramBuilder {

    private final String debugTargetToken;
    private final String threadToken;
    private final String threadName;
    private final long sequence;

    private final List<Frame> frames = new ArrayList<>();

    // HEAP slots assigned in first-mention order (reserve / fill / reference), so a box's slot index
    // is stable regardless of whether it is referenced before or after it is provided.
    private final Map<String, Integer> heapSlotByToken = new LinkedHashMap<>();
    private final Map<Integer, Box> heapBoxBySlot = new LinkedHashMap<>();
    private int nextHeapSlot;

    public MemoryDiagramBuilder(String debugTargetToken, String threadToken, String threadName, long sequence) {
        this.debugTargetToken = debugTargetToken;
        this.threadToken = threadToken;
        this.threadName = threadName;
        this.sequence = sequence;
    }

    // ---------- frames (STACK section, top-of-stack first) ----------

    /** Push a normal frame: a header plus ordered variable rows ({@code this} first, then locals). */
    public void pushFrame(String frameToken, String header, List<Variable> variables) {
        frames.add(Frame.withVariables(frameToken, header, variables));
    }

    /** Push a body-only frame (native/obsolete/unreadable): {@code body} shows instead of variables. */
    public void pushFrame(String frameToken, String header, String body) {
        frames.add(Frame.withBody(frameToken, header, body));
    }

    // ---------- heap boxes (HEAP section) ----------

    /**
     * Reserve a box's cell as an unexplored stub (header only), stub-first, so references resolve to
     * it even if it is never filled (over caps). {@link #fillBox} later replaces it.
     */
    public void reserveBox(String boxToken, String header) {
        int slot = slotOf(boxToken);
        heapBoxBySlot.putIfAbsent(slot, new Box(boxToken, header, List.of(), false, 0));
    }

    /** Fill (creating if needed) the box at {@code boxToken}'s cell with its explored contents. */
    public void fillBox(String boxToken, String header, List<Variable> fields, boolean explored, int omittedCount) {
        int slot = slotOf(boxToken);
        heapBoxBySlot.put(slot, new Box(boxToken, header, fields, explored, omittedCount));
    }

    /** Convenience: reserve and fill a fully-known box in one call. */
    public void addBox(String boxToken, String header, List<Variable> fields, boolean explored, int omittedCount) {
        fillBox(boxToken, header, fields, explored, omittedCount);
    }

    // ---------- references ----------

    /**
     * The cell coordinate of a box, for placing in a {@link Variable}'s value. The box may be filled
     * later (forward reference) or never (dangling). Object references always point into the HEAP.
     */
    public Reference reference(String boxToken) {
        return new Reference(Section.HEAP, slotOf(boxToken));
    }

    private int slotOf(String boxToken) {
        Integer slot = heapSlotByToken.get(boxToken);
        if (slot == null) {
            slot = nextHeapSlot++;
            heapSlotByToken.put(boxToken, slot);
        }
        return slot;
    }

    // ---------- build ----------

    /** Finalize: freeze ordering and the slot→box resolution table, and return the immutable diagram. */
    public MemoryDiagram build() {
        List<Box> heap = new ArrayList<>(heapBoxBySlot.size());
        Map<Integer, Box> bySlot = new LinkedHashMap<>();
        heapBoxBySlot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    heap.add(e.getValue());
                    bySlot.put(e.getKey(), e.getValue());
                });
        return new MemoryDiagram(debugTargetToken, threadToken, threadName, sequence,
                List.copyOf(frames), List.copyOf(heap), Map.copyOf(bySlot));
    }
}
