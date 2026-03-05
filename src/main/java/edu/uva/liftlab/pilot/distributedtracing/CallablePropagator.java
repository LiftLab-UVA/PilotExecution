package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class CallablePropagator {

    public SootClass sootClass;
    CallablePropagator(SootClass sootClass){
        this.sootClass = sootClass;
    }
    public static final String NEED_DRY_RUN_TRACE = "needDryRunTrace$";

    protected void injectBaggageInMethod(SootMethod method) {
        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        for (Unit u : originalUnits) {
            if (u instanceof AssignStmt) {
                AssignStmt stmt = (AssignStmt) u;
                Value rightOp = stmt.getRightOp();

                if (isCallableType(rightOp.getType())) {
                    if (rightOp instanceof NewExpr) {
                        RefType callableType = (RefType) rightOp.getType();
                        SootClass callableClass = callableType.getSootClass();

                        Unit initCall = findConstructorCall(units, u, stmt.getLeftOp());
                        if (initCall != null) {
                            setNeedBaggageForClassHierarchy(callableClass, stmt.getLeftOp(), units, initCall);
                        }
                    } else {
                        RefType callableType = (RefType) rightOp.getType();
                        SootClass callableClass = callableType.getSootClass();

                        Local callableLocal = lg.generateLocal(callableType);

                        Unit assignToLocal = Jimple.v().newAssignStmt(callableLocal, rightOp);
                        units.insertBefore(assignToLocal, u);

                        setNeedBaggageForClassHierarchy(callableClass, callableLocal, units, u);
                    }
                }
            }
        }
    }

    private Unit findConstructorCall(UnitPatchingChain units, Unit newExprUnit, Value instance) {
        Unit current = newExprUnit;
        while (current != null) {
            current = units.getSuccOf(current);
            if (current instanceof InvokeStmt) {
                InvokeStmt invoke = (InvokeStmt) current;
                if (invoke.getInvokeExpr() instanceof SpecialInvokeExpr) {
                    SpecialInvokeExpr specialInvoke = (SpecialInvokeExpr) invoke.getInvokeExpr();
                    if (specialInvoke.getBase().equivTo(instance) &&
                            specialInvoke.getMethod().getName().equals("<init>")) {
                        return current;
                    }
                }
            }
        }
        return null;
    }

    private void setNeedBaggageForClassHierarchy(SootClass targetClass, Value instance, UnitPatchingChain units, Unit insertPoint) {
        if (targetClass == null || targetClass.getName().equals("java.lang.Object")) {
            return;
        }

        if (targetClass.declaresField(NEED_DRY_RUN_TRACE, BooleanType.v())) {
            FieldRef needBaggageFieldRef = Jimple.v().newInstanceFieldRef(
                    instance,
                    targetClass.getField(NEED_DRY_RUN_TRACE, BooleanType.v()).makeRef()
            );

            Unit setNeedBaggage = Jimple.v().newAssignStmt(
                    needBaggageFieldRef,
                    IntConstant.v(1)
            );

            units.insertAfter(setNeedBaggage, insertPoint);
        }

        if (targetClass.hasSuperclass()) {
            setNeedBaggageForClassHierarchy(targetClass.getSuperclass(), instance, units, insertPoint);
        }
    }

    public void wrapCallable() {
        if (!implementsCallable(sootClass)) {
            return;
        }

        SootMethod callMethod = sootClass.getMethod("call", Collections.emptyList(), RefType.v("java.lang.Object"));
        Body body = callMethod.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        Unit lastIdentityStmt = null;
        for (Unit u : units) {
            if (u instanceof IdentityStmt) {
                lastIdentityStmt = u;
            } else {
                break;
            }
        }

        NopStmt nop = Jimple.v().newNopStmt();

        Local needBaggageLocal = lg.generateLocal(BooleanType.v());
        FieldRef needBaggageFieldRef = Jimple.v().newInstanceFieldRef(
                body.getThisLocal(),
                sootClass.getField(NEED_DRY_RUN_TRACE, BooleanType.v()).makeRef()
        );
        Unit assignNeedBaggage = Jimple.v().newAssignStmt(needBaggageLocal, needBaggageFieldRef);

        StaticInvokeExpr createBaggageExpr = Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(
                        Scene.v().loadClassAndSupport("org.apache.cassandra.utils.dryrun.TraceUtil"),
                        "createDryRunBaggage",
                        Collections.emptyList(),
                        Scene.v().getType("io.opentelemetry.api.baggage.Baggage"),
                        true
                )
        );
        Unit createBaggageStmt = Jimple.v().newInvokeStmt(createBaggageExpr);

        IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newEqExpr(needBaggageLocal, IntConstant.v(0)),
                nop
        );

        if (lastIdentityStmt != null) {
            units.insertAfter(assignNeedBaggage, lastIdentityStmt);
        } else {
            units.insertBefore(assignNeedBaggage, units.getFirst());
        }

        units.insertAfter(ifStmt, assignNeedBaggage);
        units.insertAfter(createBaggageStmt, ifStmt);
        units.insertAfter(nop, createBaggageStmt);

        body.validate();
    }


    private boolean isCallableType(Type type) {
        if (!(type instanceof RefType)) {
            return false;
        }
        return implementsCallable(((RefType) type).getSootClass());
    }

    private boolean implementsCallable(SootClass cls) {
        if (cls.getName().equals("java.util.concurrent.Callable")) {
            return true;
        }

        for (SootClass iface : cls.getInterfaces()) {
            if (implementsCallable(iface)) {
                return true;
            }
        }

        if (cls.hasSuperclass()) {
            return implementsCallable(cls.getSuperclass());
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

        Value originalCallable = stmt.getRightOp();

        Local tempCallable = lg.generateLocal(RefType.v("java.util.concurrent.Callable"));
        units.insertBefore(
                Jimple.v().newAssignStmt(tempCallable, originalCallable),
                unit
        );

        Local wrappedCallable = lg.generateLocal(RefType.v("java.util.concurrent.Callable"));
        units.insertBefore(
                Jimple.v().newAssignStmt(
                        wrappedCallable,
                        Jimple.v().newInterfaceInvokeExpr(
                                contextLocal,
                                contextClass.getMethod("wrap",
                                        Collections.singletonList(RefType.v("java.util.concurrent.Callable")),
                                        RefType.v("java.util.concurrent.Callable")
                                ).makeRef(),
                                tempCallable
                        )
                ),
                unit
        );
        stmt.setRightOp(
                wrappedCallable
        );
    }
}
