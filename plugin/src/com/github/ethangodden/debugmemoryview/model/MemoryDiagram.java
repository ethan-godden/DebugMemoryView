package com.github.ethangodden.debugmemoryview.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One immutable, platform-agnostic capture of a suspended program's memory — the replacement for the
 * former Java-shaped {@code MemorySnapshot}. It carries the STACK section ({@code frames}, top-of-stack
 * first) and the HEAP section ({@code heap}, in build order; statics classes are HEAP boxes), plus the
 * pipeline plumbing (target/thread tokens, name, sequence).
 *
 * <p>References are resolved through {@link #resolve}: a {@link Reference} addresses a HEAP cell by
 * slot index, which maps to the occupying {@link Box} or — for a <em>dangling</em> pointer — to nothing.
 * {@code heapBySlot} is the slot→box resolution table; {@code heap} is the same filled boxes in slot
 * order for iteration/rendering.
 */
public record MemoryDiagram(
        String debugTargetToken,
        String threadToken,
        String threadName,
        long sequence,
        List<Frame> frames,
        List<Box> heap,
        Map<Integer, Box> heapBySlot) {

    public MemoryDiagram {
        frames = List.copyOf(frames);
        heap = List.copyOf(heap);
        heapBySlot = Map.copyOf(heapBySlot);
    }

    /** The box occupying a reference's cell, or empty if the cell holds no box (a dangling pointer). */
    public Optional<Box> resolve(Reference ref) {
        if (ref == null || ref.section() != Section.HEAP) {
            return Optional.empty();
        }
        return Optional.ofNullable(heapBySlot.get(ref.index()));
    }

    /** Heap boxes indexed by their identity token, in slot (build) order. */
    public Map<String, Box> boxByToken() {
        Map<String, Box> byToken = new LinkedHashMap<>(heap.size() * 2);
        for (Box box : heap) {
            byToken.put(box.boxToken(), box);
        }
        return byToken;
    }
}
