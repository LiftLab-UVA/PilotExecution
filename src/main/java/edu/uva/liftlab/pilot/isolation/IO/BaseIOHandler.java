package edu.uva.liftlab.pilot.isolation.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;

import java.util.List;

public abstract class BaseIOHandler implements IOOperationHandler{
    protected static final Logger logger = LoggerFactory.getLogger(BaseIOHandler.class);
    protected void replaceStatement(IOContext context, Expr newExpr) {
        Unit unit = context.getUnit();
        UnitPatchingChain units = context.getUnits();
        Value leftOp = context.getLeftOp();

        Unit newUnit = leftOp != null
                ? Jimple.v().newAssignStmt(leftOp, newExpr)
                : Jimple.v().newInvokeStmt(newExpr);

        units.insertBefore(newUnit, unit);
        context.toRemove.add(unit);
    }

    protected SootMethodRef makeMethodRef(String className, String methodName,
                                          List<Type> paramTypes, Type returnType,
                                          boolean isStatic) {
        try {
            SootClass sootClass = Scene.v().getSootClass(className);
//            logger.info("Searching for method: {} in class: {}", methodName, sootClass.getName());
//
//            //print method in sootclass
//            for(SootMethod method : sootClass.getMethods()) {
//                if (method.getName().equals(methodName)) {
//                    logger.info("Found method: " + method);
//                }
//            }
            if (!sootClass.declaresMethod(methodName, paramTypes, returnType)) {
                //logger.error("Method {} not found in class {}", methodName, className);
                return null;
            }
            return Scene.v().makeMethodRef(
                    sootClass,
                    methodName,
                    paramTypes,
                    returnType,
                    isStatic
            );
        } catch (RuntimeException e) {
            return null;
        }
    }
}
