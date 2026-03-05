package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.transformer.PilotTransformer;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;

import static edu.uva.liftlab.pilot.distributedtracing.FutureCallbackParameterWrapper.implementsFutureCallBack;
import static edu.uva.liftlab.pilot.distributedtracing.utils.TracingUtil.*;
import static edu.uva.liftlab.pilot.util.Constants.INSTRUMENTATION_SUFFIX;
import static edu.uva.liftlab.pilot.util.SootUtils.getDryRunTraceFieldName;

public class FuturePropagator{
    private static final Logger LOG = LoggerFactory.getLogger(FuturePropagator.class);

    public SootClass sootClass;
    public ClassFilterHelper classFilterHelper;
    private static final Set<String> FUTURE_TYPES = new HashSet<>(Arrays.asList(
            "java.util.concurrent.FutureTask"
//            "akka.dispatch.forkjoin.ForkJoinTask",
//            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedCallable",
//            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnable",
//            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnableAction",
//            "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask",
//            "akka.dispatch.Mailbox",
//            "com.google.common.util.concurrent.AbstractFuture",
//            "com.google.common.util.concurrent.AbstractFuture$TrustedFuture",
//            "com.google.common.util.concurrent.ListenableFutureTask",
//            "com.google.common.util.concurrent.SettableFuture",
//            "io.netty.util.concurrent.CompleteFuture",
//            "io.netty.util.concurrent.FailedFuture",
//            "io.netty.util.concurrent.ScheduledFutureTask",
//            "java.util.concurrent.CompletableFuture$BiApply",
//            "java.util.concurrent.CompletableFuture$BiCompletion",
//            "java.util.concurrent.CompletableFuture$BiRelay",
//            "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor",
//            "java.util.concurrent.CountedCompleter",
//            "java.util.concurrent.ExecutorCompletionService$QueueingFuture",
//            "java.util.concurrent.ForkJoinTask",
//            "java.util.concurrent.ForkJoinTask$AdaptedCallable",
//            "java.util.concurrent.ForkJoinTask$RunnableExecuteAction",
//            "java.util.concurrent.RecursiveAction",
//            "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask",
//            "org.apache.pekko.dispatch.ForkJoinExecutorConfigurator$PekkoForkJoinTask",
//            "org.apache.pekko.dispatch.Mailbox",
//            "scala.collection.parallel.AdaptiveWorkStealingForkJoinTasks$WrappedTask",
//            "scala.concurrent.forkjoin.ForkJoinTask",
//            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedCallable",
//            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnable",
//            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnableAction",
//            "scala.concurrent.impl.ExecutionContextImpl$AdaptedForkJoinTask"
    ));

    FuturePropagator(SootClass sootClass, ClassFilterHelper classFilterHelper){
        this.sootClass = sootClass;
        this.classFilterHelper = classFilterHelper;
    }

    public void contextTracking() {
        for (SootMethod method : sootClass.getMethods()) {
            if (method.getName().endsWith(INSTRUMENTATION_SUFFIX)) {
                continue;
            }

            GoogleFuturePropagator googleFutureTracker = new GoogleFuturePropagator();
            googleFutureTracker.trackGoogleFuturesCallBack(method);
            googleFutureTracker.trackGoogleFuturesTransformAsync(method);
            googleFutureTracker.trackListenableFutureTaskAddListener(method);
        }
    }



    public void propagateContext(){
        for(SootMethod method : sootClass.getMethods()){
            if((!method.getName().endsWith(INSTRUMENTATION_SUFFIX)) && (!this.classFilterHelper.isTraceClass(method.getDeclaringClass()))){
                continue;
            }
            GoogleFuturePropagator googleFuturePropagator = new GoogleFuturePropagator();
            googleFuturePropagator.wrapGoogleFuturesTransformAsyncWithAPI(method);
            googleFuturePropagator.wrapGoogleFuturesCallBackWithAPI(method);
            googleFuturePropagator.wrapListenableFutureTaskAddListenerWithAPI(method);

        }
    }

    public void propagateContextExperiment(){
        for(SootMethod method : sootClass.getMethods()){
            GoogleFuturePropagator googleFuturePropagator = new GoogleFuturePropagator();
            googleFuturePropagator.wrapGoogleFuturesTransformAsyncWithAPI(method);
            googleFuturePropagator.wrapGoogleFuturesCallBackWithAPI(method);
            googleFuturePropagator.wrapListenableFutureTaskAddListenerWithAPI(method);

        }
    }

    public class GoogleFuturePropagator {
        protected void trackGoogleFuturesCallBack(SootMethod method) {
            if (!method.hasActiveBody()) {
                return;
            }

            Body body = method.retrieveActiveBody();
            UnitPatchingChain units = body.getUnits();
            List<Unit> originalUnits = new ArrayList<>(units);
            LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

            for (Unit u : originalUnits) {
                InvokeExpr invoke = getInvokeExpr(u);

                if (invoke != null && shouldBeWrapped4Callback(invoke)) {
                    instrumentFutureCallback(u, invoke, units, lg, method, body);
                }
            }
        }

        protected void trackGoogleFuturesTransformAsync(SootMethod method) {
            if (!method.hasActiveBody()) {
                return;
            }

            Body body = method.retrieveActiveBody();
            UnitPatchingChain units = body.getUnits();
            List<Unit> originalUnits = new ArrayList<>(units);
            LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

            for (Unit u : originalUnits) {
                InvokeExpr invoke = getInvokeExpr(u);

                if (invoke != null && shouldBeWrapped4TransformAsync(invoke)) {
                    instrumentFutureTransformAsync(u, invoke, units, lg, method, body);
                }
            }
        }

        protected void trackListenableFutureTaskAddListener(SootMethod method) {
            if (!method.hasActiveBody()) {
                return;
            }

            Body body = method.retrieveActiveBody();
            UnitPatchingChain units = body.getUnits();
            List<Unit> originalUnits = new ArrayList<>(units);
            LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

            for (Unit u : originalUnits) {
                InvokeExpr invoke = getInvokeExpr(u);

                if (invoke != null && shouldBeWrapped4AddListener(invoke)) {
                    instrumentFutureAddListener(u, invoke, units, lg, method, body);
                }
            }
        }


        private void instrumentFutureCallback(Unit unit, InvokeExpr invoke,
                                              PatchingChain<Unit> units,
                                              LocalGeneratorUtil lg,
                                              SootMethod parentMethod,
                                              Body body) {
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

            Value callbackArg = null;
            for (Value arg : invoke.getArgs()) {
                if (arg.getType() instanceof RefType &&
                        implementsFutureCallBack(((RefType) arg.getType()).getSootClass())) {
                    callbackArg = arg;
                    break;
                }
            }

            Local callbackType = lg.generateLocal(RefType.v("java.lang.String"));
            beforeUnits.add(Jimple.v().newAssignStmt(
                    callbackType,
                    StringConstant.v(callbackArg != null ? callbackArg.getType().toString() : "Unknown")
            ));

            units.insertBefore(beforeUnits, unit);


            recordFutureCallback(traceId, parentSpanId, parentMethodName,
                    callbackType, "FUTURE_CALLBACK", lg, afterUnits);

            units.insertAfter(afterUnits, unit);
        }

        private void instrumentFutureTransformAsync(Unit unit, InvokeExpr invoke,
                                                    PatchingChain<Unit> units,
                                                    LocalGeneratorUtil lg,
                                                    SootMethod parentMethod,
                                                    Body body) {
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

            Value asyncFunctionArg = invoke.getArg(1);
            Local asyncFunctionType = lg.generateLocal(RefType.v("java.lang.String"));
            beforeUnits.add(Jimple.v().newAssignStmt(
                    asyncFunctionType,
                    StringConstant.v(asyncFunctionArg.getType().toString())
            ));

            units.insertBefore(beforeUnits, unit);

            recordFutureCallback(traceId, parentSpanId, parentMethodName,
                    asyncFunctionType, "FUTURE_TRANSFORM_ASYNC", lg, afterUnits);

            units.insertAfter(afterUnits, unit);
        }

        private void instrumentFutureAddListener(Unit unit, InvokeExpr invoke,
                                                 PatchingChain<Unit> units,
                                                 LocalGeneratorUtil lg,
                                                 SootMethod parentMethod,
                                                 Body body) {
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

            Value listenerArg = invoke.getArg(0);
            Local listenerType = lg.generateLocal(RefType.v("java.lang.String"));
            beforeUnits.add(Jimple.v().newAssignStmt(
                    listenerType,
                    StringConstant.v(listenerArg.getType().toString())
            ));

            units.insertBefore(beforeUnits, unit);

            recordFutureCallback(traceId, parentSpanId, parentMethodName,
                    listenerType, "FUTURE_ADD_LISTENER", lg, afterUnits);

            units.insertAfter(afterUnits, unit);
        }


        private void wrapExecutorForContextPropagation(Unit unit, InvokeExpr invoke,
                                                       PatchingChain<Unit> units,
                                                       LocalGeneratorUtil lg,
                                                       int executorArgIndex) {
            List<Unit> newUnits = new ArrayList<>();

            
            SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");
            Local contextLocal = lg.generateLocal(RefType.v(contextClass));

            newUnits.add(Jimple.v().newAssignStmt(
                    contextLocal,
                    Jimple.v().newStaticInvokeExpr(
                            contextClass.getMethod("current",
                                    Collections.emptyList(),
                                    RefType.v("io.opentelemetry.context.Context")
                            ).makeRef()
                    )
            ));

            Value executorArg = invoke.getArg(executorArgIndex);
            Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
            newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

            Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
            newUnits.add(Jimple.v().newAssignStmt(
                    wrappedExecutor,
                    Jimple.v().newInterfaceInvokeExpr(
                            contextLocal,
                            contextClass.getMethod("wrap",
                                    Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                    RefType.v("java.util.concurrent.Executor")
                            ).makeRef(),
                            tempExecutor
                    )
            ));


            Local baseLocal = null;
            if (invoke instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) invoke).getBase();
                baseLocal = lg.generateLocal(base.getType());
                newUnits.add(Jimple.v().newAssignStmt(baseLocal, base));
            }

            units.insertBefore(newUnits, unit);

            List<Value> newArgs = new ArrayList<>(invoke.getArgs());
            newArgs.set(executorArgIndex, wrappedExecutor);

            InvokeExpr newInvoke;
            if (invoke instanceof StaticInvokeExpr) {
                newInvoke = Jimple.v().newStaticInvokeExpr(
                        invoke.getMethod().makeRef(),
                        newArgs
                );
            } else if (invoke instanceof VirtualInvokeExpr) {
                newInvoke = Jimple.v().newVirtualInvokeExpr(
                        baseLocal,
                        invoke.getMethod().makeRef(),
                        newArgs
                );
            } else if (invoke instanceof InterfaceInvokeExpr) {
                newInvoke = Jimple.v().newInterfaceInvokeExpr(
                        baseLocal,
                        invoke.getMethod().makeRef(),
                        newArgs
                );
            } else {
                return;
            }

            setInvokeExpr(unit, newInvoke);
        }

        private void recordFutureCallback(Local traceId, Local parentSpanId,
                                          Local parentMethod, Local callbackType,
                                          String edgeType, LocalGeneratorUtil lg,
                                          List<Unit> units) {
            SootClass recorderClass = Scene.v().getSootClass("org.pilot.trace.TraceRecorder");

            Local edge = lg.generateLocal(RefType.v("java.lang.String"));
            units.add(Jimple.v().newAssignStmt(
                    edge,
                    StringConstant.v(edgeType)
            ));

            // TraceRecorder.recordFutureCallback(traceId, parentSpanId, parentMethod, callbackType, edgeType)
            units.add(Jimple.v().newInvokeStmt(
                    Jimple.v().newStaticInvokeExpr(
                            recorderClass.getMethod("recordFutureCallback",
                                    Arrays.asList(
                                            RefType.v("java.lang.String"),
                                            RefType.v("java.lang.String"),
                                            RefType.v("java.lang.String"),
                                            RefType.v("java.lang.String"),
                                            RefType.v("java.lang.String")
                                    ),
                                    VoidType.v()
                            ).makeRef(),
                            Arrays.asList(traceId, parentSpanId, parentMethod, callbackType, edge)
                    )
            ));
        }

        private InvokeExpr getInvokeExpr(Unit unit) {
            if (unit instanceof InvokeStmt) {
                return ((InvokeStmt) unit).getInvokeExpr();
            } else if (unit instanceof AssignStmt) {
                Value rightOp = ((AssignStmt) unit).getRightOp();
                if (rightOp instanceof InvokeExpr) {
                    return (InvokeExpr) rightOp;
                }
            }
            return null;
        }

        private void setInvokeExpr(Unit unit, InvokeExpr newInvoke) {
            if (unit instanceof InvokeStmt) {
                ((InvokeStmt) unit).setInvokeExpr(newInvoke);
            } else if (unit instanceof AssignStmt) {
                ((AssignStmt) unit).setRightOp(newInvoke);
            }
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

        private boolean shouldBeWrapped4Callback(InvokeExpr invoke) {
            if (!(invoke instanceof StaticInvokeExpr)) {
                return false;
            }

            SootMethod method = invoke.getMethod();
            return method.getDeclaringClass().getName().equals("com.google.common.util.concurrent.Futures")
                    && !invoke.getArgs().isEmpty()
                    && method.getName().equals("addCallback")
                    && invoke.getArgs().stream().anyMatch(arg ->
                    arg.getType() instanceof RefType &&
                            implementsFutureCallBack(((RefType) arg.getType()).getSootClass()))
        	    && invoke.getArgs().stream().anyMatch(arg ->
                    arg.getType() instanceof RefType &&
                            ((RefType) arg.getType()).getSootClass().getName().equals("java.util.concurrent.Executor"));
	}

        private Local wrapCallback(InvokeExpr invoke, LocalGeneratorUtil lg, Body body, List<Unit> newUnits) {
            Value callbackArg = null;
            for (Value arg : invoke.getArgs()) {
                if (arg.getType() instanceof RefType &&
                        implementsFutureCallBack(((RefType) arg.getType()).getSootClass())) {
                    callbackArg = arg;
                    break;
                }
            }

            Local tempCallback = lg.generateLocal(callbackArg.getType());

            newUnits.add(Jimple.v().newAssignStmt(tempCallback, callbackArg));

            RefType callbackType = (RefType) callbackArg.getType();
            SootClass callbackClass = callbackType.getSootClass();

            setFlagForTrace(callbackClass, tempCallback, newUnits);

            return tempCallback;
        }

        private StaticInvokeExpr getNewInvokeWithTracedCallback(InvokeExpr originalInvoke, Local tracedCallback) {
            List<Value> newArgs = new ArrayList<>(originalInvoke.getArgs());
            for (int i = 0; i < newArgs.size(); i++) {
                Value arg = newArgs.get(i);
                if (arg.getType() instanceof RefType &&
                        implementsFutureCallBack(((RefType) arg.getType()).getSootClass())) {
                    newArgs.set(i, tracedCallback);
                    break;
                }
            }

            return Jimple.v().newStaticInvokeExpr(
                    originalInvoke.getMethod().makeRef(),
                    newArgs
            );
        }


        protected void wrapGoogleFuturesCallBack(SootMethod method) {
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
                    if (rightOp instanceof InvokeExpr && shouldBeWrapped4Callback((InvokeExpr) rightOp)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping callback function parameter in assignment: {}", stmt);
                        Local callbackParam = wrapCallback((InvokeExpr) rightOp, lg, body, newUnits);
                        units.insertBefore(newUnits, u);

                        StaticInvokeExpr newInvoke = getNewInvokeWithTracedCallback((InvokeExpr) rightOp, callbackParam);
                        stmt.setRightOp(newInvoke);
                    }
                } else if (u instanceof InvokeStmt) {
                    InvokeExpr invoke = ((InvokeStmt) u).getInvokeExpr();
                    if (shouldBeWrapped4Callback(invoke)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping callback function parameter in invoke statement: {}", u);
                        Local callbackParam = wrapCallback(invoke, lg, body, newUnits);
                        units.insertBefore(newUnits, u);

                        StaticInvokeExpr newInvoke = getNewInvokeWithTracedCallback(invoke, callbackParam);
                        ((InvokeStmt) u).setInvokeExpr(newInvoke);
                    }
                }
            }
        }

        protected void wrapGoogleFuturesCallBackWithAPI(SootMethod method) {
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
                    if (rightOp instanceof InvokeExpr && shouldBeWrapped4Callback((InvokeExpr) rightOp)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping executor parameter in assignment: {}", stmt);
                        PilotTransformer.ctxCount++;

                        InvokeExpr invoke = (InvokeExpr) rightOp;

                        // Get Context class
                        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

                        // Generate local for current context
                        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

                        // Get Context.current()
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        contextLocal,
                                        Jimple.v().newStaticInvokeExpr(
                                                contextClass.getMethod("current",
                                                        Collections.emptyList(),
                                                        RefType.v("io.opentelemetry.context.Context")
                                                ).makeRef()
                                        )
                                )
                        );

                        // Store original executor
                        Value executorArg = invoke.getArg(2); // executor is the third argument
                        Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

                        // Create wrapped executor using Context.wrap()
                        Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        wrappedExecutor,
                                        Jimple.v().newInterfaceInvokeExpr(
                                                contextLocal,
                                                contextClass.getMethod("wrap",
                                                        Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                                        RefType.v("java.util.concurrent.Executor")
                                                ).makeRef(),
                                                tempExecutor
                                        )
                                )
                        );

                        units.insertBefore(newUnits, u);

                        // Create new invoke with wrapped executor
                        List<Value> newArgs = new ArrayList<>(invoke.getArgs());
                        newArgs.set(2, wrappedExecutor); // Replace executor argument

                        StaticInvokeExpr newInvoke = Jimple.v().newStaticInvokeExpr(
                                invoke.getMethod().makeRef(),
                                newArgs
                        );
                        stmt.setRightOp(newInvoke);
                    }
                } else if (u instanceof InvokeStmt) {
                    InvokeExpr invoke = ((InvokeStmt) u).getInvokeExpr();
                    if (shouldBeWrapped4Callback(invoke)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping executor parameter in invoke statement: {}", u);

                        // Get Context class
                        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

                        // Generate local for current context
                        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

                        // Get Context.current()
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        contextLocal,
                                        Jimple.v().newStaticInvokeExpr(
                                                contextClass.getMethod("current",
                                                        Collections.emptyList(),
                                                        RefType.v("io.opentelemetry.context.Context")
                                                ).makeRef()
                                        )
                                )
                        );

                        // Store original executor
                        Value executorArg = invoke.getArg(2); // executor is the third argument
                        Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

                        // Create wrapped executor using Context.wrap()
                        Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        wrappedExecutor,
                                        Jimple.v().newInterfaceInvokeExpr(
                                                contextLocal,
                                                contextClass.getMethod("wrap",
                                                        Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                                        RefType.v("java.util.concurrent.Executor")
                                                ).makeRef(),
                                                tempExecutor
                                        )
                                )
                        );

                        units.insertBefore(newUnits, u);

                        // Create new invoke with wrapped executor
                        List<Value> newArgs = new ArrayList<>(invoke.getArgs());
                        newArgs.set(2, wrappedExecutor); // Replace executor argument

                        StaticInvokeExpr newInvoke = Jimple.v().newStaticInvokeExpr(
                                invoke.getMethod().makeRef(),
                                newArgs
                        );
                        ((InvokeStmt) u).setInvokeExpr(newInvoke);
                    }
                }
            }
        }

        protected void wrapGoogleFuturesTransformAsyncWithAPI(SootMethod method) {
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
                    if (rightOp instanceof InvokeExpr && shouldBeWrapped4TransformAsync((InvokeExpr) rightOp)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping executor parameter in transform async assignment: {}", stmt);
                        PilotTransformer.ctxCount++;

                        InvokeExpr invoke = (InvokeExpr) rightOp;

                        // Get Context class
                        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

                        // Generate local for current context
                        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

                        // Get Context.current()
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        contextLocal,
                                        Jimple.v().newStaticInvokeExpr(
                                                contextClass.getMethod("current",
                                                        Collections.emptyList(),
                                                        RefType.v("io.opentelemetry.context.Context")
                                                ).makeRef()
                                        )
                                )
                        );

                        // Store original executor
                        Value executorArg = invoke.getArg(2); // executor is the third argument
                        Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

                        // Create wrapped executor using Context.wrap()
                        Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        wrappedExecutor,
                                        Jimple.v().newInterfaceInvokeExpr(
                                                contextLocal,
                                                contextClass.getMethod("wrap",
                                                        Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                                        RefType.v("java.util.concurrent.Executor")
                                                ).makeRef(),
                                                tempExecutor
                                        )
                                )
                        );

                        units.insertBefore(newUnits, u);

                        // Create new invoke with wrapped executor
                        List<Value> newArgs = new ArrayList<>(invoke.getArgs());
                        newArgs.set(2, wrappedExecutor); // Replace executor argument

                        StaticInvokeExpr newInvoke = Jimple.v().newStaticInvokeExpr(
                                invoke.getMethod().makeRef(),
                                newArgs
                        );
                        stmt.setRightOp(newInvoke);
                    }
                } else if (u instanceof InvokeStmt) {
                    InvokeExpr invoke = ((InvokeStmt) u).getInvokeExpr();
                    if (shouldBeWrapped4TransformAsync(invoke)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping executor parameter in transform async invoke statement: {}", u);
                        PilotTransformer.ctxCount++;

                        // Get Context class
                        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

                        // Generate local for current context
                        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

                        // Get Context.current()
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        contextLocal,
                                        Jimple.v().newStaticInvokeExpr(
                                                contextClass.getMethod("current",
                                                        Collections.emptyList(),
                                                        RefType.v("io.opentelemetry.context.Context")
                                                ).makeRef()
                                        )
                                )
                        );

                        // Store original executor
                        Value executorArg = invoke.getArg(2); // executor is the third argument
                        Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

                        // Create wrapped executor using Context.wrap()
                        Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        wrappedExecutor,
                                        Jimple.v().newInterfaceInvokeExpr(
                                                contextLocal,
                                                contextClass.getMethod("wrap",
                                                        Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                                        RefType.v("java.util.concurrent.Executor")
                                                ).makeRef(),
                                                tempExecutor
                                        )
                                )
                        );

                        units.insertBefore(newUnits, u);

                        // Create new invoke with wrapped executor
                        List<Value> newArgs = new ArrayList<>(invoke.getArgs());
                        newArgs.set(2, wrappedExecutor); // Replace executor argument

                        StaticInvokeExpr newInvoke = Jimple.v().newStaticInvokeExpr(
                                invoke.getMethod().makeRef(),
                                newArgs
                        );
                        ((InvokeStmt) u).setInvokeExpr(newInvoke);
                    }
                }
            }
        }

        protected void wrapListenableFutureTaskAddListenerWithAPI(SootMethod method) {
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
                    if (rightOp instanceof InvokeExpr && shouldBeWrapped4AddListener((InvokeExpr) rightOp)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping executor parameter in addListener assignment: {}", stmt);
                        PilotTransformer.ctxCount++;

                        InvokeExpr invoke = (InvokeExpr) rightOp;

                        // Get Context class
                        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

                        // Generate local for current context
                        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

                        // Get Context.current()
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        contextLocal,
                                        Jimple.v().newStaticInvokeExpr(
                                                contextClass.getMethod("current",
                                                        Collections.emptyList(),
                                                        RefType.v("io.opentelemetry.context.Context")
                                                ).makeRef()
                                        )
                                )
                        );

                        // Store original executor
                        Value executorArg = invoke.getArg(1); // executor is the second argument in addListener
                        Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

                        // Create wrapped executor using Context.wrap()
                        Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        wrappedExecutor,
                                        Jimple.v().newInterfaceInvokeExpr(
                                                contextLocal,
                                                contextClass.getMethod("wrap",
                                                        Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                                        RefType.v("java.util.concurrent.Executor")
                                                ).makeRef(),
                                                tempExecutor
                                        )
                                )
                        );

                        // Store base in a local
                        Local baseLocal = null;
                        if (invoke instanceof VirtualInvokeExpr || invoke instanceof InterfaceInvokeExpr) {
                            Value base = ((InstanceInvokeExpr) invoke).getBase();
                            baseLocal = lg.generateLocal(base.getType());
                            newUnits.add(Jimple.v().newAssignStmt(baseLocal, base));
                        }

                        units.insertBefore(newUnits, u);

                        // Create new invoke with wrapped executor
                        List<Value> newArgs = new ArrayList<>(invoke.getArgs());
                        newArgs.set(1, wrappedExecutor); // Replace executor argument

                        // Create appropriate invoke expression based on the original type
                        InvokeExpr newInvoke;
                        if (invoke instanceof VirtualInvokeExpr) {
                            newInvoke = Jimple.v().newVirtualInvokeExpr(
                                    baseLocal,
                                    invoke.getMethod().makeRef(),
                                    newArgs
                            );
                        } else if (invoke instanceof InterfaceInvokeExpr) {
                            newInvoke = Jimple.v().newInterfaceInvokeExpr(
                                    baseLocal,
                                    invoke.getMethod().makeRef(),
                                    newArgs
                            );
                        } else {
                            // Shouldn't happen for addListener, but handle just in case
                            continue;
                        }

                        stmt.setRightOp(newInvoke);
                    }
                } else if (u instanceof InvokeStmt) {
                    InvokeExpr invoke = ((InvokeStmt) u).getInvokeExpr();
                    if (shouldBeWrapped4AddListener(invoke)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping executor parameter in addListener invoke statement: {}", u);
                        PilotTransformer.ctxCount++;

                        // Get Context class
                        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

                        // Generate local for current context
                        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

                        // Get Context.current()
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        contextLocal,
                                        Jimple.v().newStaticInvokeExpr(
                                                contextClass.getMethod("current",
                                                        Collections.emptyList(),
                                                        RefType.v("io.opentelemetry.context.Context")
                                                ).makeRef()
                                        )
                                )
                        );

                        // Store original executor
                        Value executorArg = invoke.getArg(1); // executor is the second argument in addListener
                        Local tempExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(Jimple.v().newAssignStmt(tempExecutor, executorArg));

                        // Create wrapped executor using Context.wrap()
                        Local wrappedExecutor = lg.generateLocal(RefType.v("java.util.concurrent.Executor"));
                        newUnits.add(
                                Jimple.v().newAssignStmt(
                                        wrappedExecutor,
                                        Jimple.v().newInterfaceInvokeExpr(
                                                contextLocal,
                                                contextClass.getMethod("wrap",
                                                        Collections.singletonList(RefType.v("java.util.concurrent.Executor")),
                                                        RefType.v("java.util.concurrent.Executor")
                                                ).makeRef(),
                                                tempExecutor
                                        )
                                )
                        );

                        // Store base in a local
                        Local baseLocal = null;
                        if (invoke instanceof VirtualInvokeExpr || invoke instanceof InterfaceInvokeExpr) {
                            Value base = ((InstanceInvokeExpr) invoke).getBase();
                            baseLocal = lg.generateLocal(base.getType());
                            newUnits.add(Jimple.v().newAssignStmt(baseLocal, base));
                        }

                        units.insertBefore(newUnits, u);

                        // Create new invoke with wrapped executor
                        List<Value> newArgs = new ArrayList<>(invoke.getArgs());
                        newArgs.set(1, wrappedExecutor); // Replace executor argument

                        // Create appropriate invoke expression based on the original type
                        InvokeExpr newInvoke;
                        if (invoke instanceof VirtualInvokeExpr) {
                            newInvoke = Jimple.v().newVirtualInvokeExpr(
                                    baseLocal,
                                    invoke.getMethod().makeRef(),
                                    newArgs
                            );
                        } else if (invoke instanceof InterfaceInvokeExpr) {
                            newInvoke = Jimple.v().newInterfaceInvokeExpr(
                                    baseLocal,
                                    invoke.getMethod().makeRef(),
                                    newArgs
                            );
                        } else {
                            // Shouldn't happen for addListener, but handle just in case
                            continue;
                        }

                        ((InvokeStmt) u).setInvokeExpr(newInvoke);
                    }
                }
            }
        }

        private boolean shouldBeWrapped4AddListener(InvokeExpr invoke) {
            if (!(invoke instanceof InstanceInvokeExpr)) {
                return false;
            }

            SootMethod method = invoke.getMethod();

            // Check if this is an addListener call
            return method.getName().equals("addListener") &&
                    method.getParameterCount() == 2 &&
                    method.getParameterType(0).toString().equals("java.lang.Runnable") &&
                    method.getParameterType(1).toString().equals("java.util.concurrent.Executor") &&
                    (invoke.getMethod().getDeclaringClass().getName().equals("com.google.common.util.concurrent.ListenableFutureTask") ||
                            invoke.getMethod().getDeclaringClass().implementsInterface("com.google.common.util.concurrent.ListenableFuture"));
        }

        protected void wrapGoogleFuturesTransformAsync(SootMethod method) {
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
                    if (rightOp instanceof InvokeExpr && shouldBeWrapped4TransformAsync((InvokeExpr) rightOp)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping transform async function parameter in assignment: {}", stmt);
                        Local asyncFunctionParam = wrapAsyncFunction((InvokeExpr) rightOp, lg, body, newUnits);
                        units.insertBefore(newUnits, u);

                        StaticInvokeExpr newInvoke = getNewInvokeWithTracedAsyncFunction((InvokeExpr) rightOp, asyncFunctionParam);
                        stmt.setRightOp(newInvoke);
                    }
                } else if (u instanceof InvokeStmt) {
                    InvokeExpr invoke = ((InvokeStmt) u).getInvokeExpr();
                    if (shouldBeWrapped4TransformAsync(invoke)) {
                        List<Unit> newUnits = new ArrayList<>();
                        LOG.info("Wrapping transform async function parameter in invoke statement: {}", u);
                        Local asyncFunctionParam = wrapAsyncFunction(invoke, lg, body, newUnits);
                        units.insertBefore(newUnits, u);

                        StaticInvokeExpr newInvoke = getNewInvokeWithTracedAsyncFunction(invoke, asyncFunctionParam);
                        ((InvokeStmt) u).setInvokeExpr(newInvoke);
                    }
                }
            }
        }

        private boolean shouldBeWrapped4TransformAsync(InvokeExpr invoke) {
            if (!(invoke instanceof StaticInvokeExpr)) {
                return false;
            }

            SootMethod method = invoke.getMethod();
            return method.getDeclaringClass().getName().equals("com.google.common.util.concurrent.Futures")
                    && method.getName().equals("transformAsync")
                    && !invoke.getArgs().isEmpty();
        }

        private Local wrapAsyncFunction(InvokeExpr invoke, LocalGeneratorUtil lg, Body body, List<Unit> newUnits) {
            Value asyncFunctionArg = invoke.getArg(1);
            Local tempAsyncFunction = lg.generateLocal(asyncFunctionArg.getType());

            newUnits.add(Jimple.v().newAssignStmt(tempAsyncFunction, asyncFunctionArg));

            RefType asyncFunctionType = (RefType) asyncFunctionArg.getType();
            SootClass asyncFunctionClass = asyncFunctionType.getSootClass();

            setFlagForTrace(asyncFunctionClass, tempAsyncFunction, newUnits);

            return tempAsyncFunction;
        }

        private StaticInvokeExpr getNewInvokeWithTracedAsyncFunction(InvokeExpr originalInvoke, Local tracedAsyncFunction) {
            List<Value> newArgs = new ArrayList<>(originalInvoke.getArgs());
            newArgs.set(1, tracedAsyncFunction);

            return Jimple.v().newStaticInvokeExpr(
                    originalInvoke.getMethod().makeRef(),
                    newArgs
            );
        }

        private void setFlagForTrace(SootClass targetClass, Value instance, List<Unit> newUnits) {
            List<SootClass> classHierarchy = new ArrayList<>();
            SootClass currentClass = targetClass;

            while (currentClass != null && !currentClass.getName().equals("java.lang.Object")) {
                classHierarchy.add(0, currentClass);
                currentClass = currentClass.hasSuperclass() ? currentClass.getSuperclass() : null;
            }

            for (SootClass cls : classHierarchy) {
                String fieldName = getDryRunTraceFieldName(cls);

                if (!cls.declaresField(fieldName, BooleanType.v())) {
                    LOG.debug("Skipping class {} as it does not declare field {}",
                            cls.getName(), fieldName);
                    continue;
                }

                try {
                    SootField field = cls.getField(fieldName, BooleanType.v());
                    FieldRef dryRunTraceFieldRef = Jimple.v().newInstanceFieldRef(
                            instance,
                            field.makeRef()
                    );

                    Unit setDryRunTrace = Jimple.v().newAssignStmt(
                            dryRunTraceFieldRef,
                            IntConstant.v(1)
                    );

                    newUnits.add(setDryRunTrace);
                    LOG.debug("Successfully set dry run trace for class: {}", cls.getName());
                } catch (Exception e) {
                    LOG.error("Failed to set dry run trace for class: " + cls.getName(), e);
                }
            }
        }
    }




    private void wrapAssignStmtExecutorRunnableParameterWithContext(AssignStmt stmt, UnitPatchingChain units, LocalGeneratorUtil lg, Body body) {
        InvokeExpr invoke = (InvokeExpr) stmt.getRightOp();
        InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) invoke;

        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

        units.insertBefore(
                Jimple.v().newAssignStmt(
                        contextLocal,
                        Jimple.v().newStaticInvokeExpr(
                                contextClass.getMethod("current",
                                        Collections.emptyList(),
                                        RefType.v("io.opentelemetry.context.Context")
                                ).makeRef()
                        )
                ),
                stmt
        );

        // Get original runnable argument
        Value originalRunnable = instanceInvoke.getArg(0);

        // Create temporary local for the original runnable
        Local tempRunnable = lg.generateLocal(RefType.v("java.lang.Runnable"));
        units.insertBefore(
                Jimple.v().newAssignStmt(tempRunnable, originalRunnable),
                stmt
        );

        // Create local for the wrapped runnable
        Local wrappedRunnable = lg.generateLocal(RefType.v("java.lang.Runnable"));

        // Wrap the runnable with Context.wrap()
        units.insertBefore(
                Jimple.v().newAssignStmt(
                        wrappedRunnable,
                        Jimple.v().newInterfaceInvokeExpr(
                                contextLocal,
                                contextClass.getMethod("wrap",
                                        Collections.singletonList(RefType.v("java.lang.Runnable")),
                                        RefType.v("java.lang.Runnable")
                                ).makeRef(),
                                tempRunnable
                        )
                ),
                stmt
        );

        // Create local for the base to avoid potential side effects
        Local baseLocal = lg.generateLocal(instanceInvoke.getBase().getType());
        units.insertBefore(
                Jimple.v().newAssignStmt(baseLocal, instanceInvoke.getBase()),
                stmt
        );

        // Update arguments list
        List<Value> newArgs = new ArrayList<>(instanceInvoke.getArgs());
        newArgs.set(0, wrappedRunnable);

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

        // Set the new invoke expression as the right operand of the assignment
        stmt.setRightOp(newInvoke);

        try {
            body.validate();
        } catch (Exception e) {
            LOG.error("Failed to validate body after wrapping executor parameter in assignment", e);
            throw e;
        }
    }


    private void wrapExecutorFutureTaskParameterWithContext(InvokeStmt stmt, UnitPatchingChain units, LocalGeneratorUtil lg, Body body) {
        InvokeExpr invoke = stmt.getInvokeExpr();
        InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) invoke;

        // Get Context class
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");

        // Generate local for current context
        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

        // Get Context.current()
        units.insertBefore(
                Jimple.v().newAssignStmt(
                        contextLocal,
                        Jimple.v().newStaticInvokeExpr(
                                contextClass.getMethod("current",
                                        Collections.emptyList(),
                                        RefType.v("io.opentelemetry.context.Context")
                                ).makeRef()
                        )
                ),
                stmt
        );

        // Get original runnable argument
        Value originalRunnable = instanceInvoke.getArg(0);

        // Create temporary local for the original runnable to avoid potential side effects
        Local tempRunnable = lg.generateLocal(RefType.v("java.lang.Runnable"));
        units.insertBefore(
                Jimple.v().newAssignStmt(tempRunnable, originalRunnable),
                stmt
        );

        // Create local for the wrapped runnable
        Local wrappedRunnable = lg.generateLocal(RefType.v("java.lang.Runnable"));

        // Wrap the runnable with Context.wrap()
        units.insertBefore(
                Jimple.v().newAssignStmt(
                        wrappedRunnable,
                        Jimple.v().newInterfaceInvokeExpr(
                                contextLocal,
                                contextClass.getMethod("wrap",
                                        Collections.singletonList(RefType.v("java.lang.Runnable")),
                                        RefType.v("java.lang.Runnable")
                                ).makeRef(),
                                tempRunnable
                        )
                ),
                stmt
        );

        // Create local for the base to avoid potential side effects
        Local baseLocal = lg.generateLocal(instanceInvoke.getBase().getType());
        units.insertBefore(
                Jimple.v().newAssignStmt(baseLocal, instanceInvoke.getBase()),
                stmt
        );

        // Update arguments list
        List<Value> newArgs = new ArrayList<>(instanceInvoke.getArgs());
        newArgs.set(0, wrappedRunnable);

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

        // Replace original invoke expression
        stmt.getInvokeExprBox().setValue(newInvoke);

        try {
            body.validate();
        } catch (Exception e) {
            LOG.error("Failed to validate body after wrapping executor parameter", e);
            throw e;
        }
    }



    private boolean shouldBeWrapped4FutureTask(InvokeExpr invoke){
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

    public void injectBaggageInMethod(SootMethod method){
        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for(Unit u: originalUnits){
            if(u instanceof AssignStmt){
                AssignStmt stmt = (AssignStmt) u;
                Value rightOp = stmt.getRightOp();

                if(isFutureType(rightOp.getType())){
                    wrapWithContext(stmt, units, u, lg);
                }
            }
        }

    }

    private boolean isFutureType(Type type) {
        if (!(type instanceof RefType)) {
            return false;
        }
        String typeName = ((RefType) type).getSootClass().getName();
        for (String futureType : FUTURE_TYPES) {
            if (typeName.contains(futureType)) {
                return true;
            }
        }
        return false;
    }

    private void wrapWithContext(AssignStmt stmt, UnitPatchingChain units, Unit unit, LocalGeneratorUtil lg) {
        
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");
        Local contextLocal = lg.generateLocal(RefType.v(contextClass));

        units.insertBefore(
                Jimple.v().newAssignStmt(
                        contextLocal,
                        Jimple.v().newStaticInvokeExpr(
                                contextClass.getMethod("current",
                                        Collections.emptyList(),
                                        RefType.v("io.opentelemetry.context.Context")
                                ).makeRef()
                        )
                ),
                unit
        );

        // Call wrap method
        Value originalFuture = stmt.getRightOp();

        Local tempFuture = lg.generateLocal(RefType.v("java.lang.Runnable"));
        units.insertBefore(
                Jimple.v().newAssignStmt(tempFuture, originalFuture),
                unit
        );

        Local wrappedFuture = lg.generateLocal(RefType.v("java.lang.Runnable"));
        units.insertBefore(
                Jimple.v().newAssignStmt(
                        wrappedFuture,
                        Jimple.v().newInterfaceInvokeExpr(
                                contextLocal,
                                contextClass.getMethod("wrap",
                                        Collections.singletonList(RefType.v("java.lang.Runnable")),
                                        RefType.v("java.lang.Runnable")
                                ).makeRef(),
                                tempFuture
                        )
                ),
                unit
        );
        stmt.setRightOp(
                wrappedFuture
        );
    }

}
