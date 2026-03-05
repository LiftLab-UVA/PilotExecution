package edu.uva.liftlab.pilot.isolation.stateredirection;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.List;

public class FieldAccessProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(FieldAccessProcessor.class);

    private final FieldRedirector fieldRedirector;
    private final UnitGenerator unitGenerator;
    private int id = 0;

    public FieldAccessProcessor() {
        this.fieldRedirector = new FieldRedirector();
        this.unitGenerator = new UnitGenerator();
    }

    public List<Unit> processFieldWrite(FieldRef fieldRef, Local pilotIdLocal) {
        List<Unit> units = new ArrayList<>();

        FieldInfo fieldInfo = buildFieldInfo(fieldRef);
        if (fieldInfo == null || !fieldInfo.isValid()) {
            LOG.debug("Invalid field info for {}", fieldRef);
            return units;
        }

        return unitGenerator.generateUnitsForWriteOperationToField(fieldRef, fieldInfo, pilotIdLocal);
    }


    public List<Unit> processFieldAccess(FieldRef fieldRef, LocalGeneratorUtil lg,
                                         SootMethod method, Local pilotIdLocal) {
        List<Unit> units = new ArrayList<>();

        FieldInfo fieldInfo = buildFieldInfo(fieldRef);
        if (fieldInfo == null || !fieldInfo.isValid()) {
            LOG.debug("Invalid field info for {}", fieldRef);
            return units;
        }

        return unitGenerator.generateUnits(fieldRef, lg, fieldInfo, method, ++id, pilotIdLocal);
    }

    public void handleAssignStmt(AssignStmt stmt, UnitPatchingChain units,
                                 LocalGeneratorUtil lg, SootMethod method,
                                 Local pilotIdLocal) {

        if (stmt.getRightOp() instanceof FieldRef) {
            FieldRef fieldRef = (FieldRef) stmt.getRightOp();
            processAssignStmtOp(fieldRef, stmt, true, units, stmt, lg, method, pilotIdLocal);
        }

        if (stmt.getLeftOp() instanceof FieldRef) {
            FieldRef fieldRef = (FieldRef) stmt.getLeftOp();
            processAssignStmtOp(fieldRef, stmt, false, units, stmt, lg, method, pilotIdLocal);
        }
    }

    public void handleInvokeStmt(Unit u, UnitPatchingChain units,
                                 LocalGeneratorUtil lg, SootMethod method,
                                 Local pilotIdLocal) {
        InvokeExpr invoke = getInvokeExpr(u);
        if (invoke == null || !(invoke instanceof InstanceInvokeExpr)) {
            return;
        }

        Value base = ((InstanceInvokeExpr) invoke).getBase();
        if (base instanceof FieldRef) {
            processInvokeStmtField((FieldRef) base, (InstanceInvokeExpr) invoke,
                    units, u, lg, method, pilotIdLocal);
        }
    }

    private void processAssignStmtOp(FieldRef fieldRef, AssignStmt stmt,
                                     boolean isRightOp, UnitPatchingChain units,
                                     Unit insertPoint, LocalGeneratorUtil lg,
                                     SootMethod method, Local pilotIdLocal) {
        FieldRef dryRunRef = fieldRedirector.getDryRunFieldRef(fieldRef);
        if (dryRunRef == null) {
            return;
        }
        List<Unit> newUnits;
        if(!isRightOp){
            newUnits = processFieldWrite(fieldRef, pilotIdLocal);
        }else{
            newUnits = processFieldAccess(fieldRef, lg, method, pilotIdLocal);
        }
        if (!newUnits.isEmpty()) {
            units.insertBefore(newUnits, insertPoint);
            if (isRightOp) {
                stmt.setRightOp(dryRunRef);
            } else {
                stmt.setLeftOp(dryRunRef);
            }
        }
    }

    private void processInvokeStmtField(FieldRef fieldRef, InstanceInvokeExpr invoke,
                                        UnitPatchingChain units, Unit insertPoint,
                                        LocalGeneratorUtil lg, SootMethod method,
                                        Local pilotIdLocal) {
        FieldRef dryRunRef = fieldRedirector.getDryRunFieldRef(fieldRef);
        if (dryRunRef == null) {
            return;
        }

        List<Unit> newUnits = processFieldAccess(fieldRef, lg, method, pilotIdLocal);
        if (!newUnits.isEmpty()) {
            units.insertBefore(newUnits, insertPoint);
            invoke.setBase(dryRunRef);
        }
    }

    private InvokeExpr getInvokeExpr(Unit u) {
        if (u instanceof InvokeStmt) {
            return ((InvokeStmt) u).getInvokeExpr();
        }
        if (u instanceof AssignStmt && ((AssignStmt)u).getRightOp() instanceof InvokeExpr) {
            return (InvokeExpr) ((AssignStmt)u).getRightOp();
        }
        return null;
    }

    private FieldInfo buildFieldInfo(FieldRef fieldRef) {
        try {
            return new FieldInfo(fieldRef);
        } catch (Exception e) {
            LOG.error("Failed to build field info for {}: {}", fieldRef, e.getMessage());
            return null;
        }
    }
}