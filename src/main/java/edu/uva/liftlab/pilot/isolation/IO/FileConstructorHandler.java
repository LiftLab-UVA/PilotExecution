package edu.uva.liftlab.pilot.isolation.IO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.AssignStmt;
import soot.jimple.Jimple;

class FileConstructorHandler extends BaseIOHandler {
    private static final String FILE_CLASS = "java.io.File";
    private static final String SHADOW_FILE = "org.pilot.filesystem.ShadowFile";

    private static final Logger logger = LoggerFactory.getLogger(FileConstructorHandler.class);

    @Override
    public boolean handle(IOContext context) {
        NewExpr expr = context.getNewExpr();

        if (expr == null || !isFileConstructor(expr)) {
            return false;
        }

        SpecialInvokeExpr constructorInvoke = context.getConstructorInvoke();
        logger.info("Find File Constructor"+constructorInvoke);
        if (constructorInvoke == null) {
            return false;
        }

        logger.info("Find File Constructor"+constructorInvoke);
        SootMethodRef initFile = makeMethodRef(
                SHADOW_FILE,
                "initFile",
                constructorInvoke.getMethod().getParameterTypes(),
                Scene.v().getSootClass(FILE_CLASS).getType(),
                true
        );
        if (initFile == null) {
            logger.error("Failed to create method reference for initFile");
            return false;
        }

        StaticInvokeExpr newExpr = Jimple.v().newStaticInvokeExpr(
                initFile,
                constructorInvoke.getArgs()
        );


        Unit newUnit = Jimple.v().newAssignStmt(
                ((AssignStmt) context.getUnit()).getLeftOp(),
                newExpr
        );
        logger.info("Find File Constructor new unit is "+newUnit);

        context.getUnits().insertAfter(newUnit, context.getConstructorUnit());
        return true;
    }

    private boolean isFileConstructor(NewExpr expr) {
        return expr.getBaseType().toString().contains(FILE_CLASS);
    }
}