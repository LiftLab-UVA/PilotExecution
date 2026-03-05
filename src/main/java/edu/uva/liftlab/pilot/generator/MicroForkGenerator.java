package edu.uva.liftlab.pilot.generator;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;

import static edu.uva.liftlab.pilot.util.Constants.*;

public class MicroForkGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(MicroForkGenerator.class);

    private static final String PILOT_UTIL_CLASS = "org.pilot.PilotUtil";
    private static final String WRAP_CONTEXT_CLASS = "org.pilot.WrapContext";

    private final Map<Unit, Unit> originalToPilotMap = new HashMap<>();


    // This method should be replaced with the one from the HBase branch and
    // merged with Angting's branch containing the newest pilot function instrumentation logic
    public void processMethod(SootMethod originalMethod, SootClass sootClass,
                              Map<Unit, String> callSiteIds) {
        if (callSiteIds == null || callSiteIds.isEmpty()) {
            return;
        }

        String pilotName = originalMethod.getName() + INSTRUMENTATION_SUFFIX;
        SootMethod pilotMethod;
        try {
            pilotMethod = sootClass.getMethod(pilotName,
                    originalMethod.getParameterTypes(),
                    originalMethod.getReturnType());
        } catch (RuntimeException e) {
            LOG.warn("Method {} not found in {}", pilotName, sootClass.getName());
            return;
        }

        buildUnitMapping(originalMethod.getActiveBody(), pilotMethod.getActiveBody());

        Map<Unit, String> pilotCallSiteIds = new HashMap<>();
        for (Map.Entry<Unit, String> entry : callSiteIds.entrySet()) {
            Unit mapped = originalToPilotMap.get(entry.getKey());
            if (mapped != null) {
                pilotCallSiteIds.put(mapped, entry.getValue());
            }
        }

        instrumentOriginalWithRecordingHooks(originalMethod, callSiteIds);

        if (!pilotCallSiteIds.isEmpty()) {
            insertFastForwardPreamble(pilotMethod, pilotCallSiteIds);
        }
    }

    private void instrumentOriginalWithRecordingHooks(SootMethod method,
                                                      Map<Unit, String> callSiteIds) {
        Body body = method.getActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);
        String methodSig = method.getSignature();
        List<Local> recordableLocals = getRecordableLocals(body);

        for (Map.Entry<Unit, String> entry : callSiteIds.entrySet()) {
            Unit callSite = entry.getKey();
            String callSiteId = entry.getValue();

            Local threadRef = lg.generateLocal(RefType.v("java.lang.Thread"));
            Unit getThread = Jimple.v().newAssignStmt(
                    threadRef,
                    makeStaticInvoke("java.lang.Thread", "currentThread",
                            Collections.emptyList(),
                            RefType.v("java.lang.Thread")));

            Local threadId = lg.generateLocal(LongType.v());
            Unit getThreadId = Jimple.v().newAssignStmt(
                    threadId,
                    Jimple.v().newVirtualInvokeExpr(
                            threadRef,
                            Scene.v().makeMethodRef(
                                    Scene.v().loadClassAndSupport("java.lang.Thread"),
                                    "getId",
                                    Collections.emptyList(),
                                    LongType.v(),
                                    false)));

            Unit recordUnit = Jimple.v().newInvokeStmt(
                    makeStaticInvokeTyped(PILOT_UTIL_CLASS, "recordExecutingUnit",
                            Arrays.asList(
                                    StringConstant.v(methodSig),
                                    StringConstant.v(callSiteId),
                                    threadId),
                            Arrays.asList(
                                    RefType.v("java.lang.String"),
                                    RefType.v("java.lang.String"),
                                    LongType.v()),
                            VoidType.v()));

            List<Unit> recordStateUnits =
                    buildRecordStateUnits(methodSig, recordableLocals, lg);

            units.insertBefore(getThread, callSite);
            units.insertBefore(getThreadId, callSite);
            units.insertBefore(recordUnit, callSite);
            for (Unit u : recordStateUnits) {
                units.insertBefore(u, callSite);
            }

            Unit popUnit = Jimple.v().newInvokeStmt(
                    makeStaticInvokeTyped(PILOT_UTIL_CLASS, "popExecutingUnit",
                            Arrays.asList(threadId),
                            Arrays.asList(LongType.v()),
                            VoidType.v()));
            units.insertAfter(popUnit, callSite);
        }
        body.validate();
    }

    private void insertFastForwardPreamble(SootMethod pilotMethod,
                                           Map<Unit, String> callSiteIds) {
        Body body = pilotMethod.getActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        String originalSig = pilotMethod.getSignature()
                .replace(INSTRUMENTATION_SUFFIX, "");

        Unit normalExecTarget = findFirstNonIdentityStmt(units);
        if (normalExecTarget == null) {
            return;
        }

        Local isFfLocal = lg.generateLocal(BooleanType.v());
        Unit assignIsFf = Jimple.v().newAssignStmt(
                isFfLocal,
                makeStaticInvoke(PILOT_UTIL_CLASS, "isFastForward",
                        Collections.emptyList(), BooleanType.v()));

        IfStmt ifNotFf = Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(isFfLocal, IntConstant.v(0)),
                normalExecTarget);

        Local stateMapLocal = lg.generateLocal(RefType.v("java.util.HashMap"));
        Unit assignStateMap = Jimple.v().newAssignStmt(
                stateMapLocal,
                makeStaticInvokeTyped(PILOT_UTIL_CLASS, "getState",
                        Arrays.asList(StringConstant.v(originalSig)),
                        Arrays.asList(RefType.v("java.lang.String")),
                        RefType.v("java.util.HashMap")));

        List<Unit> restoreUnits = buildRestoreStateUnits(body, stateMapLocal, lg);

        Local threadRef = lg.generateLocal(RefType.v("java.lang.Thread"));
        Unit getThread = Jimple.v().newAssignStmt(
                threadRef,
                makeStaticInvoke("java.lang.Thread", "currentThread",
                        Collections.emptyList(),
                        RefType.v("java.lang.Thread")));

        Local threadId = lg.generateLocal(LongType.v());
        Unit getThreadId = Jimple.v().newAssignStmt(
                threadId,
                Jimple.v().newVirtualInvokeExpr(
                        threadRef,
                        Scene.v().makeMethodRef(
                                Scene.v().loadClassAndSupport("java.lang.Thread"),
                                "getId",
                                Collections.emptyList(),
                                LongType.v(),
                                false)));

        Local targetId = lg.generateLocal(IntType.v());
        Unit getTargetId = Jimple.v().newAssignStmt(
                targetId,
                makeStaticInvokeTyped(PILOT_UTIL_CLASS, "getExecutingUnit",
                        Arrays.asList(threadId),
                        Arrays.asList(LongType.v()),
                        IntType.v()));

        List<IntConstant> lookupValues = new ArrayList<>();
        List<Unit> lookupTargets = new ArrayList<>();
        for (Map.Entry<Unit, String> entry : callSiteIds.entrySet()) {
            lookupValues.add(IntConstant.v(Integer.parseInt(entry.getValue())));
            lookupTargets.add(entry.getKey());
        }
        LookupSwitchStmt lookupSwitch = Jimple.v().newLookupSwitchStmt(
                targetId, lookupValues, lookupTargets, normalExecTarget);

        List<Unit> preamble = new ArrayList<>();
        preamble.add(assignIsFf);
        preamble.add(ifNotFf);
        preamble.add(assignStateMap);
        preamble.addAll(restoreUnits);
        preamble.add(getThread);
        preamble.add(getThreadId);
        preamble.add(getTargetId);
        preamble.add(lookupSwitch);

        for (Unit u : preamble) {
            units.insertBefore(u, normalExecTarget);
        }

        body.validate();
    }

    private List<Unit> buildRecordStateUnits(String methodSig,
                                             List<Local> locals,
                                             LocalGeneratorUtil lg) {
        List<Unit> result = new ArrayList<>();
        SootClass hashMapClass =
                Scene.v().loadClassAndSupport("java.util.HashMap");
        SootClass wrapCtxClass =
                Scene.v().loadClassAndSupport(WRAP_CONTEXT_CLASS);

        Local mapLocal = lg.generateLocal(RefType.v("java.util.HashMap"));
        result.add(Jimple.v().newAssignStmt(
                mapLocal,
                Jimple.v().newNewExpr(RefType.v("java.util.HashMap"))));
        result.add(Jimple.v().newInvokeStmt(
                Jimple.v().newSpecialInvokeExpr(
                        mapLocal,
                        Scene.v().makeMethodRef(
                                hashMapClass, "<init>",
                                Collections.emptyList(),
                                VoidType.v(), false))));

        for (Local local : locals) {
            Local boxed = boxIfPrimitive(local, lg, result);

            Local wcLocal = lg.generateLocal(RefType.v(WRAP_CONTEXT_CLASS));
            result.add(Jimple.v().newAssignStmt(
                    wcLocal,
                    Jimple.v().newNewExpr(RefType.v(WRAP_CONTEXT_CLASS))));
            result.add(Jimple.v().newInvokeStmt(
                    Jimple.v().newSpecialInvokeExpr(
                            wcLocal,
                            Scene.v().makeMethodRef(
                                    wrapCtxClass, "<init>",
                                    Arrays.asList(RefType.v("java.lang.Object")),
                                    VoidType.v(), false),
                            Arrays.asList(boxed))));

            result.add(Jimple.v().newInvokeStmt(
                    Jimple.v().newVirtualInvokeExpr(
                            mapLocal,
                            Scene.v().makeMethodRef(
                                    hashMapClass, "put",
                                    Arrays.asList(
                                            RefType.v("java.lang.Object"),
                                            RefType.v("java.lang.Object")),
                                    RefType.v("java.lang.Object"), false),
                            Arrays.asList(
                                    StringConstant.v(local.getName()),
                                    wcLocal))));
        }

        result.add(Jimple.v().newInvokeStmt(
                makeStaticInvokeTyped(PILOT_UTIL_CLASS, "recordState",
                        Arrays.asList(StringConstant.v(methodSig), mapLocal),
                        Arrays.asList(
                                RefType.v("java.lang.String"),
                                RefType.v("java.util.HashMap")),
                        VoidType.v())));

        return result;
    }

    private List<Unit> buildRestoreStateUnits(Body body,
                                              Local stateMapLocal,
                                              LocalGeneratorUtil lg) {
        List<Unit> result = new ArrayList<>();
        SootClass hashMapClass =
                Scene.v().loadClassAndSupport("java.util.HashMap");

        for (Local local : getRecordableLocals(body)) {
            Local raw = lg.generateLocal(RefType.v("java.lang.Object"));
            result.add(Jimple.v().newAssignStmt(
                    raw,
                    Jimple.v().newVirtualInvokeExpr(
                            stateMapLocal,
                            Scene.v().makeMethodRef(
                                    hashMapClass, "get",
                                    Arrays.asList(RefType.v("java.lang.Object")),
                                    RefType.v("java.lang.Object"), false),
                            Arrays.asList(StringConstant.v(local.getName())))));

            NopStmt skipRestore = Jimple.v().newNopStmt();
            result.add(Jimple.v().newIfStmt(
                    Jimple.v().newEqExpr(raw, NullConstant.v()),
                    skipRestore));

            Local wcLocal = lg.generateLocal(RefType.v(WRAP_CONTEXT_CLASS));
            result.add(Jimple.v().newAssignStmt(
                    wcLocal,
                    Jimple.v().newCastExpr(raw, RefType.v(WRAP_CONTEXT_CLASS))));

            SootField valueField =
                    Scene.v().loadClassAndSupport(WRAP_CONTEXT_CLASS)
                            .getFieldByName("value");
            Local valObj = lg.generateLocal(RefType.v("java.lang.Object"));
            result.add(Jimple.v().newAssignStmt(
                    valObj,
                    Jimple.v().newInstanceFieldRef(wcLocal, valueField.makeRef())));

            if (local.getType() instanceof PrimType) {
                result.addAll(unboxPrimitive(valObj, local, lg));
            } else {
                result.add(Jimple.v().newAssignStmt(
                        local,
                        Jimple.v().newCastExpr(valObj, local.getType())));
            }

            result.add(skipRestore);
        }

        return result;
    }

    private void buildUnitMapping(Body originalBody, Body pilotBody) {
        originalToPilotMap.clear();
        Iterator<Unit> origIt = originalBody.getUnits().iterator();
        Iterator<Unit> pilotIt = pilotBody.getUnits().iterator();
        while (origIt.hasNext() && pilotIt.hasNext()) {
            originalToPilotMap.put(origIt.next(), pilotIt.next());
        }
    }

    private Unit findFirstNonIdentityStmt(PatchingChain<Unit> units) {
        for (Unit u : units) {
            if (!(u instanceof IdentityStmt)) {
                return u;
            }
        }
        return null;
    }

    private Unit findLastIdentityStmt(PatchingChain<Unit> units) {
        Unit last = null;
        for (Unit u : units) {
            if (u instanceof IdentityStmt) {
                last = u;
            } else {
                break;
            }
        }
        return last;
    }

    private List<Local> getRecordableLocals(Body body) {
        Set<Local> identityLocals = new HashSet<>();
        for (Unit u : body.getUnits()) {
            if (u instanceof IdentityStmt) {
                Value left = ((IdentityStmt) u).getLeftOp();
                if (left instanceof Local) {
                    identityLocals.add((Local) left);
                }
            }
        }

        List<Local> result = new ArrayList<>();
        for (Local l : body.getLocals()) {
            if (identityLocals.contains(l)) continue;
            if (l.getName().startsWith("$")) continue;
            result.add(l);
        }
        return result;
    }

    private Local boxIfPrimitive(Local local, LocalGeneratorUtil lg,
                                 List<Unit> result) {
        if (!(local.getType() instanceof PrimType)) {
            return local;
        }
        String wrapper = getWrapperClass((PrimType) local.getType());
        RefType wrapperType = RefType.v(wrapper);
        Local boxed = lg.generateLocal(wrapperType);
        result.add(Jimple.v().newAssignStmt(
                boxed,
                Jimple.v().newStaticInvokeExpr(
                        Scene.v().makeMethodRef(
                                Scene.v().loadClassAndSupport(wrapper),
                                "valueOf",
                                Arrays.asList(local.getType()),
                                wrapperType,
                                true),
                        Arrays.asList(local))));
        return boxed;
    }

    private List<Unit> unboxPrimitive(Local objLocal, Local targetLocal,
                                      LocalGeneratorUtil lg) {
        List<Unit> result = new ArrayList<>();
        PrimType pt = (PrimType) targetLocal.getType();
        String wrapper = getWrapperClass(pt);
        RefType wt = RefType.v(wrapper);

        Local casted = lg.generateLocal(wt);
        result.add(Jimple.v().newAssignStmt(
                casted,
                Jimple.v().newCastExpr(objLocal, wt)));
        result.add(Jimple.v().newAssignStmt(
                targetLocal,
                Jimple.v().newVirtualInvokeExpr(
                        casted,
                        Scene.v().makeMethodRef(
                                Scene.v().loadClassAndSupport(wrapper),
                                pt.toString() + "Value",
                                Collections.emptyList(),
                                pt,
                                false))));
        return result;
    }

    private String getWrapperClass(PrimType t) {
        if (t instanceof IntType)     return "java.lang.Integer";
        if (t instanceof LongType)    return "java.lang.Long";
        if (t instanceof FloatType)   return "java.lang.Float";
        if (t instanceof DoubleType)  return "java.lang.Double";
        if (t instanceof BooleanType) return "java.lang.Boolean";
        if (t instanceof ByteType)    return "java.lang.Byte";
        if (t instanceof ShortType)   return "java.lang.Short";
        if (t instanceof CharType)    return "java.lang.Character";
        throw new RuntimeException("Unknown primitive type: " + t);
    }

    private StaticInvokeExpr makeStaticInvoke(String cls, String method,
                                              List<?> args, Type retType) {
        List<Type> types = new ArrayList<>();
        List<Value> vals = new ArrayList<>();
        for (Object a : args) {
            Value v = (Value) a;
            vals.add(v);
            types.add(v.getType());
        }
        return Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport(cls),
                        method, types, retType, true),
                vals);
    }

    private StaticInvokeExpr makeStaticInvokeTyped(String cls, String method,
                                                   List<?> args,
                                                   List<Type> paramTypes,
                                                   Type retType) {
        List<Value> vals = new ArrayList<>();
        for (Object a : args) {
            vals.add((Value) a);
        }
        return Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport(cls),
                        method, paramTypes, retType, true),
                vals);
    }
}