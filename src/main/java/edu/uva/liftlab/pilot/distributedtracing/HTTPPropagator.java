package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.isolation.stateredirection.ClassFilterHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static edu.uva.liftlab.pilot.util.Constants.PILOT_UTIL_CLASS_NAME;

public class HTTPPropagator {
    private static final Logger LOG = LoggerFactory.getLogger(HTTPPropagator.class);
    ClassFilterHelper filterHelper;

    public HTTPPropagator(ClassFilterHelper filterHelper) {
        this.filterHelper = filterHelper;
    }

    public void injectCtxHooks(){
        addPilotContextHeader();
        instrumentRecv();
    }

    public void instrumentRecv(){
        for(String className: filterHelper.httpRecvClasses){
            try {
                SootClass sootClass = Scene.v().getSootClass(className);
                LOG.info("Instrumenting class for HTTP receive: {}", className);

                // Skip if class is phantom or interface
                if (sootClass.isPhantom() || sootClass.isInterface()) {
                    continue;
                }

                // Look for the doFilter method specifically
                SootMethod doFilterMethod = null;

                // Try to find doFilter method with the standard Filter signature
                try {
                    // Standard servlet filter signature: void doFilter(ServletRequest, ServletResponse, FilterChain)
                    List<Type> paramTypes = Arrays.asList(
                            RefType.v("javax.servlet.ServletRequest"),
                            RefType.v("javax.servlet.ServletResponse"),
                            RefType.v("javax.servlet.FilterChain")
                    );
                    doFilterMethod = sootClass.getMethod("doFilter", paramTypes, VoidType.v());
                } catch (RuntimeException e) {
                    // Method not found with exact signature, try alternate approaches

                    // Look for any method named doFilter
                    for (SootMethod method : sootClass.getMethods()) {
                        if (method.getName().equals("doFilter") && method.isConcrete()) {
                            doFilterMethod = method;
                            break;
                        }
                    }
                }

                // If we found a doFilter method, instrument it
                if (doFilterMethod != null && doFilterMethod.isConcrete()) {
                    LOG.info("Found doFilter method in class: {}", className);
                    instrumentDoFilter(doFilterMethod);
                } else {
                    LOG.warn("No concrete doFilter method found in class: {}", className);
                }

            } catch (Exception e) {
                LOG.error("Error processing class " + className, e);
            }
        }
    }

    public void addPilotContextHeader() {
        // Iterate through each class in httpSendClasses
        for (String className : filterHelper.httpSendClasses) {
            try {
                SootClass sootClass = Scene.v().getSootClass(className);

                // Skip if class is phantom or interface
                if (sootClass.isPhantom() || sootClass.isInterface()) {
                    continue;
                }

                // Iterate through all methods in the class
                for (SootMethod method : sootClass.getMethods()) {
                    if (method.isConcrete()) {
                        instrumentMethod(method);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error processing class " + className, e);
            }
        }
    }

    private void instrumentMethod(SootMethod method) {
        Body body = method.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();

        // Find httpClient.execute(method, httpClientRequestContext) calls
        List<Unit> executeCallsToInstrument = new ArrayList<>();

        for (Unit unit : units) {
            if (unit instanceof InvokeStmt) {
                InvokeStmt invokeStmt = (InvokeStmt) unit;
                InvokeExpr invokeExpr = invokeStmt.getInvokeExpr();

                if (isHttpClientExecuteCall(invokeExpr)) {
                    executeCallsToInstrument.add(unit);
                }
            } else if (unit instanceof AssignStmt) {
                AssignStmt assignStmt = (AssignStmt) unit;
                Value rightOp = assignStmt.getRightOp();

                if (rightOp instanceof InvokeExpr && isHttpClientExecuteCall((InvokeExpr) rightOp)) {
                    executeCallsToInstrument.add(unit);
                }
            }
        }

        // Instrument each found execute call
        for (Unit executeCall : executeCallsToInstrument) {
            insertHeaderAddition(body, units, executeCall);
        }

        // Validate the body after instrumentation
        body.validate();
    }

    private boolean isHttpClientExecuteCall(InvokeExpr invokeExpr) {
        SootMethod targetMethod = invokeExpr.getMethod();

        // Check if it's httpClient.execute method
        if (targetMethod.getName().equals("execute") &&
                targetMethod.getParameterCount() >= 2) {

            // Check if the declaring class is HttpClient or its subclass
            String declaringClass = targetMethod.getDeclaringClass().getName();
            if (declaringClass.contains("HttpClient") ||
                    declaringClass.equals("org.apache.http.client.HttpClient")) {

                // Check if first parameter is HttpRequestBase or similar
                Type firstParamType = targetMethod.getParameterType(0);
                if (firstParamType.toString().contains("HttpRequestBase") ||
                        firstParamType.toString().contains("HttpUriRequest") ||
                        firstParamType.toString().contains("HttpRequest")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void insertHeaderAddition(Body body, PatchingChain<Unit> units, Unit executeCall) {
        // Get the first argument (method parameter) from the execute call
        InvokeExpr executeInvokeExpr = null;
        if (executeCall instanceof InvokeStmt) {
            executeInvokeExpr = ((InvokeStmt) executeCall).getInvokeExpr();
        } else if (executeCall instanceof AssignStmt) {
            Value rightOp = ((AssignStmt) executeCall).getRightOp();
            if (rightOp instanceof InvokeExpr) {
                executeInvokeExpr = (InvokeExpr) rightOp;
            }
        }

        if (executeInvokeExpr == null || executeInvokeExpr.getArgCount() < 1) {
            return;
        }

        Value methodParam = executeInvokeExpr.getArg(0);

        // Create the instrumentation statements
        Jimple jimple = Jimple.v();

        // 1. Get PilotID
        SootClass pilotUtilClass = Scene.v().getSootClass(PILOT_UTIL_CLASS_NAME);
        SootMethod getPilotIDMethod = pilotUtilClass.getMethod("int getPilotID()");

        Local pilotIDLocal = jimple.newLocal("$pilotID", IntType.v());
        body.getLocals().add(pilotIDLocal);

        StaticInvokeExpr getPilotIDExpr = jimple.newStaticInvokeExpr(getPilotIDMethod.makeRef());
        AssignStmt getPilotIDStmt = jimple.newAssignStmt(pilotIDLocal, getPilotIDExpr);

        // Convert int to String
        SootClass stringClass = Scene.v().getSootClass("java.lang.String");
        SootMethod valueOfMethod = stringClass.getMethod("java.lang.String valueOf(int)");

        Local pilotIDStringLocal = jimple.newLocal("$pilotIDString", RefType.v("java.lang.String"));
        body.getLocals().add(pilotIDStringLocal);

        StaticInvokeExpr valueOfExpr = jimple.newStaticInvokeExpr(
                valueOfMethod.makeRef(),
                pilotIDLocal
        );
        AssignStmt toStringStmt = jimple.newAssignStmt(pilotIDStringLocal, valueOfExpr);

        // 2. Get current span and its ID
        // Get current span
        SootClass spanClass = Scene.v().getSootClass("io.opentelemetry.api.trace.Span");
        Local currentSpan = jimple.newLocal("$currentSpan", RefType.v(spanClass));
        body.getLocals().add(currentSpan);

        StaticInvokeExpr getCurrentSpanExpr = jimple.newStaticInvokeExpr(
                spanClass.getMethod("current", Collections.emptyList()).makeRef()
        );
        AssignStmt getCurrentSpanStmt = jimple.newAssignStmt(currentSpan, getCurrentSpanExpr);

        // Get span context
        SootClass spanContextClass = Scene.v().getSootClass("io.opentelemetry.api.trace.SpanContext");
        Local spanContext = jimple.newLocal("$spanContext", RefType.v(spanContextClass));
        body.getLocals().add(spanContext);

        InterfaceInvokeExpr getSpanContextExpr = jimple.newInterfaceInvokeExpr(
                currentSpan,
                spanClass.getMethod("getSpanContext", Collections.emptyList()).makeRef()
        );
        AssignStmt getSpanContextStmt = jimple.newAssignStmt(spanContext, getSpanContextExpr);

        // Get span ID
        Local spanId = jimple.newLocal("$spanId", RefType.v("java.lang.String"));
        body.getLocals().add(spanId);

        InterfaceInvokeExpr getSpanIdExpr = jimple.newInterfaceInvokeExpr(
                spanContext,
                spanContextClass.getMethod("getSpanId", Collections.emptyList()).makeRef()
        );
        AssignStmt getSpanIdStmt = jimple.newAssignStmt(spanId, getSpanIdExpr);

        // 3. Add headers to HTTP request
        try {
            // Find addHeader method
            SootMethod addHeaderMethod = null;
            String[] possibleClasses = {
                    "org.apache.http.client.methods.HttpRequestBase",
                    "org.apache.http.message.AbstractHttpMessage",
                    "org.apache.http.HttpMessage"
            };

            for (String possibleClass : possibleClasses) {
                try {
                    SootClass httpClass = Scene.v().getSootClass(possibleClass);
                    addHeaderMethod = httpClass.getMethod("void addHeader(java.lang.String,java.lang.String)");
                    break;
                } catch (Exception e) {
                    // Try next class
                }
            }

            if (addHeaderMethod == null) {
                LOG.error("Could not find addHeader method");
                return;
            }

            // Add PilotID header
            VirtualInvokeExpr addPilotIDHeaderExpr = jimple.newVirtualInvokeExpr(
                    (Local) methodParam,
                    addHeaderMethod.makeRef(),
                    StringConstant.v("PilotID"),
                    pilotIDStringLocal
            );
            InvokeStmt addPilotIDHeaderStmt = jimple.newInvokeStmt(addPilotIDHeaderExpr);

            // Add SpanID header
            VirtualInvokeExpr addSpanIDHeaderExpr = jimple.newVirtualInvokeExpr(
                    (Local) methodParam,
                    addHeaderMethod.makeRef(),
                    StringConstant.v("SpanID"),
                    spanId
            );
            InvokeStmt addSpanIDHeaderStmt = jimple.newInvokeStmt(addSpanIDHeaderExpr);

            // Insert all statements before the execute call
            units.insertBefore(getPilotIDStmt, executeCall);
            units.insertBefore(toStringStmt, executeCall);
            units.insertBefore(getCurrentSpanStmt, executeCall);
            units.insertBefore(getSpanContextStmt, executeCall);
            units.insertBefore(getSpanIdStmt, executeCall);
            units.insertBefore(addPilotIDHeaderStmt, executeCall);
            units.insertBefore(addSpanIDHeaderStmt, executeCall);

        } catch (Exception e) {
            LOG.error("Error adding header instrumentation", e);
        }
    }

    private void instrumentDoFilter(SootMethod method) {
        if (!method.hasActiveBody()) return;

        Body body = method.getActiveBody();
        PatchingChain<Unit> units = body.getUnits();

        // Create locals for scope and span
        Jimple jimple = Jimple.v();
        SootClass scopeClass = Scene.v().getSootClass("io.opentelemetry.context.Scope");
        Local scope = jimple.newLocal("$scope", RefType.v(scopeClass));
        body.getLocals().add(scope);

        SootClass spanClass = Scene.v().getSootClass("io.opentelemetry.api.trace.Span");
        Local currentSpan = jimple.newLocal("$currentSpan", RefType.v(spanClass));
        body.getLocals().add(currentSpan);

        // Find the first non-identity statement (where actual code starts)
        Unit firstNonIdentity = null;
        for (Unit u : units) {
            if (!(u instanceof IdentityStmt)) {
                firstNonIdentity = u;
                break;
            }
        }

        // Find request parameter from identity statements
        Local requestParam = null;
        for (Unit u : units) {
            if (u instanceof IdentityStmt) {
                IdentityStmt idStmt = (IdentityStmt) u;
                if (idStmt.getRightOp() instanceof ParameterRef) {
                    ParameterRef paramRef = (ParameterRef) idStmt.getRightOp();
                    if (paramRef.getIndex() == 0) { // First parameter is request
                        requestParam = (Local) idStmt.getLeftOp();
                        break;
                    }
                }
            }
        }

        // Insert initialization at the beginning
        List<Unit> initUnits = new ArrayList<>();

        // Initialize to null for safety
        initUnits.add(jimple.newAssignStmt(scope, NullConstant.v()));
        initUnits.add(jimple.newAssignStmt(currentSpan, NullConstant.v()));

        // Call PilotUtil.getContextFromHTTP(request)
        SootClass pilotUtilClass = Scene.v().getSootClass("org.pilot.PilotUtil");
        StaticInvokeExpr getContextExpr = jimple.newStaticInvokeExpr(
                pilotUtilClass.getMethod("getContextFromHTTP",
                        Arrays.asList(RefType.v("javax.servlet.ServletRequest")),
                        RefType.v(scopeClass)
                ).makeRef(),
                requestParam
        );
        initUnits.add(jimple.newAssignStmt(scope, getContextExpr));

        // Get current span
        StaticInvokeExpr getCurrentSpanExpr = jimple.newStaticInvokeExpr(
                spanClass.getMethod("current", Collections.emptyList()).makeRef()
        );
        initUnits.add(jimple.newAssignStmt(currentSpan, getCurrentSpanExpr));

        // Insert at the beginning of the method
        units.insertBefore(initUnits, firstNonIdentity);

        // Add cleanup before all return statements
        List<Unit> returnStmts = new ArrayList<>();
        for (Unit u : units) {
            if (u instanceof ReturnStmt || u instanceof ReturnVoidStmt) {
                returnStmts.add(u);
            }
        }

        for (Unit ret : returnStmts) {
            units.insertBefore(createDoFilterCleanupUnits(scope, currentSpan, scopeClass, spanClass), ret);
        }

        // Add cleanup at the beginning of all exception handlers
        for (Trap trap : body.getTraps()) {
            Unit handlerUnit = trap.getHandlerUnit();

            // Find first non-identity statement in the handler
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

            units.insertBefore(createDoFilterCleanupUnits(scope, currentSpan, scopeClass, spanClass), insertPoint);
        }

        // Handle void methods without explicit return
        if (method.getReturnType() instanceof VoidType && returnStmts.isEmpty()) {
            Unit lastUnit = null;
            for (Unit u : units) {
                if (!(u instanceof ReturnStmt || u instanceof ReturnVoidStmt)) {
                    lastUnit = u;
                }
            }

            if (lastUnit != null) {
                units.insertAfter(createDoFilterCleanupUnits(scope, currentSpan, scopeClass, spanClass), lastUnit);
                units.add(jimple.newReturnVoidStmt());
            }
        }

        // Validate the body
        body.validate();
    }

    private List<Unit> createDoFilterCleanupUnits(Local scope, Local currentSpan,
                                                  SootClass scopeClass, SootClass spanClass) {
        List<Unit> cleanupUnits = new ArrayList<>();
        Jimple jimple = Jimple.v();

        // Create labels for null checks
        NopStmt scopeNullLabel = jimple.newNopStmt();
        NopStmt spanNullLabel = jimple.newNopStmt();

        // Check if scope is not null before closing
        cleanupUnits.add(jimple.newIfStmt(
                jimple.newEqExpr(scope, NullConstant.v()),
                scopeNullLabel
        ));

        // scope.close()
        cleanupUnits.add(jimple.newInvokeStmt(
                jimple.newInterfaceInvokeExpr(
                        scope,
                        scopeClass.getMethod("close", Collections.emptyList()).makeRef()
                )
        ));

        cleanupUnits.add(scopeNullLabel);

        // Check if currentSpan is not null before ending
        cleanupUnits.add(jimple.newIfStmt(
                jimple.newEqExpr(currentSpan, NullConstant.v()),
                spanNullLabel
        ));

        // currentSpan.end()
        cleanupUnits.add(jimple.newInvokeStmt(
                jimple.newInterfaceInvokeExpr(
                        currentSpan,
                        spanClass.getMethod("end", Collections.emptyList()).makeRef()
                )
        ));

        cleanupUnits.add(spanNullLabel);

        return cleanupUnits;
    }
}