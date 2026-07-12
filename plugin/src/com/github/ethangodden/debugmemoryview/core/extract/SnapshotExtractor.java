package com.github.ethangodden.debugmemoryview.core.extract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaPrimitiveValue;
import org.eclipse.jdt.debug.core.IJavaReferenceType;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;

import com.github.ethangodden.debugmemoryview.core.ExtractionLimits;
import com.github.ethangodden.debugmemoryview.model.ExtractionStats;
import com.github.ethangodden.debugmemoryview.model.FieldModel;
import com.github.ethangodden.debugmemoryview.model.HeapObjectModel;
import com.github.ethangodden.debugmemoryview.model.HeapReference;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.NullValue;
import com.github.ethangodden.debugmemoryview.model.PrimitiveValue;
import com.github.ethangodden.debugmemoryview.model.StackFrameModel;
import com.github.ethangodden.debugmemoryview.model.StaticsClassModel;
import com.github.ethangodden.debugmemoryview.model.UnreadableValue;
import com.github.ethangodden.debugmemoryview.model.ValueModel;
import com.github.ethangodden.debugmemoryview.model.VariableModel;

/**
 * Walks a suspended thread and produces one immutable {@link MemorySnapshot}.
 * This is the ONLY class in the plugin that makes JDI wire calls; it must run on
 * a Jobs-framework worker thread, never on the UI or debug event dispatch thread.
 * One instance per extraction run.
 *
 * Failure handling is layered: a per-value read failure degrades to
 * {@link UnreadableValue}; a per-object failure leaves the already-inserted stub
 * node in place (arrows stay valid); a per-frame failure yields an obsolete-style
 * placeholder frame; an unusable thread/target aborts the whole snapshot with
 * {@link OperationCanceledException}.
 */
public final class SnapshotExtractor {

    private static final String STRING_SIGNATURE = "Ljava/lang/String;"; //$NON-NLS-1$

    // A String whose backing array is larger than this is NOT pulled over JDWP in
    // full: getValueString() would transfer and allocate the entire contents on the
    // debugger before truncation to maxStringLength, so a pathological debuggee String
    // could stall or OOM the debugger despite the cap. Bounds transient/wire cost the
    // way maxStringLength bounds the stored text; over-ceiling strings show a marker.
    private static final int MAX_STRING_TRANSFER_CHARS = 1 << 20; // ~1M

    private static final Map<String, String> BOXED_BY_SIGNATURE = Map.of(
            "Ljava/lang/Boolean;", "java.lang.Boolean", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Byte;", "java.lang.Byte", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Character;", "java.lang.Character", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Short;", "java.lang.Short", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Integer;", "java.lang.Integer", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Long;", "java.lang.Long", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Float;", "java.lang.Float", //$NON-NLS-1$ //$NON-NLS-2$
            "Ljava/lang/Double;", "java.lang.Double"); //$NON-NLS-1$ //$NON-NLS-2$

    @FunctionalInterface
    private interface DebugRead<T> {
        T read() throws DebugException;
    }

    private record Pending(IJavaObject object, long id, int depth, String typeName) {
    }

    private final ExtractionLimits limits;
    private final IProgressMonitor monitor;
    private final long sequence;

    private IJavaThread thread;
    private IDebugTarget target;

    // BFS state: stub-first insertion keeps every HeapReference resolvable and
    // makes aliasing/cycles structural. LinkedHashMap iteration order = BFS order.
    private final LinkedHashMap<Long, HeapObjectModel> heap = new LinkedHashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final LinkedHashMap<String, IJavaReferenceType> staticsTypes = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private int admitted;       // enqueued for exploration; counts toward maxObjects
    private int explored;       // successfully built nodes
    private int objectsOmitted; // enqueues refused by the caps
    private boolean heapTruncated;
    private boolean partial;

    public SnapshotExtractor(ExtractionLimits limits, IProgressMonitor monitor, long sequence) {
        this.limits = limits;
        this.monitor = monitor;
        this.sequence = sequence;
    }

    public MemorySnapshot extract(IJavaThread javaThread, IJavaStackFrame selectedFrameOrNull,
            String debugTargetKey, String threadKey) throws DebugException {
        this.thread = javaThread;
        this.target = javaThread.getDebugTarget();
        abortIfUnusable();
        checkCanceled();

        String threadName;
        try {
            threadName = javaThread.getName();
        } catch (DebugException e) {
            threadName = "?"; //$NON-NLS-1$
        }

        IStackFrame[] raw = javaThread.getStackFrames(); // one wire call, top frame first
        int taken = Math.min(raw.length, limits.maxFrames());
        int framesOmitted = raw.length - taken;
        List<StackFrameModel> frames = new ArrayList<>(taken);
        String selectedFrameKey = null;
        for (int i = 0; i < taken; i++) {
            checkCanceled();
            IJavaStackFrame frame = (IJavaStackFrame) raw[i];
            StackFrameModel model = extractFrame(frame, raw.length - 1 - i);
            frames.add(model);
            if (selectedFrameOrNull != null && frame.equals(selectedFrameOrNull)) {
                selectedFrameKey = model.frameKey();
            }
        }

        // Statics are BFS roots too (depth 0); seed them before draining the queue.
        List<StaticsClassModel> statics = extractStatics();
        drainHeapQueue();

        ExtractionStats stats = new ExtractionStats(explored, objectsOmitted, heapTruncated, partial,
                List.copyOf(errors));
        return new MemorySnapshot(debugTargetKey, threadKey, threadName, sequence,
                System.currentTimeMillis(), List.copyOf(frames), framesOmitted,
                Collections.unmodifiableMap(heap), statics, selectedFrameKey, stats);
    }

    // ---- stack -----------------------------------------------------------

    private StackFrameModel extractFrame(IJavaStackFrame frame, int depthFromBottom) {
        String context = "frame " + depthFromBottom; //$NON-NLS-1$
        boolean obsolete = readOr(frame::isObsolete, Boolean.TRUE, context).booleanValue();
        String typeName = readOr(frame::getDeclaringTypeName, "<unknown>", context); //$NON-NLS-1$
        String methodName = readOr(frame::getMethodName, "<unknown>", context); //$NON-NLS-1$
        String signature = readOr(frame::getSignature, "", context); //$NON-NLS-1$
        int lineNumber = readOr(frame::getLineNumber, Integer.valueOf(-1), context).intValue();
        boolean nativeFrame = readOr(frame::isNative, Boolean.FALSE, context).booleanValue();
        boolean staticMethod = readOr(frame::isStatic, Boolean.FALSE, context).booleanValue();
        String frameKey = StackFrameModel.frameKey(depthFromBottom, typeName, methodName, signature);
        String label = buildLabel(typeName, methodName, lineNumber);

        if (!obsolete) {
            registerStaticsType(frame);
            try {
                return completeFrame(frame, frameKey, typeName, methodName, signature, label,
                        lineNumber, depthFromBottom, nativeFrame, staticMethod);
            } catch (DebugException e) {
                // Per-frame degradation (incl. ERR_INVALID_STACK_FRAME): placeholder, snapshot survives.
                recordError(context + " (" + label + "): " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new StackFrameModel(frameKey, typeName, methodName, label, lineNumber,
                depthFromBottom, true, nativeFrame, staticMethod, false, null, List.of());
    }

    private StackFrameModel completeFrame(IJavaStackFrame frame, String frameKey, String typeName,
            String methodName, String signature, String label, int lineNumber, int depthFromBottom,
            boolean nativeFrame, boolean staticMethod) throws DebugException {

        VariableModel thisVariable = null;
        try {
            IJavaObject self = frame.getThis(); // null for static/native frames
            if (self != null) {
                thisVariable = new VariableModel("this", typeName, convert(self, 0)); //$NON-NLS-1$
            }
        } catch (DebugException e) {
            String message = shortMessage(e);
            recordError("this of " + label + ": " + message); //$NON-NLS-1$ //$NON-NLS-2$
            if (!staticMethod && !nativeFrame) {
                thisVariable = new VariableModel("this", typeName, new UnreadableValue(message)); //$NON-NLS-1$
            }
        }

        IJavaVariable[] rawLocals = frame.getLocalVariables(); // slot/declaration order
        boolean localsAvailable = frame.wereLocalsAvailable(); // valid only after the retrieval above
        List<VariableModel> locals = new ArrayList<>(rawLocals.length);
        for (IJavaVariable variable : rawLocals) {
            String name = readOr(variable::getName, null, "local of " + label); //$NON-NLS-1$
            if (name == null) {
                continue;
            }
            locals.add(new VariableModel(name, declaredTypeName(variable), valueOf(variable, 0)));
        }
        return new StackFrameModel(frameKey, typeName, methodName, label, lineNumber,
                depthFromBottom, false, nativeFrame, staticMethod, localsAvailable, thisVariable,
                List.copyOf(locals));
    }

    private static String buildLabel(String typeName, String methodName, int lineNumber) {
        StringBuilder label = new StringBuilder();
        label.append(simpleName(typeName)).append('.').append(methodName).append("()"); //$NON-NLS-1$
        if (lineNumber >= 0) {
            label.append(" line ").append(lineNumber); //$NON-NLS-1$
        }
        return label.toString();
    }

    // ---- values ----------------------------------------------------------

    /** Converts a value, registering any referenced object in the heap (stub first). */
    private ValueModel convert(IJavaValue value, int depth) {
        try {
            if (value == null || value.isNull()) {
                return NullValue.INSTANCE;
            }
            if (value instanceof IJavaPrimitiveValue) {
                return new PrimitiveValue(value.getReferenceTypeName(), value.getValueString());
            }
            if (value instanceof IJavaArray array) {
                return reference(array, depth);
            }
            if (value instanceof IJavaObject object) {
                String signature = object.getSignature();
                if (STRING_SIGNATURE.equals(signature) && limits.inlineStrings()) {
                    // Escape hatch: no heap node, quoted text inline.
                    return new PrimitiveValue("java.lang.String", quotedString(object)); //$NON-NLS-1$
                }
                String wrapper = BOXED_BY_SIGNATURE.get(signature);
                if (wrapper != null && limits.inlineBoxed()) {
                    return new PrimitiveValue(wrapper, boxedText(object));
                }
                return reference(object, depth);
            }
            return new PrimitiveValue("?", value.getValueString()); // JDI void and friends //$NON-NLS-1$
        } catch (DebugException e) {
            String message = shortMessage(e);
            recordError("value: " + message); //$NON-NLS-1$
            return new UnreadableValue(message);
        }
    }

    private ValueModel reference(IJavaObject object, int depth) throws DebugException {
        long id = object.getUniqueId();
        if (id == -1) {
            // Collected between value fetch and id fetch: no node.
            recordError("value: object collected"); //$NON-NLS-1$
            return new UnreadableValue("<collected>"); //$NON-NLS-1$
        }
        HeapObjectModel existing = heap.get(id);
        String typeName = existing != null ? existing.typeName() : typeNameOf(object);
        if (existing == null) {
            // STUB first: every HeapReference has a target node, aliasing stays
            // correct past the caps, and cycles terminate trivially.
            heap.put(id, HeapObjectModel.stub(id, typeName, simpleName(typeName)));
            if (depth < limits.maxDepth() && admitted < limits.maxObjects()) {
                admitted++;
                queue.add(new Pending(object, id, depth, typeName));
            } else {
                heapTruncated = true;
                objectsOmitted++;
            }
        }
        return new HeapReference(id, typeName);
    }

    // ---- heap ------------------------------------------------------------

    private void drainHeapQueue() {
        Pending pending;
        while ((pending = queue.poll()) != null) {
            checkCanceled();
            HeapObjectModel node = buildNode(pending);
            if (node != null) {
                heap.put(pending.id(), node); // replaces the stub in place, keeps BFS order
                explored++;
            }
        }
    }

    private HeapObjectModel buildNode(Pending pending) {
        String simple = simpleName(pending.typeName());
        try {
            if (pending.object() instanceof IJavaArray array) {
                return buildArray(array, pending, simple);
            }
            String signature = pending.object().getSignature();
            if (STRING_SIGNATURE.equals(signature)) {
                return buildString(pending.object(), pending.id());
            }
            String wrapper = BOXED_BY_SIGNATURE.get(signature);
            if (wrapper != null) {
                return buildBoxed(pending.object(), pending.id(), wrapper);
            }
            return buildPlainOrEnum(pending, simple);
        } catch (DebugException e) {
            // Per-object degradation: the stub stays, so inbound arrows remain valid.
            recordError("object " + simple + "#" + pending.id() + ": " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
    }

    private HeapObjectModel buildArray(IJavaArray array, Pending pending, String simple)
            throws DebugException {
        int length = array.getLength();
        int shown = Math.min(length, limits.maxArrayElements());
        List<ValueModel> elements = new ArrayList<>(shown);
        if (length <= limits.maxArrayElements()) {
            IJavaValue[] values = array.getValues(); // one JDWP round trip for the whole array
            for (IJavaValue value : values) {
                elements.add(convert(value, pending.depth() + 1));
            }
        } else {
            // Bounded per-index reads; never materialize a huge array wholesale.
            for (int i = 0; i < shown; i++) {
                checkCanceled();
                ValueModel element;
                try {
                    element = convert(array.getValue(i), pending.depth() + 1);
                } catch (DebugException e) {
                    String message = shortMessage(e);
                    recordError("element " + i + " of " + simple + ": " + message); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    element = new UnreadableValue(message);
                }
                elements.add(element);
            }
        }
        return HeapObjectModel.array(pending.id(), pending.typeName(), simple, length,
                List.copyOf(elements), length - shown);
    }

    private HeapObjectModel buildString(IJavaObject object, long id) throws DebugException {
        if (exceedsTransferCeiling(object)) {
            return HeapObjectModel.string(id, "", true); // too large to pull over the wire //$NON-NLS-1$
        }
        String text = object.getValueString(); // raw contents of the StringReference
        if (text == null) {
            text = ""; //$NON-NLS-1$
        }
        boolean truncated = text.length() > limits.maxStringLength();
        if (truncated) {
            text = text.substring(0, limits.maxStringLength());
        }
        return HeapObjectModel.string(id, text, truncated);
    }

    private HeapObjectModel buildBoxed(IJavaObject object, long id, String wrapperTypeName)
            throws DebugException {
        IJavaValue inner = boxedInner(object);
        String text = inner == null ? "?" : inner.getValueString(); //$NON-NLS-1$
        return HeapObjectModel.boxed(id, wrapperTypeName, simpleName(wrapperTypeName), text,
                isJvmCached(wrapperTypeName, inner));
    }

    /** Spec-defined valueOf caches: Integer/Short/Byte/Long in [-128,127], Character in [0,127], Boolean always. */
    private static boolean isJvmCached(String wrapperTypeName, IJavaValue inner) {
        if ("java.lang.Boolean".equals(wrapperTypeName)) { //$NON-NLS-1$
            return true;
        }
        if (!(inner instanceof IJavaPrimitiveValue primitive)) {
            return false;
        }
        return switch (wrapperTypeName) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte" -> { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                long value = primitive.getLongValue();
                yield value >= -128 && value <= 127;
            }
            case "java.lang.Character" -> primitive.getCharValue() <= 127; //$NON-NLS-1$
            default -> false;
        };
    }

    private HeapObjectModel buildPlainOrEnum(Pending pending, String simple) throws DebugException {
        IJavaObject object = pending.object();
        boolean isEnum = false;
        try {
            isEnum = object.getJavaType() instanceof IJavaClassType classType && classType.isEnum();
        } catch (DebugException e) {
            recordError("type of " + simple + ": " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IVariable[] variables = object.getVariables(); // allFields(): instance+static+inherited, JDT-sorted
        List<FieldModel> fields = new ArrayList<>();
        int omitted = 0;
        String enumConstantName = null;
        for (IVariable raw : variables) {
            if (!(raw instanceof IJavaVariable variable)) {
                continue;
            }
            boolean isStatic;
            boolean isSynthetic;
            try {
                isStatic = variable.isStatic();
                isSynthetic = variable.isSynthetic();
            } catch (DebugException e) {
                recordError("field of " + simple + ": " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (isStatic) {
                continue; // statics render in the STATIC DATA area, not the object box
            }
            if (isSynthetic && !limits.includeSyntheticFields()) {
                continue;
            }
            String name = readOr(variable::getName, null, "field of " + simple); //$NON-NLS-1$
            if (name == null) {
                continue;
            }
            if (isEnum && enumConstantName == null && "name".equals(name) && isEnumBaseField(variable)) { //$NON-NLS-1$
                // Inherited private java.lang.Enum.name — only visible via allFields().
                enumConstantName = readOr(() -> variable.getValue().getValueString(), null,
                        "enum name of " + simple); //$NON-NLS-1$
            }
            if (fields.size() >= limits.maxFieldsPerObject()) {
                omitted++;
                continue;
            }
            fields.add(toFieldModel(variable, name, pending.depth() + 1));
        }
        if (isEnum) {
            return HeapObjectModel.enumConstant(pending.id(), pending.typeName(), simple,
                    List.copyOf(fields), omitted, enumConstantName);
        }
        return HeapObjectModel.plain(pending.id(), pending.typeName(), simple, List.copyOf(fields),
                omitted);
    }

    private static boolean isEnumBaseField(IJavaVariable variable) {
        if (variable instanceof IJavaFieldVariable field) {
            try {
                IJavaType declaring = field.getDeclaringType();
                return declaring != null && "java.lang.Enum".equals(declaring.getName()); //$NON-NLS-1$
            } catch (DebugException e) {
                return false;
            }
        }
        return false;
    }

    private FieldModel toFieldModel(IJavaVariable variable, String name, int depth) {
        String declaringTypeName = "?"; //$NON-NLS-1$
        if (variable instanceof IJavaFieldVariable field) {
            IJavaType declaring = field.getDeclaringType(); // local getter, no wire call
            if (declaring != null) {
                declaringTypeName = readOr(declaring::getName, "?", "declaring type of " + name); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new FieldModel(name, declaringTypeName, declaredTypeName(variable),
                valueOf(variable, depth));
    }

    // ---- statics ---------------------------------------------------------

    private void registerStaticsType(IJavaStackFrame frame) {
        try {
            IJavaReferenceType type = frame.getReferenceType();
            if (type != null) {
                staticsTypes.putIfAbsent(type.getName(), type); // first appearance = top frame first
            }
        } catch (DebugException e) {
            recordError("declaring type: " + shortMessage(e)); //$NON-NLS-1$
        }
    }

    private List<StaticsClassModel> extractStatics() {
        List<StaticsClassModel> statics = new ArrayList<>(staticsTypes.size());
        for (Map.Entry<String, IJavaReferenceType> entry : staticsTypes.entrySet()) {
            checkCanceled();
            String className = entry.getKey();
            IJavaReferenceType type = entry.getValue();
            List<FieldModel> fields = new ArrayList<>();
            int omitted = 0;
            try {
                // Declared fields only: inherited statics show under their own class
                // when that class is also on the stack.
                for (String name : type.getDeclaredFieldNames()) {
                    IJavaFieldVariable field;
                    try {
                        field = type.getField(name);
                    } catch (DebugException e) {
                        recordError("static " + className + "." + name + ": " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        continue;
                    }
                    if (field == null) {
                        continue;
                    }
                    boolean isStatic;
                    boolean isSynthetic;
                    try {
                        isStatic = field.isStatic();
                        isSynthetic = field.isSynthetic();
                    } catch (DebugException e) {
                        recordError("static " + className + "." + name + ": " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        continue;
                    }
                    if (!isStatic || (isSynthetic && !limits.includeSyntheticFields())) {
                        continue;
                    }
                    if (fields.size() >= limits.maxStaticFieldsPerClass()) {
                        omitted++;
                        continue;
                    }
                    // Statics are BFS roots (depth 0).
                    fields.add(new FieldModel(name, className, declaredTypeName(field),
                            valueOf(field, 0)));
                }
            } catch (DebugException e) {
                recordError("statics of " + className + ": " + shortMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!fields.isEmpty()) {
                statics.add(new StaticsClassModel(className, simpleName(className),
                        List.copyOf(fields), omitted));
            }
        }
        return List.copyOf(statics);
    }

    // ---- helpers ---------------------------------------------------------

    private ValueModel valueOf(IJavaVariable variable, int depth) {
        try {
            return convert((IJavaValue) variable.getValue(), depth);
        } catch (DebugException e) {
            String message = shortMessage(e);
            recordError("value of " + safeName(variable) + ": " + message); //$NON-NLS-1$ //$NON-NLS-2$
            return new UnreadableValue(message);
        }
    }

    private String declaredTypeName(IJavaVariable variable) {
        try {
            return variable.getReferenceTypeName();
        } catch (DebugException e) {
            try {
                return typeNameFromSignature(variable.getSignature());
            } catch (DebugException e2) {
                return "?"; //$NON-NLS-1$
            }
        }
    }

    private String typeNameOf(IJavaValue value) {
        try {
            IJavaType type = value.getJavaType();
            if (type != null) {
                return type.getName();
            }
        } catch (DebugException e) {
            // fall through to the reference type name
        }
        try {
            return value.getReferenceTypeName();
        } catch (DebugException e) {
            return "?"; //$NON-NLS-1$
        }
    }

    private String quotedString(IJavaObject object) throws DebugException {
        if (exceedsTransferCeiling(object)) {
            return "\"...\""; // too large to pull over the wire //$NON-NLS-1$
        }
        String text = object.getValueString();
        if (text == null) {
            text = ""; //$NON-NLS-1$
        }
        if (text.length() > limits.maxStringLength()) {
            return "\"" + text.substring(0, limits.maxStringLength()) + "...\""; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "\"" + text + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * True when the String's backing array is so large that pulling its full contents
     * over JDWP (getValueString) would risk an allocation/transfer spike on the debugger.
     * Reads only the backing-array length, never its contents. Best-effort: any probe
     * failure returns false so the normal full read still runs.
     */
    private static boolean exceedsTransferCeiling(IJavaObject stringObject) {
        try {
            IJavaFieldVariable valueField = stringObject.getField("value", false); //$NON-NLS-1$
            if (valueField != null && valueField.getValue() instanceof IJavaArray backing) {
                return backing.getLength() > MAX_STRING_TRANSFER_CHARS;
            }
        } catch (DebugException e) {
            // best-effort probe: fall back to the normal full read
        }
        return false;
    }

    private static String boxedText(IJavaObject object) throws DebugException {
        IJavaValue inner = boxedInner(object);
        return inner == null ? "?" : inner.getValueString(); //$NON-NLS-1$
    }

    /** The unwrapped {@code value} field of a boxed primitive (null if the field is absent). */
    private static IJavaValue boxedInner(IJavaObject object) throws DebugException {
        IJavaFieldVariable valueField = object.getField("value", false); //$NON-NLS-1$
        return valueField == null ? null : (IJavaValue) valueField.getValue();
    }

    static String typeNameFromSignature(String signature) {
        if (signature == null || signature.isEmpty()) {
            return "?"; //$NON-NLS-1$
        }
        int dims = 0;
        while (dims < signature.length() && signature.charAt(dims) == '[') {
            dims++;
        }
        if (dims >= signature.length()) {
            return "?"; //$NON-NLS-1$
        }
        String base = switch (signature.charAt(dims)) {
            case 'B' -> "byte"; //$NON-NLS-1$
            case 'C' -> "char"; //$NON-NLS-1$
            case 'D' -> "double"; //$NON-NLS-1$
            case 'F' -> "float"; //$NON-NLS-1$
            case 'I' -> "int"; //$NON-NLS-1$
            case 'J' -> "long"; //$NON-NLS-1$
            case 'S' -> "short"; //$NON-NLS-1$
            case 'Z' -> "boolean"; //$NON-NLS-1$
            case 'V' -> "void"; //$NON-NLS-1$
            case 'L' -> signature.endsWith(";") //$NON-NLS-1$
                    ? signature.substring(dims + 1, signature.length() - 1).replace('/', '.')
                    : "?"; //$NON-NLS-1$
            default -> "?"; //$NON-NLS-1$
        };
        return base + "[]".repeat(dims); //$NON-NLS-1$
    }

    static String simpleName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "?"; //$NON-NLS-1$
        }
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }

    private static String safeName(IJavaVariable variable) {
        try {
            return variable.getName();
        } catch (DebugException e) {
            return "?"; //$NON-NLS-1$
        }
    }

    private static String shortMessage(DebugException e) {
        String message = e.getStatus() != null ? e.getStatus().getMessage() : null;
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        message = message.trim();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private <T> T readOr(DebugRead<T> read, T fallback, String context) {
        try {
            return read.read();
        } catch (DebugException e) {
            recordError(context + ": " + shortMessage(e)); //$NON-NLS-1$
            return fallback;
        }
    }

    private void recordError(String message) {
        partial = true;
        if (errors.size() < limits.maxErrors()) {
            errors.add(message);
        }
        // A doomed walk (thread resumed, VM gone) fails on every read; abort fast
        // instead of grinding through the caps. These probes are local state checks.
        abortIfUnusable();
    }

    private void abortIfUnusable() {
        if (target == null || target.isTerminated() || target.isDisconnected()
                || !thread.isSuspended()) {
            throw new OperationCanceledException();
        }
    }

    private void checkCanceled() {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }
}
