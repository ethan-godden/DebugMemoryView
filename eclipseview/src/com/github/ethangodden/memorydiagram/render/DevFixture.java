package com.github.ethangodden.memorydiagram.render;

import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.ethangodden.memorydiagram.model.ExtractionStats;
import com.github.ethangodden.memorydiagram.model.FieldModel;
import com.github.ethangodden.memorydiagram.model.HeapObjectModel;
import com.github.ethangodden.memorydiagram.model.HeapReference;
import com.github.ethangodden.memorydiagram.model.MemorySnapshot;
import com.github.ethangodden.memorydiagram.model.NullValue;
import com.github.ethangodden.memorydiagram.model.PrimitiveValue;
import com.github.ethangodden.memorydiagram.model.StackFrameModel;
import com.github.ethangodden.memorydiagram.model.StaticsClassModel;
import com.github.ethangodden.memorydiagram.model.UnreadableValue;
import com.github.ethangodden.memorydiagram.model.VariableModel;
import com.github.ethangodden.memorydiagram.model.diff.ChangeStatus;
import com.github.ethangodden.memorydiagram.model.diff.MemoryDiff;

/**
 * DEV-ONLY: a representative hard-coded snapshot + diff pair for exercising the
 * renderer without a debugger (call controller.setSnapshot(DevFixture.snapshot(),
 * DevFixture.diff()) from a scratch action). Covers aliasing (two rows point at
 * Person #1), an array, a STRING box, a BOXED int with jvmCached, an enum, a
 * stub, a self-reference, statics, and every ChangeStatus including ghosts
 * (deleted frame / variable / object / static class / static field).
 */
public final class DevFixture {

    private static final long PERSON = 1;
    private static final long NAME = 2;
    private static final long ACCOUNT = 3;
    private static final long HISTORY = 4;
    private static final long LIMIT = 5;
    private static final long NOTE = 6;
    private static final long STATUS = 7;
    private static final long REGISTRY = 8;
    private static final long DEAD_ORDER = 9;

    private DevFixture() {
    }

    public static MemorySnapshot snapshot() {
        Map<Long, HeapObjectModel> heap = new LinkedHashMap<>();
        heap.put(ACCOUNT, HeapObjectModel.plain(ACCOUNT, "bank.Account", "Account", List.of(
                new FieldModel("owner", "bank.Account", "demo.Person", ref(PERSON, "demo.Person")),
                new FieldModel("balance", "bank.Account", "double", prim("double", "250.0")),
                new FieldModel("history", "bank.Account", "int[]", ref(HISTORY, "int[]")),
                new FieldModel("cachedLimit", "bank.Account", "java.lang.Integer",
                        ref(LIMIT, "java.lang.Integer"))),
                0));
        heap.put(PERSON, HeapObjectModel.plain(PERSON, "demo.Person", "Person", List.of(
                new FieldModel("name", "demo.Person", "java.lang.String", ref(NAME, "java.lang.String")),
                new FieldModel("age", "demo.Person", "int", prim("int", "31")),
                new FieldModel("account", "demo.Person", "bank.Account", ref(ACCOUNT, "bank.Account")),
                new FieldModel("self", "demo.Person", "demo.Person", ref(PERSON, "demo.Person")),
                new FieldModel("secret", "demo.Person", "java.lang.Object",
                        new UnreadableValue("JDWP error 35"))),
                1));
        heap.put(NOTE, HeapObjectModel.string(NOTE,
                "pending transfer to savings, awaiting confirmation from the branch office", true));
        heap.put(STATUS, HeapObjectModel.enumConstant(STATUS, "demo.Status", "Status", List.of(), 0, "ACTIVE"));
        heap.put(LIMIT, HeapObjectModel.boxed(LIMIT, "java.lang.Integer", "Integer", "42", true));
        heap.put(REGISTRY, HeapObjectModel.stub(REGISTRY, "java.util.HashMap", "HashMap"));
        heap.put(HISTORY, HeapObjectModel.array(HISTORY, "int[]", "int[]", 8,
                List.of(prim("int", "10"), prim("int", "20"), prim("int", "99"),
                        prim("int", "40"), prim("int", "50")),
                3));
        heap.put(NAME, HeapObjectModel.string(NAME, "Alice", false));

        String withdrawKey = StackFrameModel.frameKey(2, "bank.Account", "withdraw", "(I)V");
        StackFrameModel withdraw = new StackFrameModel(withdrawKey, "bank.Account", "withdraw", "(I)V",
                "Account.withdraw(int) line 42", 42, 2, false, false, false, true,
                thisVar("bank.Account", ACCOUNT),
                List.of(local("amount", "int", prim("int", "40")),
                        local("owner", "demo.Person", ref(PERSON, "demo.Person")),
                        local("note", "java.lang.String", ref(NOTE, "java.lang.String")),
                        local("status", "demo.Status", ref(STATUS, "demo.Status")),
                        local("cache", "java.lang.Integer", ref(LIMIT, "java.lang.Integer")),
                        local("map", "java.util.Map", ref(REGISTRY, "java.util.HashMap")),
                        local("receipt", "java.lang.Object", NullValue.INSTANCE)));

        String payKey = StackFrameModel.frameKey(1, "demo.Person", "pay", "(Lbank/Account;)V");
        StackFrameModel pay = new StackFrameModel(payKey, "demo.Person", "pay", "(Lbank/Account;)V",
                "Person.pay(Account) line 17", 17, 1, false, false, false, true,
                thisVar("demo.Person", PERSON),
                List.of(local("acct", "bank.Account", ref(ACCOUNT, "bank.Account"))));

        String mainKey = StackFrameModel.frameKey(0, "demo.Main", "main", "([Ljava/lang/String;)V");
        StackFrameModel main = new StackFrameModel(mainKey, "demo.Main", "main", "([Ljava/lang/String;)V",
                "Main.main(String[]) line 9", 9, 0, false, false, true, true, null,
                List.of(local("p", "demo.Person", ref(PERSON, "demo.Person")),
                        local("i", "int", prim("int", "3"))));

        List<StaticsClassModel> statics = List.of(
                new StaticsClassModel("bank.Bank", "Bank", List.of(
                        new FieldModel("TOTAL", "bank.Bank", "long", prim("long", "1250")),
                        new FieldModel("REGISTRY", "bank.Bank", "java.util.Map",
                                ref(REGISTRY, "java.util.HashMap"))),
                        1));

        return new MemorySnapshot("target-1", "thread-1", "main", 8, System.currentTimeMillis(),
                List.of(withdraw, pay, main), 0, Collections.unmodifiableMap(heap), statics,
                withdrawKey, ExtractionStats.empty());
    }

    public static MemoryDiff diff() {
        String withdrawKey = StackFrameModel.frameKey(2, "bank.Account", "withdraw", "(I)V");
        String payKey = StackFrameModel.frameKey(1, "demo.Person", "pay", "(Lbank/Account;)V");
        String mainKey = StackFrameModel.frameKey(0, "demo.Main", "main", "([Ljava/lang/String;)V");
        String logKey = StackFrameModel.frameKey(3, "demo.Logger", "log", "(Ljava/lang/String;)V");

        Map<String, ChangeStatus> frameStatus = Map.of(
                withdrawKey, ChangeStatus.NEW,
                payKey, ChangeStatus.CHANGED,
                mainKey, ChangeStatus.UNCHANGED,
                logKey, ChangeStatus.DELETED);

        Map<String, ChangeStatus> variableStatus = new LinkedHashMap<>();
        for (String name : List.of("this", "amount", "owner", "note", "status", "cache", "map", "receipt")) {
            variableStatus.put(withdrawKey + "#" + name, ChangeStatus.NEW);
        }
        variableStatus.put(payKey + "#this", ChangeStatus.UNCHANGED);
        variableStatus.put(payKey + "#acct", ChangeStatus.CHANGED);
        variableStatus.put(mainKey + "#p", ChangeStatus.UNCHANGED);
        variableStatus.put(mainKey + "#i", ChangeStatus.CHANGED);

        Map<Long, ChangeStatus> objectStatus = Map.of(
                PERSON, ChangeStatus.CHANGED,
                ACCOUNT, ChangeStatus.CHANGED,
                HISTORY, ChangeStatus.CHANGED,
                LIMIT, ChangeStatus.NEW,
                NOTE, ChangeStatus.NEW,
                DEAD_ORDER, ChangeStatus.DELETED);

        Map<Long, Map<String, ChangeStatus>> fieldStatus = Map.of(
                PERSON, Map.of("demo.Person.age", ChangeStatus.CHANGED),
                ACCOUNT, Map.of("bank.Account.balance", ChangeStatus.CHANGED,
                        "bank.Account.cachedLimit", ChangeStatus.NEW));

        BitSet historyChanged = new BitSet();
        historyChanged.set(2);
        Map<Long, BitSet> changedElements = Map.of(HISTORY, historyChanged);

        Map<String, ChangeStatus> staticStatus = Map.of(
                "bank.Bank.TOTAL", ChangeStatus.CHANGED,
                "bank.Bank.REGISTRY", ChangeStatus.UNCHANGED,
                "bank.Bank.OLD_LIMIT", ChangeStatus.DELETED);

        StackFrameModel deletedLogFrame = new StackFrameModel(logKey, "demo.Logger", "log",
                "(Ljava/lang/String;)V", "Logger.log(String) line 88", 88, 3, false, false, true, true, null,
                List.of(local("msg", "java.lang.String", ref(NOTE, "java.lang.String"))));

        Map<String, List<VariableModel>> deletedVariables = Map.of(
                mainKey, List.of(local("temp", "int", prim("int", "7"))));

        HeapObjectModel deadOrder = HeapObjectModel.plain(DEAD_ORDER, "shop.Order", "Order", List.of(
                new FieldModel("total", "shop.Order", "double", prim("double", "19.99")),
                new FieldModel("buyer", "shop.Order", "demo.Person", ref(PERSON, "demo.Person"))),
                0);

        List<StaticsClassModel> deletedStaticClasses = List.of(
                new StaticsClassModel("bank.Audit", "Audit", List.of(
                        new FieldModel("trail", "bank.Audit", "shop.Order", ref(DEAD_ORDER, "shop.Order"))),
                        0));

        Map<String, List<FieldModel>> deletedStaticFields = Map.of(
                "bank.Bank", List.of(
                        new FieldModel("OLD_LIMIT", "bank.Bank", "int", prim("int", "100"))));

        return new MemoryDiff(7, frameStatus, variableStatus, objectStatus, fieldStatus, changedElements,
                staticStatus, List.of(deletedLogFrame), deletedVariables, List.of(deadOrder),
                deletedStaticClasses, deletedStaticFields);
    }

    private static PrimitiveValue prim(String type, String text) {
        return new PrimitiveValue(type, text);
    }

    private static HeapReference ref(long id, String type) {
        return new HeapReference(id, type);
    }

    private static VariableModel local(String name, String type, com.github.ethangodden.memorydiagram.model.ValueModel value) {
        return new VariableModel(name, type, value);
    }

    private static VariableModel thisVar(String type, long targetId) {
        return new VariableModel("this", type, ref(targetId, type));
    }
}
