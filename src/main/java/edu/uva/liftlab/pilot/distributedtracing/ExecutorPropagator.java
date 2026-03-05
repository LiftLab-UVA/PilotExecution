package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.transformer.PilotTransformer;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;

import static edu.uva.liftlab.pilot.distributedtracing.utils.TracingUtil.*;
import static edu.uva.liftlab.pilot.util.Constants.*;
import static edu.uva.liftlab.pilot.util.SootUtils.getDryRunTraceFieldName;

public class ExecutorPropagator {
    public static final List<String> executorServiceTypes = Arrays.asList(
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.ScheduledExecutorService",
            "com.google.common.util.concurrent.ListeningExecutorService"
    );
    private static final Logger LOG = LoggerFactory.getLogger(ExecutorPropagator.class);
    public SootClass sootClass;
    public ClassFilterHelper filterHelper;
    ExecutorPropagator(SootClass sootClass) {
        this.sootClass = sootClass;
    }

    public ExecutorPropagator(SootClass sootClass, ClassFilterHelper filterHelper) {
        this.sootClass = sootClass;
        this.filterHelper = filterHelper;
    }

    public void contextTracking() {
        for (SootMethod method : sootClass.getMethods()) {
            if (!method.getName().endsWith(INSTRUMENTATION_SUFFIX) && !filterHelper.isTraceClass(method.getDeclaringClass())) {
                continue;
            }

            trackExecutorRunnableCalls(method);
            trackExecutorCallableCalls(method);
        }
    }

    private void trackExecutorRunnableCalls(SootMethod method) {
        if (!method.hasActiveBody()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit u : originalUnits) {
            InvokeExpr invoke = null;

            if (u instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) u;
                Value rightOp = stmt.getRightOp();
                if (rightOp instanceof InvokeExpr) {
                    invoke = (InvokeExpr) rightOp;
                }
            } else if (u instanceof InvokeStmt) {
                invoke = ((InvokeStmt) u).getInvokeExpr();
            }

            if (invoke != null && shouldBeWrapped4Runnable(invoke)) {
                instrumentExecutorSubmitForTracking(u, invoke, units, lg, method, "Runnable");
            }
        }
    }

    private void trackExecutorCallableCalls(SootMethod method) {
        if (!method.hasActiveBody()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit u : originalUnits) {
            InvokeExpr invoke = null;

            if (u instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) u;
                Value rightOp = stmt.getRightOp();
                if (rightOp instanceof InvokeExpr) {
                    invoke = (InvokeExpr) rightOp;
                }
            } else if (u instanceof InvokeStmt) {
                invoke = ((InvokeStmt) u).getInvokeExpr();
            }

            if (invoke != null && shouldBeWrapped4Callable(invoke)) {
                instrumentExecutorSubmitForTracking(u, invoke, units, lg, method, "Callable");
            }
        }
    }

    private void instrumentExecutorSubmitForTracking(Unit unit, InvokeExpr invoke,
                                                     PatchingChain<Unit> units,
                                                     LocalGeneratorUtil lg,
                                                     SootMethod parentMethod,
                                                     String taskType) {
        List<Unit> beforeUnits = new ArrayList<>();
        List<Unit> afterUnits = new ArrayList<>();

        Local currentSpan = getSpanCurrent(lg, beforeUnits);
        Local currentContext = getSpanContext(currentSpan, lg, beforeUnits);
        Local parentSpanId = getSpanId(currentContext, lg, beforeUnits);
        Local traceId = getTraceId(currentContext, lg, beforeUnits);

        Local parentMethodName = lg.generateLocal(RefType.v("java.lang.String"));
        beforeUnits.add(Jimple.v().newAssignStmt(
                parentMethodName,
                StringConstant.v(parentMethod.getDeclaringClass().getName() + "." + parentMethod.getName())
        ));

        Value task = invoke.getArg(0);
        Local taskClass = lg.generateLocal(RefType.v("java.lang.String"));
        beforeUnits.add(Jimple.v().newAssignStmt(
                taskClass,
                StringConstant.v(task.getType().toString())
        ));

        units.insertBefore(beforeUnits, unit);

        recordAsyncSubmission(traceId, parentSpanId, parentMethodName,
                taskClass, taskType, lg, afterUnits);

        units.insertAfter(afterUnits, unit);
    }

    private void recordAsyncSubmission(Local traceId, Local parentSpanId,
                                       Local parentMethod, Local taskClass,
                                       String taskType, LocalGeneratorUtil lg,
                                       List<Unit> units) {
        SootClass recorderClass = Scene.v().getSootClass("org.pilot.trace.TraceRecorder");

        Local edgeType = lg.generateLocal(RefType.v("java.lang.String"));
        units.add(Jimple.v().newAssignStmt(
                edgeType,
                StringConstant.v("ASYNC_SUBMIT_" + taskType.toUpperCase())
        ));


        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(
                        recorderClass.getMethod("recordAsyncSubmission",
                                Arrays.asList(
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String")
                                ),
                                VoidType.v()
                        ).makeRef(),
                        Arrays.asList(traceId, parentSpanId, parentMethod, taskClass, edgeType)
                )
        ));
    }


    private Local getSpanCurrent(LocalGeneratorUtil lg, List<Unit> units) {
        SootClass spanClass = Scene.v().getSootClass("io.opentelemetry.api.trace.Span");
        Local span = lg.generateLocal(RefType.v(spanClass));

        units.add(Jimple.v().newAssignStmt(
                span,
                Jimple.v().newStaticInvokeExpr(
                        spanClass.getMethod("current", Collections.emptyList()).makeRef()
                )
        ));

        return span;
    }

    private Local getSpanContext(Local span, LocalGeneratorUtil lg, List<Unit> units) {
        SootClass spanClass = Scene.v().getSootClass("io.opentelemetry.api.trace.Span");
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.api.trace.SpanContext");
        Local context = lg.generateLocal(RefType.v(contextClass));

        units.add(Jimple.v().newAssignStmt(
                context,
                Jimple.v().newInterfaceInvokeExpr(
                        span,
                        spanClass.getMethod("getSpanContext", Collections.emptyList()).makeRef()
                )
        ));

        return context;
    }

    private Local getTraceId(Local spanContext, LocalGeneratorUtil lg, List<Unit> units) {
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.api.trace.SpanContext");
        Local traceId = lg.generateLocal(RefType.v("java.lang.String"));

        units.add(Jimple.v().newAssignStmt(
                traceId,
                Jimple.v().newInterfaceInvokeExpr(
                        spanContext,
                        contextClass.getMethod("getTraceId", Collections.emptyList()).makeRef()
                )
        ));

        return traceId;
    }

    private Local getSpanId(Local spanContext, LocalGeneratorUtil lg, List<Unit> units) {
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.api.trace.SpanContext");
        Local spanId = lg.generateLocal(RefType.v("java.lang.String"));

        units.add(Jimple.v().newAssignStmt(
                spanId,
                Jimple.v().newInterfaceInvokeExpr(
                        spanContext,
                        contextClass.getMethod("getSpanId", Collections.emptyList()).makeRef()
                )
        ));

        return spanId;
    }

    public void propagateContextExperiment() {
        for(SootMethod method : sootClass.getMethods()) {
            this.wrapExecutorRunnableParameter(method);
            this.wrapExecutorCallableParameter(method);
        }
    }

    public void propagateContext(){
        for(SootMethod method : sootClass.getMethods()) {
            //LOG.info("Propagating baggage for method: {}", method.getName());
//            if(!method.getName().endsWith(INSTRUMENTATION_SUFFIX) &&! filterHelper.isInWhiteList(method.getDeclaringClass())){
//                continue;
//            }
            if(!method.getName().endsWith(INSTRUMENTATION_SUFFIX) && !filterHelper.isTraceClass(method.getDeclaringClass())){
                continue;
            }

            this.wrapExecutorRunnableParameter(method);
            this.wrapExecutorCallableParameter(method);
        }
    }

    protected void wrapExecutorCallableParameter(SootMethod method){
        if (!method.hasActiveBody()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit u : originalUnits) {
            if (u instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) u;
                Value rightOp = stmt.getRightOp();
                if (rightOp instanceof InvokeExpr && shouldBeWrapped4Callable((InvokeExpr) rightOp)) {
                    List<Unit> newUnits = new ArrayList<>();
                    LOG.info("Wrapping executor parameter in assignment: {}", stmt);
                    PilotTransformer.ctxCount++;
                    Local wrappedCallableParameter = wrapCallable((InstanceInvokeExpr) rightOp, lg, body, newUnits);
                    units.insertBefore(newUnits, u);
                    Local baseLocal = lg.generateLocal(((InstanceInvokeExpr) rightOp).getBase().getType());
                    units.insertBefore(Jimple.v().newAssignStmt(baseLocal, ((InstanceInvokeExpr) rightOp).getBase()), u);
                    InstanceInvokeExpr newInvoke = getNewInvokeWithTracedParameter((InvokeExpr) rightOp, wrappedCallableParameter, baseLocal);
                    stmt.setRightOp(newInvoke);

                    Value leftOp = stmt.getLeftOp();
                    if (isFutureType(leftOp.getType())) {
                        List<Unit> trackFutureUnits = createTrackFutureCall(lg, (Local) leftOp);
                        units.insertAfter(trackFutureUnits, u);
                    }

                    try {
                        body.validate();
                    } catch (Exception e) {
                        LOG.error("Failed to validate body after wrapping executor parameter in assignment", e);
                        throw e;
                    }

                }
            }
            else if (u instanceof InvokeStmt && shouldBeWrapped4Callable(((InvokeStmt) u).getInvokeExpr())){
                LOG.info("Wrapping executor parameter in invoke statement: {}", u);
                PilotTransformer.ctxCount++;
                List<Unit> newUnits = new ArrayList<>();
                InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) ((InvokeStmt) u).getInvokeExpr();
                Local wrappedCallableParameter = wrapCallable(instanceInvoke, lg, body, newUnits);
                units.insertBefore(newUnits, u);
                Local baseLocal = lg.generateLocal(instanceInvoke.getBase().getType());
                units.insertBefore(Jimple.v().newAssignStmt(baseLocal, (instanceInvoke.getBase())), u);
                InstanceInvokeExpr newInvoke = getNewInvokeWithTracedParameter(instanceInvoke, wrappedCallableParameter, baseLocal);
                ((InvokeStmt) u).setInvokeExpr(newInvoke);
                try {
                    body.validate();
                } catch (Exception e) {
                    LOG.error("Failed to validate body after wrapping executor parameter in invokeStmt", e);
                    throw e;
                }
            }
        }
    }


    protected void wrapExecutorRunnableParameter(SootMethod method) {
        if (!method.hasActiveBody()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit u : originalUnits) {
            if (u instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) u;
                Value rightOp = stmt.getRightOp();
                if (rightOp instanceof InvokeExpr && shouldBeWrapped4Runnable((InvokeExpr) rightOp)) {
                    List<Unit> newUnits = new ArrayList<>();
                    PilotTransformer.ctxCount++;
                    LOG.info("Wrapping executor parameter in assignment: {}", stmt);
                    Local wrappedRunnableParameter = wrapRunnable((InstanceInvokeExpr) rightOp, lg, body, newUnits);
                    units.insertBefore(newUnits, u);
                    Local baseLocal = lg.generateLocal(((InstanceInvokeExpr) rightOp).getBase().getType());
                    units.insertBefore(Jimple.v().newAssignStmt(baseLocal, ((InstanceInvokeExpr) rightOp).getBase()), u);
                    InstanceInvokeExpr newInvoke = getNewInvokeWithTracedParameter((InvokeExpr) rightOp, wrappedRunnableParameter, baseLocal);
                    stmt.setRightOp(newInvoke);

                    Value leftOp = stmt.getLeftOp();
                    if (isFutureType(leftOp.getType())) {
                        List<Unit> trackFutureUnits = createTrackFutureCall(lg, (Local) leftOp);
                        units.insertAfter(trackFutureUnits, u);
                    }

                    try {
                        body.validate();
                    } catch (Exception e) {
                        LOG.error("Failed to validate body after wrapping executor parameter in assignment", e);
                        throw e;
                    }

                }
            }
            else if (u instanceof InvokeStmt && shouldBeWrapped4Runnable(((InvokeStmt) u).getInvokeExpr())){
                PilotTransformer.ctxCount++;
                LOG.info("Wrapping executor parameter in invoke statement: {}", u);
                List<Unit> newUnits = new ArrayList<>();
                InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) ((InvokeStmt) u).getInvokeExpr();
                Local wrappedRunnableParameter = wrapRunnable(instanceInvoke, lg, body, newUnits);
                units.insertBefore(newUnits, u);
                Local baseLocal = lg.generateLocal(instanceInvoke.getBase().getType());
                units.insertBefore(Jimple.v().newAssignStmt(baseLocal, (instanceInvoke.getBase())), u);
                InstanceInvokeExpr newInvoke = getNewInvokeWithTracedParameter(instanceInvoke, wrappedRunnableParameter, baseLocal);
                ((InvokeStmt) u).setInvokeExpr(newInvoke);
                
                try {
                    body.validate();
                } catch (Exception e) {
                    LOG.error("Failed to validate body after wrapping executor parameter in invokeStmt", e);
                    throw e;
                }
            }
        }
    }


    private boolean isFutureType(Type type) {
        if (!(type instanceof RefType)) {
            return false;
        }

        RefType refType = (RefType) type;
        SootClass sootClass = refType.getSootClass();

        
        List<String> futureTypes = Arrays.asList(
                "java.util.concurrent.Future",
                "java.util.concurrent.ScheduledFuture",
                "java.util.concurrent.RunnableFuture",
                "java.util.concurrent.FutureTask",
                "com.google.common.util.concurrent.ListenableFuture",
                "com.google.common.util.concurrent.ListenableScheduledFuture"
        );

        if (futureTypes.contains(sootClass.getName())) {
            return true;
        }


        try {
            SootClass futureClass = Scene.v().getSootClass("java.util.concurrent.Future");
            if (isSubtypeOf(sootClass, futureClass)) {
                return true;
            }
        } catch (Exception e) {
            LOG.debug("Failed to check Future subtype: {}", e.getMessage());
        }

        return false;
    }

    /**

     */
    private boolean isSubtypeOf(SootClass child, SootClass parent) {
        if (child.equals(parent)) {
            return true;
        }

        
        for (SootClass iface : child.getInterfaces()) {
            if (iface.equals(parent) || isSubtypeOf(iface, parent)) {
                return true;
            }
        }


        if (child.hasSuperclass()) {
            return isSubtypeOf(child.getSuperclass(), parent);
        }

        return false;
    }


    private List<Unit> createTrackFutureCall(LocalGeneratorUtil lg, Local futureLocal) {
        List<Unit> units = new ArrayList<>();

        SootClass pilotUtilClass = Scene.v().getSootClass(PILOT_UTIL_CLASS_NAME);


        SootMethod trackFutureMethod = pilotUtilClass.getMethod(
                "trackFuture",
                Collections.singletonList(RefType.v("java.util.concurrent.Future")),
                VoidType.v()
        );


        StaticInvokeExpr trackFutureInvoke = Jimple.v().newStaticInvokeExpr(
                trackFutureMethod.makeRef(),
                Collections.singletonList(futureLocal)
        );

        units.add(Jimple.v().newInvokeStmt(trackFutureInvoke));

        return units;
    }




    private boolean shouldBeWrapped4Callable(InvokeExpr invoke){
        if (!(invoke instanceof InstanceInvokeExpr)) {
            LOG.info("Invoke expression is not an instance invoke expression: {}", invoke);
            return false;
        }

        InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) invoke;
        Value base = instanceInvoke.getBase();

        if(base == null){
            return false;
        }

        if (isExecutorType(base.getType()) &&
                isExecutorMethod(invoke.getMethod()) &&
                !invoke.getArgs().isEmpty() &&
                isCallableType(invoke.getArg(0).getType())) {

            return true;
        }

        return false;
    }

    private boolean shouldBeWrapped4Runnable(InvokeExpr invoke){
        if (!(invoke instanceof InstanceInvokeExpr)) {
            LOG.info("Invoke expression is not an instance invoke expression: {}", invoke);
            return false;
        }

        InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) invoke;
        Value base = instanceInvoke.getBase();

        if(base == null){
            return false;
        }

        if (isExecutorType(base.getType()) &&
                isExecutorMethod(invoke.getMethod()) &&
                !invoke.getArgs().isEmpty() &&
                isRunnableType(invoke.getArg(0).getType())) {

            return true;
        }

        return false;
    }


    private Local wrapRunnable(InstanceInvokeExpr instanceInvoke, LocalGeneratorUtil lg, Body body, List<Unit> newUnits){
        SootClass traceUtilClass = Scene.v().getSootClass(PILOT_UTIL_CLASS_NAME);
        //print sootmethod in traceUtilClass
        SootMethod shouldBeContextWrapMethod = traceUtilClass.getMethod(SHOULD_BE_CONTEXT_WRAP_METHOD_SIGNATURE);

        Value originalRunnable = instanceInvoke.getArg(0);
        Local tempRunnable = lg.generateLocal(originalRunnable.getType());

        Unit assignRunnableStmt = Jimple.v().newAssignStmt(tempRunnable, originalRunnable);
        newUnits.add(assignRunnableStmt);

        Local baseLocal = lg.generateLocal(instanceInvoke.getBase().getType());
        newUnits.add(Jimple.v().newAssignStmt(baseLocal, instanceInvoke.getBase()));

        StaticInvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(
                shouldBeContextWrapMethod.makeRef(),
                Arrays.asList(originalRunnable, baseLocal)
        );

        Local resultRunnable = lg.generateLocal(BooleanType.v());

        AssignStmt assignStmt = Jimple.v().newAssignStmt(
                resultRunnable,
                invokeExpr
        );
        newUnits.add(assignStmt);

        List<Unit> contextWrapUnits = new ArrayList<>();
        Local wrappedRunnable = wrapExecutorRunnableParameterWithContext(lg, body, tempRunnable, contextWrapUnits);
        contextWrapUnits.add(Jimple.v().newAssignStmt(tempRunnable, wrappedRunnable));

        //List<Unit> trycatchWrapUnits = wrapExecutorRunnableParameterWithTryCatch(tempRunnable);
        List<Unit> trycatchWrapUnits = new ArrayList<>();

        NopStmt endNop = Jimple.v().newNopStmt();
        NopStmt nop = Jimple.v().newNopStmt();
        IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(resultRunnable, IntConstant.v(0)),
                nop
        );
        newUnits.add(ifStmt);

        newUnits.addAll(contextWrapUnits);
        GotoStmt gotoStmt = Jimple.v().newGotoStmt(endNop);
        newUnits.add(gotoStmt);
        newUnits.add(nop);
        newUnits.addAll(trycatchWrapUnits);
        newUnits.add(endNop);
        return tempRunnable;
    }


    private Local wrapCallable(InstanceInvokeExpr instanceInvoke, LocalGeneratorUtil lg, Body body, List<Unit> newUnits){
        Value originalCallable = instanceInvoke.getArg(0);
        Local tempCallable = lg.generateLocal(originalCallable.getType());

        Unit assignCallableStmt = Jimple.v().newAssignStmt(tempCallable, originalCallable);
        newUnits.add(assignCallableStmt);

        List<Unit> contextWrapUnits = new ArrayList<>();
        Local wrappedCallable = wrapExecutorCallableParameterWithContext(lg, body, tempCallable, contextWrapUnits);

        contextWrapUnits.add(Jimple.v().newAssignStmt(tempCallable, wrappedCallable));

        newUnits.addAll(contextWrapUnits);

        return tempCallable;
    }

    public InstanceInvokeExpr getNewInvokeWithTracedParameter(InvokeExpr instanceInvoke, Local temp, Local baseLocal){
        List<Value> newArgs = new ArrayList<>(instanceInvoke.getArgs());
        newArgs.set(0, temp);

        // Create appropriate invoke expression based on the original type
        InstanceInvokeExpr newInvoke;
        if (instanceInvoke instanceof VirtualInvokeExpr) {
            newInvoke = Jimple.v().newVirtualInvokeExpr(
                    baseLocal,
                    instanceInvoke.getMethod().makeRef(),
                    newArgs
            );
        } else if (instanceInvoke instanceof InterfaceInvokeExpr) {
            newInvoke = Jimple.v().newInterfaceInvokeExpr(
                    baseLocal,
                    instanceInvoke.getMethod().makeRef(),
                    newArgs
            );
        } else if (instanceInvoke instanceof SpecialInvokeExpr) {
            newInvoke = Jimple.v().newSpecialInvokeExpr(
                    baseLocal,
                    instanceInvoke.getMethod().makeRef(),
                    newArgs
            );
        } else {
            throw new RuntimeException("Unexpected invoke expression type: " + instanceInvoke.getClass());
        }
        return newInvoke;
    }



    private List<Unit> wrapExecutorRunnableParameterWithTryCatch(Local runnableArg) {

        List<Unit> res = new ArrayList<>();

        // Get the declared type of the Runnable parameter
        RefType runnableType = (RefType) runnableArg.getType();
        SootClass runnableClass = runnableType.getSootClass();

        // Set dryRunTrace fields for the runnable
        setFlagForTrace(
                runnableClass,
                runnableArg,
                res);
        return res;
    }

    private Local wrapExecutorCallableParameterWithContext(LocalGeneratorUtil lg, Body body, Local runnableArg, List<Unit> res) {
        // Get Context class
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

        // Generate local for current context
        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

        res.add(Jimple.v().newAssignStmt(
                contextLocal,
                Jimple.v().newStaticInvokeExpr(
                        contextClass.getMethod("current",
                                Collections.emptyList(),
                                RefType.v("io.opentelemetry.context.Context")
                        ).makeRef()
                )
        ));

        // Wrap the runnable with Context.wrap()
        res.add(Jimple.v().newAssignStmt(
                runnableArg,
                Jimple.v().newInterfaceInvokeExpr(
                        contextLocal,
                        contextClass.getMethod("wrap",
                                Collections.singletonList(RefType.v("java.util.concurrent.Callable")),
                                RefType.v("java.util.concurrent.Callable")
                        ).makeRef(),
                        runnableArg
                )
        ));

        return runnableArg;
    }


    private Local wrapExecutorRunnableParameterWithContext(LocalGeneratorUtil lg, Body body, Local runnableArg, List<Unit> res) {
        // Get Context class
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

        // Generate local for current context
        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

        res.add(Jimple.v().newAssignStmt(
                contextLocal,
                Jimple.v().newStaticInvokeExpr(
                        contextClass.getMethod("current",
                                Collections.emptyList(),
                                RefType.v("io.opentelemetry.context.Context")
                        ).makeRef()
                )
        ));


//        // Create local for the wrapped runnable
//        Local wrappedRunnable = lg.generateLocal(RefType.v("java.lang.Runnable"));


        // Wrap the runnable with Context.wrap()
        res.add(Jimple.v().newAssignStmt(
                runnableArg,
                Jimple.v().newInterfaceInvokeExpr(
                        contextLocal,
                        contextClass.getMethod("wrap",
                                Collections.singletonList(RefType.v("java.lang.Runnable")),
                                RefType.v("java.lang.Runnable")
                        ).makeRef(),
                        runnableArg
                )
        ));

        return runnableArg;
    }

    private void setFlagForTrace(
            SootClass targetClass,
            Value instance,
            List<Unit> newUnits) {

        // Build class hierarchy (parent classes first)
        List<SootClass> classHierarchy = new ArrayList<>();
        SootClass currentClass = targetClass;

        while (currentClass != null && !currentClass.getName().equals("java.lang.Object")) {

            classHierarchy.add(0, currentClass);
            currentClass = currentClass.hasSuperclass() ? currentClass.getSuperclass() : null;
        }

        for (SootClass cls : classHierarchy) {
            String fieldName = getDryRunTraceFieldName(cls);

            // Skip if field doesn't exist in this class
            if (!cls.declaresField(fieldName, BooleanType.v())) {
                LOG.debug("Skipping class {} as it does not declare field {}",
                        cls.getName(), fieldName);
                continue;
            }

            try {
                SootField field = cls.getField(fieldName, BooleanType.v());
                FieldRef needBaggageFieldRef = Jimple.v().newInstanceFieldRef(
                        instance,
                        field.makeRef()
                );

                Unit setNeedBaggage = Jimple.v().newAssignStmt(
                        needBaggageFieldRef,
                        IntConstant.v(1)
                );

                newUnits.add(setNeedBaggage);
                //units.insertAfter(setNeedBaggage, currentInsertPoint);
                //currentInsertPoint = setNeedBaggage;

                LOG.debug("Successfully set dry run trace for class: {}", cls.getName());

            } catch (Exception e) {
                LOG.error("Failed to set dry run trace for class: " + cls.getName(), e);
            }
        }
    }


}