package com.github.ethangodden.memorydiagram.core;

import com.github.ethangodden.memorydiagram.model.MemorySnapshot;
import com.github.ethangodden.memorydiagram.model.diff.MemoryDiff;

/** Implemented by the view; every method is invoked on the SWT UI thread. */
public interface ISnapshotConsumer {

    void snapshotReady(MemorySnapshot snapshot, MemoryDiff diff);

    /** The rendered thread resumed: gray out the current diagram, keep it visible. */
    void threadResumed(String threadKey);

    /** No usable context (no session, non-Java debugger, terminated): show placeholder. */
    void cleared(String reason);
}
