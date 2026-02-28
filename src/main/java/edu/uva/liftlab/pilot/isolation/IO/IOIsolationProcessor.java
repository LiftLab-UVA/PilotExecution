package edu.uva.liftlab.pilot.isolation.IO;

import edu.uva.liftlab.pilot.util.LocalGeneratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.*;


public class IOIsolationProcessor {
    private final IOOperationHandler handler;
    private static final Logger logger = LoggerFactory.getLogger(IOIsolationProcessor.class);

    public IOIsolationProcessor() {
        this.handler = new CompositeIOHandler();
    }

    public void redirectIOOperations(Body body) {
        UnitPatchingChain units = body.getUnits();
        List<Unit> originalUnits = new ArrayList<>(units);
        LocalGeneratorUtil lg = new LocalGeneratorUtil(body);
        Set<Unit> toRemove = new HashSet<>();

        for (Unit unit : originalUnits) {
            if (unit instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) unit;
                Value rightOp = assign.getRightOp();

                if (rightOp instanceof NewExpr) {
                    //handleConstructor(unit, assign, units, lg, body.getMethod(), toRemove);
                }
                else if (rightOp instanceof InvokeExpr) {
                    IOContext context = new IOContext(unit, null, units, lg, body.getMethod());
                    handler.handle(context);
                    toRemove.addAll(context.toRemove);
                }
            }
            else if (unit instanceof InvokeStmt) {
                IOContext context = new IOContext(unit, null, units, lg, body.getMethod());
                handler.handle(context);
                toRemove.addAll(context.toRemove);
            }
        }

        for (Unit unit : toRemove) {
            units.remove(unit);
        }

    }

    private void handleConstructor(Unit currentUnit, AssignStmt newAssign,
                                   UnitPatchingChain units, LocalGeneratorUtil lg,
                                   SootMethod method, Set<Unit> toRemove) {
        Value leftOp = newAssign.getLeftOp();

        Unit initUnit = findConstructorCall(units, currentUnit, leftOp);
        if (initUnit == null) {
            return;
        }

        logger.info("Found Constructor: {} with init call: {}", currentUnit, initUnit);

        IOContext context = new IOContext(
                currentUnit,
                initUnit,
                units,
                lg,
                method
        );

        if (handler.handle(context)) {
            toRemove.add(currentUnit);
            toRemove.add(initUnit);
        }
    }

    private Unit findConstructorCall(UnitPatchingChain units, Unit startUnit, Value targetLocal) {
        Iterator<Unit> it = units.iterator(startUnit);
        while (it.hasNext()) {
            Unit unit = it.next();

            if (unit instanceof InvokeStmt) {
                InvokeExpr expr = ((InvokeStmt) unit).getInvokeExpr();
                if (expr instanceof SpecialInvokeExpr) {
                    SpecialInvokeExpr constructorCall = (SpecialInvokeExpr) expr;
                    if (constructorCall.getMethod().getName().equals("<init>") &&
                            constructorCall.getBase().equivTo(targetLocal)) {
                        return unit;
                    }
                }
            }
            else if (unit instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) unit;
                if (assign.containsInvokeExpr()) {
                    InvokeExpr expr = assign.getInvokeExpr();
                    if (expr instanceof SpecialInvokeExpr) {
                        SpecialInvokeExpr constructorCall = (SpecialInvokeExpr) expr;
                        if (constructorCall.getMethod().getName().equals("<init>") &&
                                constructorCall.getBase().equivTo(targetLocal)) {
                            return unit;
                        }
                    }
                }
            }
        }
        return null;
    }
}