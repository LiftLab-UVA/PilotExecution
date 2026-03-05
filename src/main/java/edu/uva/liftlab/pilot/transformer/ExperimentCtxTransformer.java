package edu.uva.liftlab.pilot.transformer;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import edu.uva.liftlab.pilot.util.SootUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;

import static edu.uva.liftlab.pilot.util.Constants.INSTRUMENTATION_SUFFIX;

public class ExperimentCtxTransformer {
    private static final Logger LOG = LoggerFactory.getLogger(ExperimentCtxTransformer.class);
    private static final String TRACE_RECORDER_CLASS = "org.pilot.trace.TraceRecorder";
    public static final String ENTRY_RECOVERY_POINT = "repairAsync";
    public static final String PILOT_CONTEXT_TRACKING_CLASS = "org.pilot.trace.PilotContextTracking";
    public Set<SootClass> sootClass = new HashSet<>();

    private final ClassFilterHelper classFilterHelper;

    public ExperimentCtxTransformer(ClassFilterHelper classFilterHelper) {
        this.classFilterHelper = classFilterHelper;

    }

    public void transform(){
        transformInitClass();
        SootUtils.getShouldInstrumentedMethodForCtxTree(classFilterHelper);
        transformPilotExecution();
    }


    public void transformInitClass() {
        for (String str : this.classFilterHelper.trackInitClasses) {
            this.sootClass.add(Scene.v().getSootClass(str));
        }

        for (SootClass sc : this.sootClass) {
            SootMethod clinit;

            if (sc.declaresMethodByName("<clinit>")) {
                clinit = sc.getMethodByName("<clinit>");
            } else {
                clinit = new SootMethod("<clinit>",
                        Collections.emptyList(),
                        VoidType.v(),
                        Modifier.STATIC);
                sc.addMethod(clinit);
                JimpleBody body = Jimple.v().newBody(clinit);
                clinit.setActiveBody(body);
                body.getUnits().add(Jimple.v().newReturnVoidStmt());
            }

            Body body = clinit.retrieveActiveBody();
            PatchingChain<Unit> units = body.getUnits();


            SootClass pilotClass = Scene.v().getSootClass(TRACE_RECORDER_CLASS);
            SootMethod initMethod = pilotClass.getMethod("void initializeOpenTelemetry()");
            InvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(initMethod.makeRef());
            Unit invokeStmt = Jimple.v().newInvokeStmt(invokeExpr);


            Unit insertPoint = null;
            for (Unit unit : units) {
                if (!(unit instanceof IdentityStmt)) {
                    insertPoint = unit;
                    break;
                }
            }

            if (insertPoint != null) {
                units.insertBefore(invokeStmt, insertPoint);
            } else {
                units.insertBefore(invokeStmt, units.getLast());
            }

            body.validate();
        }
    }

    public void transformPilotExecution() {
        Set<SootMethod> instrumentedMethods = SootUtils.shouldInstrumentedMethods;

        for (SootMethod originalMethod : instrumentedMethods) {
            try {
                String instrumentedMethodName = originalMethod.getName() + INSTRUMENTATION_SUFFIX;
                SootClass declaringClass = originalMethod.getDeclaringClass();

                
                String subSignature = originalMethod.getSubSignature()
                        .replace(originalMethod.getName(), instrumentedMethodName);

                
                SootMethod instrumentedMethod = declaringClass.getMethodUnsafe(subSignature);

                if (instrumentedMethod != null) {
                    LOG.info("Found instrumented method: {} for original: {}",
                            instrumentedMethod.getSignature(), originalMethod.getSignature());
                    instrumentMethodWithSpan(instrumentedMethod);
                    LOG.info("Successfully instrumented method with span: {}",
                            instrumentedMethod.getSignature());
                } else {
                    LOG.warn("Could not find instrumented version of method: {} with suffix: {}",
                            originalMethod.getSignature(), INSTRUMENTATION_SUFFIX);
                }
            } catch (Exception e) {
                LOG.error("Failed to transform pilot execution for method {}: {}",
                        originalMethod.getSignature(), e.getMessage(), e);
            }
        }

        LOG.info("Completed pilot execution transformation");
    }

    public void transformOriginalFunction() {
//        for(SootClass sc: Scene.v().getApplicationClasses()){
//            // Skip interfaces and phantom classes
//            if(this.classFilterHelper.isBlackListClass(sc) && !this.classFilterHelper.isTraceClass(sc) && !this.classFilterHelper.isIsolateClass(sc)){
//                continue;
//            }
//            BaggagePropagation baggagePropagation = new BaggagePropagation(sc,this.classFilterHelper);
//            baggagePropagation.propagateContextExperiment();
//        }

        for(SootMethod sootMethod: SootUtils.shouldInstrumentedMethods){
            try {
                // First instrument method calls
                //instrumentMethodCalls(method);
                // Then add span creation logic
                instrumentMethodWithSpan(sootMethod);
            } catch (Exception e) {
                LOG.warn("Failed to instrument method {}: {}", sootMethod.getSignature(), e.getMessage());
            }
        }
    }


    /**
     * Add Span creation and ending logic to method - WITHOUT exception handling
     */


    /**
     * Get Tracer
     */
    private Local getTracer(LocalGeneratorUtil lg, List<Unit> units) {
        SootClass globalOtel = Scene.v().getSootClass("io.opentelemetry.api.GlobalOpenTelemetry");
        Local otel = lg.generateLocal(RefType.v("io.opentelemetry.api.OpenTelemetry"));

        units.add(Jimple.v().newAssignStmt(
                otel,
                Jimple.v().newStaticInvokeExpr(
                        globalOtel.getMethod("get", Collections.emptyList()).makeRef()
                )
        ));

        Local tracerName = lg.generateLocal(RefType.v("java.lang.String"));
        units.add(Jimple.v().newAssignStmt(
                tracerName,
                StringConstant.v("experiment-tracer")
        ));

        Local tracer = lg.generateLocal(RefType.v("io.opentelemetry.api.trace.Tracer"));
        units.add(Jimple.v().newAssignStmt(
                tracer,
                Jimple.v().newInterfaceInvokeExpr(
                        otel,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.api.OpenTelemetry"),
                                "getTracer",
                                Collections.singletonList(RefType.v("java.lang.String")),
                                RefType.v("io.opentelemetry.api.trace.Tracer"),
                                false
                        ),
                        tracerName
                )
        ));

        return tracer;
    }

    /**
     * Record method entry
     */
    private void recordMethodEntry(Local traceId, Local spanId, Local methodName,
                                   LocalGeneratorUtil lg, List<Unit> units) {
        SootClass recorderClass = Scene.v().getSootClass(TRACE_RECORDER_CLASS);

        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(
                        recorderClass.getMethod("recordMethodEntry",
                                Arrays.asList(
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String")
                                ),
                                VoidType.v()
                        ).makeRef(),
                        Arrays.asList(traceId, spanId, methodName)
                )
        ));
    }

    /**
     * Record parent-child span relationship
     */
    private void recordSpanRelation(Local traceId, Local parentSpanId, Local childSpanId,
                                    Local methodName, LocalGeneratorUtil lg, List<Unit> units) {
        SootClass recorderClass = Scene.v().getSootClass(TRACE_RECORDER_CLASS);

        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(
                        recorderClass.getMethod("recordSpanRelationForPilotExecution",
                                Arrays.asList(
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String")
                                ),
                                VoidType.v()
                        ).makeRef(),
                        Arrays.asList(traceId, parentSpanId, childSpanId, methodName)
                )
        ));
    }

    private void instrumentMethodCalls(SootMethod method) {
        if (!method.hasActiveBody()) return;

        Body body = method.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        List<Unit> originalUnits = new ArrayList<>(units);

        for (Unit unit : originalUnits) {
            InvokeExpr invoke = null;

            if (unit instanceof InvokeStmt) {
                invoke = ((InvokeStmt) unit).getInvokeExpr();
            } else if (unit instanceof AssignStmt) {
                Value rightOp = ((AssignStmt) unit).getRightOp();
                if (rightOp instanceof InvokeExpr) {
                    invoke = (InvokeExpr) rightOp;
                }
            }

            if (invoke != null && shouldInstrumentCall(invoke)) {
                try {
                    List<Unit> beforeUnits = new ArrayList<>();
                    List<Unit> afterUnits = new ArrayList<>();

                    Local parentSpan = getSpanCurrent(lg, beforeUnits);
                    Local parentContext = getSpanContext(parentSpan, lg, beforeUnits);

                    Local parentMethod = lg.generateLocal(RefType.v("java.lang.String"));
                    beforeUnits.add(Jimple.v().newAssignStmt(
                            parentMethod,
                            StringConstant.v(method.getDeclaringClass().getName() + "." + method.getName())
                    ));

                    Local parentSpanId = getSpanId(parentContext, lg, beforeUnits);

                    units.insertBefore(beforeUnits, unit);

                    Local childSpan = getSpanCurrent(lg, afterUnits);
                    Local childContext = getSpanContext(childSpan, lg, afterUnits);
                    Local childSpanId = getSpanId(childContext, lg, afterUnits);

                    recordRelation(parentContext, parentSpanId, childSpanId,
                            parentMethod, invoke, "METHOD_CALL", lg, afterUnits);

                    units.insertAfter(afterUnits, unit);
                } catch (Exception e) {
                    LOG.warn("Failed to instrument call in method {}: {}", method.getSignature(), e.getMessage());
                }
            }
        }
    }

    private void recordRelation(Local parentContext, Local parentSpanId, Local childSpanId,
                                Local parentMethod, InvokeExpr childInvoke, String edgeType,
                                LocalGeneratorUtil lg, List<Unit> units) {
        SootClass recorderClass = Scene.v().getSootClass(TRACE_RECORDER_CLASS);

        Local traceId = getTraceId(parentContext, lg, units);
        Local childMethod = lg.generateLocal(RefType.v("java.lang.String"));
        Local edge = lg.generateLocal(RefType.v("java.lang.String"));

        units.add(Jimple.v().newAssignStmt(
                childMethod,
                StringConstant.v(childInvoke.getMethod().getDeclaringClass().getName() +
                        "." + childInvoke.getMethod().getName())
        ));

        units.add(Jimple.v().newAssignStmt(
                edge,
                StringConstant.v(edgeType)
        ));

        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newStaticInvokeExpr(
                        recorderClass.getMethod("recordRelation",
                                Arrays.asList(
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String"),
                                        RefType.v("java.lang.String")
                                ),
                                VoidType.v()
                        ).makeRef(),
                        Arrays.asList(traceId, parentSpanId, childSpanId,
                                parentMethod, childMethod, edge)
                )
        ));
    }

    // Helper methods
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

    private boolean shouldInstrumentCall(InvokeExpr invoke) {
        SootMethod method = invoke.getMethod();
        String className = method.getDeclaringClass().getName();

        // Skip system classes
        if (className.startsWith("java.") ||
                className.startsWith("sun.") ||
                className.startsWith("io.opentelemetry.") ||
                className.startsWith("org.pilot.trace.")) {
            return false;
        }

        // Skip constructors
        if (method.getName().equals("<init>") || method.getName().equals("<clinit>")) {
            return false;
        }

        // Skip methods with generics
        String signature = method.getSignature();
        if (signature.contains("<") && signature.contains(">") && !signature.contains("<init>") && !signature.contains("<clinit>")) {
            return false;
        }


        return true;
    }
    private void instrumentMethodWithSpan(SootMethod method) {
        if (!method.hasActiveBody()) return;

        // Skip constructors
        if (method.getName().equals("<init>") || method.getName().equals("<clinit>")) {
            return;
        }

        Body body = method.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        // Variables needed throughout the method
        Local span = lg.generateLocal(RefType.v("io.opentelemetry.api.trace.Span"));
        Local scope = lg.generateLocal(RefType.v("io.opentelemetry.context.Scope"));


        Unit firstNonIdentity = SootUtils.getFirstNonIdentityStmt(body);

        
        List<Unit> initUnits = new ArrayList<>();
        initUnits.add(Jimple.v().newAssignStmt(span, NullConstant.v()));
        initUnits.add(Jimple.v().newAssignStmt(scope, NullConstant.v()));

        
        List<Unit> spanCreateUnits = new ArrayList<>();

        // Get current span as parent
        Local parentSpan = getSpanCurrent(lg, spanCreateUnits);
        Local parentContext = getSpanContext(parentSpan, lg, spanCreateUnits);
        Local parentSpanId = getSpanId(parentContext, lg, spanCreateUnits);

        // Get Tracer
        Local tracer = getTracer(lg, spanCreateUnits);

        // Create SpanBuilder
        Local spanBuilder = lg.generateLocal(RefType.v("io.opentelemetry.api.trace.SpanBuilder"));
        Local methodName = lg.generateLocal(RefType.v("java.lang.String"));
        spanCreateUnits.add(Jimple.v().newAssignStmt(
                methodName,
                StringConstant.v(method.getDeclaringClass().getName() + "." + method.getName())
        ));

        spanCreateUnits.add(Jimple.v().newAssignStmt(
                spanBuilder,
                Jimple.v().newInterfaceInvokeExpr(
                        tracer,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.api.trace.Tracer"),
                                "spanBuilder",
                                Collections.singletonList(RefType.v("java.lang.String")),
                                RefType.v("io.opentelemetry.api.trace.SpanBuilder"),
                                false
                        ),
                        methodName
                )
        ));

        // Set parent context
        SootClass contextClass = Scene.v().getSootClass("io.opentelemetry.context.Context");
        Local currentContext = lg.generateLocal(RefType.v(contextClass));

        spanCreateUnits.add(Jimple.v().newAssignStmt(
                currentContext,
                Jimple.v().newStaticInvokeExpr(
                        contextClass.getMethod("current", Collections.emptyList()).makeRef()
                )
        ));

        spanCreateUnits.add(Jimple.v().newAssignStmt(
                spanBuilder,
                Jimple.v().newInterfaceInvokeExpr(
                        spanBuilder,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.api.trace.SpanBuilder"),
                                "setParent",
                                Collections.singletonList(RefType.v("io.opentelemetry.context.Context")),
                                RefType.v("io.opentelemetry.api.trace.SpanBuilder"),
                                false
                        ),
                        currentContext
                )
        ));

        // Start span
        spanCreateUnits.add(Jimple.v().newAssignStmt(
                span,
                Jimple.v().newInterfaceInvokeExpr(
                        spanBuilder,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.api.trace.SpanBuilder"),
                                "startSpan",
                                Collections.emptyList(),
                                RefType.v("io.opentelemetry.api.trace.Span"),
                                false
                        )
                )
        ));

        // Make current
        spanCreateUnits.add(Jimple.v().newAssignStmt(
                scope,
                Jimple.v().newInterfaceInvokeExpr(
                        span,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.api.trace.Span"),
                                "makeCurrent",
                                Collections.emptyList(),
                                RefType.v("io.opentelemetry.context.Scope"),
                                false
                        )
                )
        ));

        // Record span relation
        Local spanContext = getSpanContext(span, lg, spanCreateUnits);
        Local spanId = getSpanId(spanContext, lg, spanCreateUnits);
        Local traceId = getTraceId(spanContext, lg, spanCreateUnits);
        recordSpanRelation(traceId, parentSpanId, spanId, methodName, lg, spanCreateUnits);

        
        initUnits.addAll(spanCreateUnits);
        Unit lastIdentity = SootUtils.getLastIdentityUnit(body);
        if(lastIdentity == null) {
            units.insertBefore(initUnits, firstNonIdentity);
        } else {
            units.insertAfter(initUnits, lastIdentity);
        }

        
        for (Trap trap : body.getTraps()) {
            Unit handlerUnit = trap.getHandlerUnit();

            
            Unit insertPoint = handlerUnit;
            Unit currentUnit = handlerUnit;

            
            while (currentUnit != null && currentUnit instanceof IdentityStmt) {
                Unit nextUnit = units.getSuccOf(currentUnit);
                if (nextUnit != null) {
                    insertPoint = nextUnit;
                    currentUnit = nextUnit;
                } else {
                    break;
                }
            }

            
            List<Unit> cleanupUnits = createSafeCleanupUnits(scope, span, lg);
            units.insertBefore(cleanupUnits, insertPoint);
        }

        
        List<Unit> returnStmts = new ArrayList<>();
        for (Unit u : units) {
            if (u instanceof ReturnStmt || u instanceof ReturnVoidStmt) {
                returnStmts.add(u);
            }
        }

        for (Unit ret : returnStmts) {
            List<Unit> cleanupUnits = createSafeCleanupUnits(scope, span, lg);
            units.insertBefore(cleanupUnits, ret);
        }

        
        if (method.getReturnType() instanceof VoidType && returnStmts.isEmpty()) {
            Unit lastUnit = null;
            for (Unit u : units) {
                if (!(u instanceof ReturnStmt || u instanceof ReturnVoidStmt)) {
                    lastUnit = u;
                }
            }

            if (lastUnit != null) {
                List<Unit> cleanupUnits = createSafeCleanupUnits(scope, span, lg);
                units.insertAfter(cleanupUnits, lastUnit);
                units.add(Jimple.v().newReturnVoidStmt());
            }
        }

        //validateVariables(body);
    }

    // The createSafeCleanupUnits method remains the same
    private List<Unit> createSafeCleanupUnits(Local scope, Local span, LocalGeneratorUtil lg) {
        List<Unit> cleanupUnits = new ArrayList<>();

        // Create labels for null checks
        Unit scopeNullLabel = Jimple.v().newNopStmt();
        Unit spanNullLabel = Jimple.v().newNopStmt();

        // Check if scope is not null before closing
        cleanupUnits.add(Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(scope, NullConstant.v()),
                scopeNullLabel
        ));

        // scope.close()
        cleanupUnits.add(Jimple.v().newInvokeStmt(
                Jimple.v().newInterfaceInvokeExpr(
                        scope,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.context.Scope"),
                                "close",
                                Collections.emptyList(),
                                VoidType.v(),
                                false
                        )
                )
        ));

        // Add the null label for scope
        cleanupUnits.add(scopeNullLabel);

        // Check if span is not null before ending
        cleanupUnits.add(Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(span, NullConstant.v()),
                spanNullLabel
        ));

        // span.end()
        cleanupUnits.add(Jimple.v().newInvokeStmt(
                Jimple.v().newInterfaceInvokeExpr(
                        span,
                        Scene.v().makeMethodRef(
                                Scene.v().getSootClass("io.opentelemetry.api.trace.Span"),
                                "end",
                                Collections.emptyList(),
                                VoidType.v(),
                                false
                        )
                )
        ));

        // Add the null label for span
        cleanupUnits.add(spanNullLabel);

        return cleanupUnits;
    }
}