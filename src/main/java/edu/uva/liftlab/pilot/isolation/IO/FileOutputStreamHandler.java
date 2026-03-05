package edu.uva.liftlab.pilot.isolation.IO;
import soot.*;
import soot.jimple.*;

class FileOutputStreamHandler extends BaseIOHandler {
    private static final String FILE_OUTPUT_STREAM_CLASS = "java.io.FileOutputStream";
    private static final String SHADOW_FILE_OUTPUT_STREAM =
            "org.pilot.filesystem.ShadowFileOutputStream";

    @Override
    public boolean handle(IOContext context) {
        NewExpr expr = context.getNewExpr();
        if (expr == null || !isFileOutputStreamConstructor(expr)) {
            return false;
        }


        SpecialInvokeExpr constructorInvoke = context.getConstructorInvoke();
        System.out.println("FileOutputstream constructorInvoke = " + constructorInvoke);
        if (constructorInvoke == null) {
            return false;
        }

        SootMethodRef initMethod = makeMethodRef(
                SHADOW_FILE_OUTPUT_STREAM,
                "initShadowFileOutputStream",
                constructorInvoke.getMethod().getParameterTypes(),
                Scene.v().getSootClass(FILE_OUTPUT_STREAM_CLASS).getType(),
                true
        );
        if (initMethod == null) {
            return false;
        }


        StaticInvokeExpr initExpr = Jimple.v().newStaticInvokeExpr(
                initMethod,
                constructorInvoke.getArgs()
        );

        Unit newUnit = Jimple.v().newAssignStmt(
                ((AssignStmt) context.getUnit()).getLeftOp(),
                initExpr
        );

        context.getUnits().insertAfter(newUnit, context.getConstructorUnit());
        return true;
    }

    private boolean isFileOutputStreamConstructor(NewExpr expr) {
        return expr.getBaseType().toString().contains(FILE_OUTPUT_STREAM_CLASS);
    }
}
