package com.github.ethangodden.debugmemoryview.render;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sticky heap-diagram order keys that survive full figure rebuilds so boxes
 * never jump between debugger steps. Cleared when a new debug session starts.
 *
 * PURE: imports from the JDK only (headless-testable); no Draw2d/SWT.
 */
public final class LayoutMemory {

    private final Map<Long, Long> orderKeys = new HashMap<>();
    private long nextOrderKey;

    /**
     * An object already remembered keeps its orderKey verbatim (even if its BFS
     * discovery position changed); a new object appends below every existing box.
     */
    public long assign(long id) {
        return orderKeys.computeIfAbsent(Long.valueOf(id), key -> Long.valueOf(nextOrderKey++)).longValue();
    }

    /** The remembered orderKey, or null for an id never assigned (or evicted). */
    public Long orderKeyOf(long id) {
        return orderKeys.get(Long.valueOf(id));
    }

    /** Evicts orderKeys of ids absent from the latest snapshot and its ghosts. */
    public void retainAll(Set<Long> liveIds) {
        orderKeys.keySet().retainAll(liveIds);
    }

    public void clear() {
        orderKeys.clear();
        nextOrderKey = 0;
    }
}
