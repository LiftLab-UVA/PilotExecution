package edu.uva.liftlab.pilot.isolation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.List;

import static edu.uva.liftlab.pilot.util.SootUtils.dryRunMethodshouldBeInstrumented;

public class FunctionRedirection {
    private final SootClass sootClass;
    private static final Logger LOG = LoggerFactory.getLogger(FunctionRedirection.class);
    private static final String INSTRUMENTATION_SUFFIX = "$instrumentation";

    public FunctionRedirection(SootClass sootClass) {
        this.sootClass = sootClass;
    }

    public void redirectFunctionsInMethod$instrumentation() {
        for (SootMethod method : sootClass.getMethods()) {
            if (method.isConcrete() && dryRunMethodshouldBeInstrumented(method, sootClass)) {
                redirectMethod(method);
            }
        }
    }

    private void redirectMethod(SootMethod method) {
        if (!method.hasActiveBody()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        Body instrumentationBody = Jimple.v().newBody(method);
        instrumentationBody.importBodyContentsFrom(body);
        redirectMethodCalls(instrumentationBody);
        instrumentationBody.validate();
        method.setActiveBody(instrumentationBody);
    }

    private void redirectMethodCalls(Body body) {
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);

        for (Unit u : originalUnits) {
            if (u instanceof InvokeStmt) {
                InvokeStmt stmt = (InvokeStmt) u;
                redirectInvokeExpr(stmt.getInvokeExpr());
            }

            else if (u instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) u;
                if (stmt.getRightOp() instanceof InvokeExpr) {
                    redirectInvokeExpr((InvokeExpr) stmt.getRightOp());
                }
            }
        }
    }

    private void redirectInvokeExpr(InvokeExpr invokeExpr) {
//        SootMethod originalMethod = invokeExpr.getMethod();
//        String instrumentationMethodName = originalMethod.getName() + INSTRUMENTATION_SUFFIX;
//
//        try {

//            SootMethod instrumentationMethod = originalMethod.getDeclaringClass()
//                    .getMethodByName(instrumentationMethodName);
//

//            if (instrumentationMethod != null) {
//                if (invokeExpr instanceof StaticInvokeExpr) {

//                    StaticInvokeExpr staticInvoke = (StaticInvokeExpr) invokeExpr;
//                    List<Value> args = staticInvoke.getArgs();
//                    StaticInvokeExpr newInvoke = Jimple.v().newStaticInvokeExpr(
//                            instrumentationMethod.makeRef(), args);
//                    replaceInvokeExpr(invokeExpr, newInvoke);
//                } else if (invokeExpr instanceof InstanceInvokeExpr) {

//                    InstanceInvokeExpr instanceInvoke = (InstanceInvokeExpr) invokeExpr;
//                    List<Value> args = instanceInvoke.getArgs();
//                    Value base = instanceInvoke.getBase();
//
//                    InstanceInvokeExpr newInvoke;
//                    if (invokeExpr instanceof VirtualInvokeExpr) {
//                        newInvoke = Jimple.v().newVirtualInvokeExpr(
//                                base, instrumentationMethod.makeRef(), args);
//                    } else if (invokeExpr instanceof SpecialInvokeExpr) {
//                        newInvoke = Jimple.v().newSpecialInvokeExpr(
//                                base, instrumentationMethod.makeRef(), args);
//                    } else if (invokeExpr instanceof InterfaceInvokeExpr) {
//                        newInvoke = Jimple.v().newInterfaceInvokeExpr(
//                                base, instrumentationMethod.makeRef(), args);
//                    } else {
//                        LOG.warn("Unsupported invoke expression type: {}", invokeExpr.getClass());
//                        return;
//                    }
//                    replaceInvokeExpr(invokeExpr, newInvoke);
//                }
//            }
//        } catch (RuntimeException e) {
//            LOG.debug("Method {} does not have an instrumentation version in class {}",
//                    originalMethod.getName(), originalMethod.getDeclaringClass());
//        }
    }

    private void replaceInvokeExpr(InvokeExpr oldInvoke, InvokeExpr newInvoke) {

        Body body = oldInvoke.getMethod().getActiveBody();
        for (Unit unit : body.getUnits()) {
            if (unit instanceof InvokeStmt) {
                InvokeStmt stmt = (InvokeStmt) unit;
                if (stmt.getInvokeExpr() == oldInvoke) {
                    stmt.setInvokeExpr(newInvoke);
                }
            } else if (unit instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) unit;
                if (stmt.getRightOp() == oldInvoke) {
                    stmt.setRightOp(newInvoke);
                }
            }
        }
    }
}