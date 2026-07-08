package com.github.ethangodden.memorydiagram.model;

import java.util.ArrayList;
import java.util.List;

public record StackFrameModel(
        String frameKey,
        String declaringTypeName,
        String methodName,
        String methodSignature,      // raw JNI signature
        String label,                // "TypeSimpleName.methodName() line N", built at extraction
        int lineNumber,              // -1 if unknown
        int depthFromBottom,         // 0 = main/root frame
        boolean obsolete,            // invalidated by hot code replace; no variables
        boolean nativeFrame,
        boolean staticMethod,
        boolean localsAvailable,
        VariableModel thisVariable,  // null for static/native frames
        List<VariableModel> locals) {

    /**
     * Numbered from the stack BOTTOM so surviving frames keep their keys when frames
     * are pushed/popped above them; method identity distinguishes a return followed by
     * a call to a different method at the same depth (reads as DELETED + NEW).
     */
    public static String frameKey(int depthFromBottom, String declaringTypeName, String methodName, String signature) {
        return depthFromBottom + "|" + declaringTypeName + "." + methodName + signature;
    }

    /** {@code this} (when present) followed by locals in declaration order. */
    public List<VariableModel> allVariables() {
        if (thisVariable == null) {
            return locals;
        }
        List<VariableModel> all = new ArrayList<>(locals.size() + 1);
        all.add(thisVariable);
        all.addAll(locals);
        return all;
    }
}
