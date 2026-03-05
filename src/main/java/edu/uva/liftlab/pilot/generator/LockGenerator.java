package edu.uva.liftlab.pilot.generator;

import soot.*;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.Stmt;
import soot.jimple.internal.JEnterMonitorStmt;
import soot.jimple.internal.JExitMonitorStmt;

import java.util.*;

public class LockGenerator {

    public static final String LOCK_MANAGER_CLASS = "org.pilot.concurrency.LockManager";
    public static final String LOCK_METHOD = "void lock(java.lang.Object)";
    public static final String UNLOCK_METHOD = "void unlock(java.lang.Object)";

    public void transformLocks(SootMethod method){
        if(!method.hasActiveBody()){
            return;
        }

        Body body = method.getActiveBody();
        PatchingChain<Unit> units = body.getUnits();

        List<Unit> toRemove = new ArrayList<>();
        Map<Unit, List<Unit>> replacements = new HashMap<>();

        for(Unit unit:units){
            if(unit instanceof JEnterMonitorStmt){
                JEnterMonitorStmt enterMonitorStmt = (JEnterMonitorStmt) unit;
                Value lockObject = enterMonitorStmt.getOp();


                SootMethod lockMethod = getLockMethod();
                InvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(lockMethod.makeRef(), lockObject);
                Stmt lockStmt = Jimple.v().newInvokeStmt(invokeExpr);

                // Replace the original statement with the new one
                replacements.put(unit, Collections.singletonList(lockStmt));
                toRemove.add(unit);
            }else if(unit instanceof JExitMonitorStmt){
                JExitMonitorStmt exitMonitorStmt = (JExitMonitorStmt) unit;
                Value lockObject = exitMonitorStmt.getOp();

                SootMethod unlockMethod = getUnlockMethod();
                InvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(unlockMethod.makeRef(), lockObject);
                Stmt unlockStmt = Jimple.v().newInvokeStmt(invokeExpr);

                // Replace the original statement with the new one
                replacements.put(unit, Collections.singletonList(unlockStmt));
                toRemove.add(unit);
            }
        }

        for(Unit oldUnit: toRemove){
            List<Unit> newUnits = replacements.get(oldUnit);
            Unit insertPoint = oldUnit;

            for(Unit newUnit: newUnits){
                units.insertBefore(newUnit, insertPoint);
            }

            units.remove(oldUnit);
        }
    }

    public SootMethod getLockMethod(){
        SootClass lockManagerClass = Scene.v().loadClassAndSupport(LOCK_MANAGER_CLASS);

        return lockManagerClass.getMethod(LOCK_METHOD);
    }

    public SootMethod getUnlockMethod(){
        SootClass lockManagerClass = Scene.v().loadClassAndSupport(LOCK_MANAGER_CLASS);
        return lockManagerClass.getMethod(UNLOCK_METHOD);
    }
}
