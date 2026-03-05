package edu.uva.liftlab.pilot.isolation.IO;
import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import soot.*;
import soot.jimple.*;

import java.util.HashSet;
import java.util.Set;

class IOContext {
    public Set<Unit> toRemove = new HashSet<>();
    private final Unit unit;
    private final Unit constructorUnit;
    private final UnitPatchingChain units;
    private final LocalGeneratorUtil lg;
    private final SootMethod method;
    private InvokeExpr invokeExpr;
    private NewExpr newExpr;

    public IOContext(Unit unit, Unit constructorUnit, UnitPatchingChain units,
                     LocalGeneratorUtil lg, SootMethod method) {
        this.unit = unit;
        this.constructorUnit = constructorUnit;
        this.units = units;
        this.lg = lg;
        this.method = method;
        extractExpressions();
    }

    private void extractExpressions() {
        if (unit instanceof InvokeStmt) {
            this.invokeExpr = ((InvokeStmt) unit).getInvokeExpr();
        } else if (unit instanceof AssignStmt) {
            Value rightOp = ((AssignStmt) unit).getRightOp();
            if (rightOp instanceof InvokeExpr) {
                this.invokeExpr = (InvokeExpr) rightOp;
            } else if (rightOp instanceof NewExpr) {
                this.newExpr = (NewExpr) rightOp;
            }
        }
    }

    public Unit getUnit() { return unit; }
    public Unit getConstructorUnit() { return constructorUnit; }
    public UnitPatchingChain getUnits() { return units; }
    public LocalGeneratorUtil getLg() { return lg; }
    public SootMethod getMethod() { return method; }
    public InvokeExpr getInvokeExpr() { return invokeExpr; }
    public NewExpr getNewExpr() { return newExpr; }

    public Value getLeftOp() {
        return unit instanceof AssignStmt ? ((AssignStmt) unit).getLeftOp() : null;
    }

    public SpecialInvokeExpr getConstructorInvoke() {
        if (constructorUnit instanceof AssignStmt) {
            Value rightOp = ((AssignStmt) constructorUnit).getRightOp();
            if (rightOp instanceof SpecialInvokeExpr) {
                return (SpecialInvokeExpr) rightOp;
            }
        }else if(constructorUnit instanceof InvokeStmt) {
            return (SpecialInvokeExpr) ((InvokeStmt) constructorUnit).getInvokeExpr();
        }
        return null;
    }
}
