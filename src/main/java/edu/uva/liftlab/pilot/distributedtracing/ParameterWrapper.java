package edu.uva.liftlab.pilot.distributedtracing;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import edu.uva.liftlab.pilot.util.SootUtils;
import soot.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static edu.uva.liftlab.pilot.util.Constants.PILOT_UTIL_CLASS_NAME;

public abstract class ParameterWrapper {
    SootClass sootClass;

    public ParameterWrapper(SootClass sootClass){
        this.sootClass = sootClass;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ParameterWrapper.class);

    public abstract void wrapParameter();

    protected Unit getLastIdentityStmt(PatchingChain<Unit> units) {
        Unit lastIdentityStmt = null;
        for (Unit u : units) {
            if (u instanceof IdentityStmt) {
                lastIdentityStmt = u;
            } else {
                break;
            }
        }
        return lastIdentityStmt;
    }


    public void wrapInterface(SootMethod targetMethod) {
        Body body = targetMethod.retrieveActiveBody();
        PatchingChain<Unit> units = body.getUnits();
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);

        Unit lastIdentityStmt = getLastIdentityStmt(units);

        Local needBaggageLocal = lg.generateLocal(BooleanType.v());
        Local scopeLocal = lg.generateLocal(Scene.v().loadClassAndSupport("io.opentelemetry.context.Scope").getType());
        Local exceptionLocal = lg.generateLocal(RefType.v("java.lang.Throwable"));

        NopStmt tryStart = Jimple.v().newNopStmt();
        NopStmt tryEnd = Jimple.v().newNopStmt();
        NopStmt finallyStart = Jimple.v().newNopStmt();
        //NopStmt afterFinally = Jimple.v().newNopStmt(); // Position after finally block

        FieldRef needBaggageFieldRef = Jimple.v().newInstanceFieldRef(
                body.getThisLocal(),
                sootClass.getField(SootUtils.getDryRunTraceFieldName(sootClass), BooleanType.v()).makeRef()
        );

        // Initialize variables and insert tryStart at the beginning of the method (after identity statements)
        List<Unit> initStmts = Arrays.asList(
                Jimple.v().newAssignStmt(needBaggageLocal, needBaggageFieldRef),
                Jimple.v().newAssignStmt(
                        scopeLocal,
                        Jimple.v().newStaticInvokeExpr(
                                Scene.v().makeMethodRef(
                                        Scene.v().loadClassAndSupport(PILOT_UTIL_CLASS_NAME),
                                        "getDryRunTraceScope",
                                        Collections.singletonList(BooleanType.v()),
                                        scopeLocal.getType(),
                                        true
                                ),
                                needBaggageLocal
                        )
                ),
                tryStart
        );

        if (lastIdentityStmt != null) {
            units.insertAfter(initStmts, lastIdentityStmt);
        } else {
            units.insertBefore(initStmts, units.getFirst());
        }

        // Create scope.close() statement that will be inserted before each return
        InvokeStmt closeStmt = Jimple.v().newInvokeStmt(
                Jimple.v().newInterfaceInvokeExpr(
                        scopeLocal,
                        Scene.v().makeMethodRef(
                                Scene.v().loadClassAndSupport("io.opentelemetry.context.Scope"),
                                "close",
                                Collections.emptyList(),
                                VoidType.v(),
                                false
                        )
                )
        );

        // Insert scope.close() before all return statements
        List<Unit> returnStmts = new ArrayList<>();
        for (Unit u : units) {
            if (u instanceof ReturnStmt || u instanceof ReturnVoidStmt) {
                returnStmts.add(u);
            }
        }
        for (Unit returnStmt : returnStmts) {
            units.insertBefore((Unit) closeStmt.clone(), returnStmt);
        }

        // Insert tryEnd before the last unit
        units.insertBefore(tryEnd, units.getLast());

        IdentityStmt caughtExceptionStmt = Jimple.v().newIdentityStmt(
                exceptionLocal,
                Jimple.v().newCaughtExceptionRef()
        );

        units.add(caughtExceptionStmt);
        units.add((Unit) closeStmt.clone());  // close()
        units.add(Jimple.v().newThrowStmt(exceptionLocal));  // throw

        // Define trap
        body.getTraps().add(Jimple.v().newTrap(
                Scene.v().getSootClass("java.lang.Throwable"),
                tryStart,
                tryEnd,
                caughtExceptionStmt
        ));

        body.validate();
    }






}
