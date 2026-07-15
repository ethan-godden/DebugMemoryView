package com.github.ethangodden.debugmemoryview.core;

import com.github.ethangodden.debugmemoryview.model.MemoryDiagram;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;

/** Implemented by the view; every method is invoked on the SWT UI thread. */
public interface ISnapshotConsumer {

    void snapshotReady(MemoryDiagram diagram, MemoryDiff diff);

    /** The rendered thread resumed: gray out the current diagram, keep it visible. */
    void threadResumed(String threadToken);

    /** No usable context (no session, non-Java debugger, terminated): show placeholder. */
    void cleared(String reason);
}
