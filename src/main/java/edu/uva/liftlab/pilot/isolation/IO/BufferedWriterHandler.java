package edu.uva.liftlab.pilot.isolation.IO;
import soot.*;
import soot.jimple.*;

class BufferedWriterHandler extends BaseIOHandler {
    private static final String FILES_CLASS = "java.nio.file.Files";
    private static final String SHADOW_FILE_OUTPUT_STREAM =
            "org.pilot.filesystem.ShadowFileOutputStream";

    @Override
    public boolean handle(IOContext context) {
        InvokeExpr expr = context.getInvokeExpr();
        if (expr == null || !isFilesNewBufferedWriter(expr)) {
            return false;
        }

        SootMethodRef initMethod = makeMethodRef(
                SHADOW_FILE_OUTPUT_STREAM,
                "initShadowBufferedWriter",
                expr.getMethod().getParameterTypes(),
                expr.getMethod().getReturnType(),
                true
        );

        StaticInvokeExpr newExpr = Jimple.v().newStaticInvokeExpr(
                initMethod,
                expr.getArgs()
        );

        replaceStatement(context, newExpr);
        return true;
    }

    private boolean isFilesNewBufferedWriter(InvokeExpr expr) {
        return expr.getMethod().getDeclaringClass().getName().equals(FILES_CLASS)
                && expr.getMethod().getName().equals("newBufferedWriter");
    }
}
